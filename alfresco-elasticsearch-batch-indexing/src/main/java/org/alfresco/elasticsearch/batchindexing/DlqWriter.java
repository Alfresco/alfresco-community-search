/*-
 * #%L
 * Alfresco Elasticsearch Connector :: Batch Indexing Module
 * %%
 * Copyright (C) 2026 Alfresco Software Limited
 * %%
 * This file is part of the Alfresco software.
 * If the software was purchased under a paid Alfresco license, the terms of
 * the paid license agreement will prevail.  Otherwise, the software is
 * provided under the following open source license terms:
 *
 * Alfresco is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Alfresco is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Alfresco. If not, see <http://www.gnu.org/licenses/>.
 * #L%
 */
package org.alfresco.elasticsearch.batchindexing;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.core.DeleteRequest;
import org.opensearch.client.opensearch.core.GetRequest;
import org.opensearch.client.opensearch.core.GetResponse;
import org.opensearch.client.opensearch.core.IndexRequest;
import org.opensearch.client.opensearch.indices.CreateIndexRequest;
import org.opensearch.client.opensearch.indices.ExistsRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.alfresco.elasticsearch.batchindexing.model.DeadLetterDocument;
import org.alfresco.elasticsearch.batchindexing.model.DeadLetterDocument.FailureStage;

/**
 * Writes dead-letter records to a dedicated Elasticsearch index, and deletes them once the drain confirms a node recovered. Best-effort throughout: a DLQ write failure never blocks indexing. ERROR logs on write failure are rate-limited to one per minute per failure-type key to avoid log spam if the DLQ index is broken for a sustained period.
 * <p>
 * Reading the queue back is {@link DlqReader}'s job — it throws where this class swallows.
 */
public class DlqWriter
{
    private static final Logger LOGGER = LoggerFactory.getLogger(DlqWriter.class);

    /** Cap for the truncated failure message stored in ES. */
    static final int MAX_REASON_LENGTH = 1024;

    private static final Duration LOG_INTERVAL = Duration.ofMinutes(1);

    private final OpenSearchClient openSearchClient;
    private final String indexName;

    /** Rate-limiting map keyed by failure-type. Bounded by the small enum of write-side errors we ever see. */
    private final ConcurrentMap<String, Instant> lastLoggedByReason = new ConcurrentHashMap<>();

    public DlqWriter(OpenSearchClient openSearchClient, String indexName)
    {
        this.openSearchClient = openSearchClient;
        this.indexName = indexName;
    }

    /**
     * Best-effort index creation on startup. Logs but does not throw — a broken DLQ must not take down indexing.
     */
    public void ensureIndexExists()
    {
        try
        {
            boolean exists = openSearchClient.indices()
                    .exists(new ExistsRequest.Builder().index(indexName).build())
                    .value();
            if (exists)
            {
                LOGGER.debug("DLQ index '{}' already exists", indexName);
                return;
            }
            openSearchClient.indices().create(new CreateIndexRequest.Builder()
                    .index(indexName)
                    .settings(s -> s.hidden(true))
                    .build());
            LOGGER.info("Created DLQ index '{}'", indexName);
        }
        catch (Exception e)
        {
            LOGGER.error("Failed to create or verify DLQ index '{}'. Dead-letter writes will keep retrying and log rate-limited errors. {}",
                    indexName, e.getMessage(), e);
        }
    }

    /**
     * Record a give-up event for {@code dbId}. If a doc already exists it is upserted in place: {@code firstFailedAt} is preserved, {@code lastFailedAt} and the failure fields are refreshed, and {@code failureCount} is incremented.
     *
     * @param dbId
     *            node database id; may be null for reader-side skips (rare), in which case no document is written
     * @param nodeRef
     *            best-effort node reference; may be null
     * @param sourceCommitTimeMs
     *            best-effort ACS commit time of the node; may be null
     * @param stage
     *            pipeline stage that produced the skip
     * @param throwable
     *            the exception Spring Batch skipped
     */
    public void recordSkip(Long dbId, String nodeRef, Long sourceCommitTimeMs, FailureStage stage, Throwable throwable)
    {
        String failureType = throwable == null ? "unknown" : throwable.getClass().getSimpleName();
        String failureReason = throwable == null ? null : throwable.getMessage();
        recordSkip(dbId, nodeRef, sourceCommitTimeMs, stage, failureType, failureReason);
    }

    /**
     * Same as {@link #recordSkip(Long, String, Long, FailureStage, Throwable)} but with the failure classifier / reason supplied as strings. Used by the write-side bulk-item bridge, which receives ES error responses rather than Java exceptions.
     */
    public void recordSkip(Long dbId, String nodeRef, Long sourceCommitTimeMs, FailureStage stage,
            String failureType, String failureReason)
    {
        if (dbId == null)
        {
            LOGGER.warn("DLQ skip without a dbId (stage={}, type={}). Not persisting a record; reason: {}",
                    stage, failureType, failureReason);
            return;
        }
        writeDocument(String.valueOf(dbId), dbId, nodeRef, sourceCommitTimeMs, stage, failureType, failureReason);
    }

    /**
     * Record a page-level read failure keyed by the failed time window. The window bounds also form the ES document id ({@code window:<tsFrom>-<tsTo>}) so repeated failures on the same window merge into one document; a burst of chunked cycles hitting the same poison rows produces a single DLQ entry with an incrementing failureCount, not N of them.
     *
     * <p>
     * {@code dbId} is left null (window-level, not node-level) and the message is prefixed with the window bounds so an operator can see the range at a glance.
     */
    public void recordWindowFailure(long tsFromMs, long tsToMs, Throwable cause)
    {
        String failureType = cause == null ? "unknown" : cause.getClass().getSimpleName();
        String message = cause == null ? null : cause.getMessage();
        String failureReason = "window [" + tsFromMs + ".." + tsToMs + "]" + (message == null ? "" : ": " + message);
        String documentKey = "window:" + tsFromMs + "-" + tsToMs;
        writeDocument(documentKey, null, null, tsFromMs, FailureStage.READER, failureType, failureReason);
    }

    private void writeDocument(String documentKey, Long dbId, String nodeRef, Long sourceCommitTimeMs, FailureStage stage,
            String failureType, String failureReason)
    {
        try
        {
            DeadLetterDocument document = mergeOrCreate(documentKey, dbId, nodeRef, sourceCommitTimeMs, stage, failureType, failureReason);
            openSearchClient.index(new IndexRequest.Builder<DeadLetterDocument>()
                    .index(indexName)
                    .id(documentKey)
                    .document(document)
                    .build());
        }
        catch (Exception e)
        {
            logRateLimited(e);
        }
    }

    private DeadLetterDocument mergeOrCreate(String documentKey, Long dbId, String nodeRef, Long sourceCommitTimeMs, FailureStage stage,
            String failureType, String failureReason)
    {
        String now = Instant.now().toString();
        String resolvedType = failureType == null ? "unknown" : failureType;
        String resolvedReason = truncate(failureReason, MAX_REASON_LENGTH);

        DeadLetterDocument document = readExisting(documentKey).orElseGet(DeadLetterDocument::new);
        if (dbId != null)
        {
            document.setDbId(dbId);
        }
        if (nodeRef != null)
        {
            document.setNodeRef(nodeRef);
        }
        if (sourceCommitTimeMs != null)
        {
            document.setSourceCommitTimeMs(sourceCommitTimeMs);
        }
        if (document.getFirstFailedAt() == null)
        {
            document.setFirstFailedAt(now);
        }
        document.setLastFailedAt(now);
        document.setFailureCount(document.getFailureCount() + 1);
        document.setFailureStage(stage);
        document.setFailureType(resolvedType);
        document.setFailureReason(resolvedReason);
        return document;
    }

    private Optional<DeadLetterDocument> readExisting(String documentKey)
    {
        try
        {
            GetResponse<DeadLetterDocument> response = openSearchClient.get(
                    new GetRequest.Builder().index(indexName).id(documentKey).build(),
                    DeadLetterDocument.class);
            if (!response.found() || response.source() == null)
            {
                return Optional.empty();
            }
            return Optional.of(response.source());
        }
        catch (Exception e)
        {
            // A read miss for a first-time failure is normal. Return empty and let the write proceed as a fresh doc.
            if (LOGGER.isDebugEnabled())
            {
                LOGGER.debug("DLQ read for id={} failed, treating as first-time failure: {}",
                        documentKey, e.getMessage());
            }
            return Optional.empty();
        }
    }

    /**
     * Delete a DLQ document by id after its node has reindexed cleanly. Best-effort: a failed delete just leaves the entry to be re-evaluated on the next {@code reindexDlq} run, which is safe (see class javadoc).
     */
    void delete(String documentId)
    {
        try
        {
            openSearchClient.delete(new DeleteRequest.Builder().index(indexName).id(documentId).build());
        }
        catch (Exception e)
        {
            logRateLimited(e);
        }
    }

    private void logRateLimited(Exception e)
    {
        String key = e.getClass().getSimpleName();
        Instant now = Instant.now();
        Instant previous = lastLoggedByReason.get(key);
        if (previous == null || Duration.between(previous, now).compareTo(LOG_INTERVAL) >= 0)
        {
            lastLoggedByReason.put(key, now);
            LOGGER.error("Failed to write DLQ entry ({}). Suppressing repeats of this failure type for {}. {}",
                    key, LOG_INTERVAL, e.getMessage(), e);
        }
    }

    private static String truncate(String s, int max)
    {
        if (s == null || s.length() <= max)
        {
            return s;
        }
        return s.substring(0, max);
    }
}
