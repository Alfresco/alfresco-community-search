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

import java.time.Instant;
import java.util.Optional;

import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.OpenSearchException;
import org.opensearch.client.opensearch.core.GetRequest;
import org.opensearch.client.opensearch.core.GetResponse;
import org.opensearch.client.opensearch.core.IndexRequest;
import org.opensearch.client.opensearch.core.IndexResponse;
import org.opensearch.client.opensearch.indices.CreateIndexRequest;
import org.opensearch.client.opensearch.indices.ExistsRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.alfresco.elasticsearch.batchindexing.model.WatermarkDocument;

/**
 * Persists the reindexing watermark as a typed {@link WatermarkDocument} in a dedicated Elasticsearch index.
 * <p>
 * The document contains both the trusted anchor ({@code lastSuccessfulToTimeEpochMs}) and audit fields for the most recent run (status, counts, time range) so operators can diagnose degraded runs directly from Elasticsearch without grepping logs.
 */
public class ElasticsearchWatermarkStore
{
    private static final Logger LOGGER = LoggerFactory.getLogger(ElasticsearchWatermarkStore.class);

    static final String WATERMARK_DOC_ID = "reindexByDate-watermark";

    /**
     * Sentinel value indicating no version info has been captured yet (first-ever run or document not found). When both are {@code -1} the write is unconditional.
     */
    private static final long VERSION_UNSET = -1L;
    private static final long MAX_BACKOFF_MS = 60_000L;

    private final OpenSearchClient openSearchClient;
    private final String indexName;
    private final int maxRetries;
    private final long retryDelayMs;

    /**
     * Elasticsearch sequence number captured by the last successful {@link #read()}. Used for optimistic concurrency control on subsequent {@link #write(WatermarkDocument)} calls.
     * <p>
     * Only accessed from the single {@code continuous-reindex} thread — no synchronization required.
     */
    private long lastSeqNo = VERSION_UNSET;

    /**
     * Elasticsearch primary term captured by the last successful {@link #read()}.
     *
     * @see #lastSeqNo
     */
    private long lastPrimaryTerm = VERSION_UNSET;

    public ElasticsearchWatermarkStore(OpenSearchClient openSearchClient, String indexName,
            int maxRetries, long retryDelayMs)
    {
        this.openSearchClient = openSearchClient;
        this.indexName = indexName;
        this.maxRetries = maxRetries;
        this.retryDelayMs = retryDelayMs;
    }

    /**
     * Best-effort check whether the state index currently exists in the cluster. Used by the startup bootstrap when {@code autoCreateStateIndex=false} to refuse-to-start rather than silently creating a fresh index. Any exception is bubbled up so the caller can distinguish "cluster unreachable" from "index missing".
     */
    public boolean indexExists() throws WatermarkAccessException
    {
        try
        {
            return openSearchClient.indices()
                    .exists(new ExistsRequest.Builder().index(indexName).build())
                    .value();
        }
        catch (Exception e)
        {
            throw new WatermarkAccessException("Failed to probe watermark index '" + indexName
                    + "' for existence: " + e.getMessage(), e);
        }
    }

    /**
     * Ensures the watermark index exists. Called once on startup. Retries with exponential backoff if Elasticsearch is unreachable.
     *
     * @throws WatermarkAccessException
     *             if the index cannot be created or verified after all retry attempts
     */
    public void ensureIndexExists() throws WatermarkAccessException
    {
        Exception lastException = null;
        for (int attempt = 1; attempt <= maxRetries; attempt++)
        {
            try
            {
                boolean exists = openSearchClient.indices()
                        .exists(new ExistsRequest.Builder().index(indexName).build())
                        .value();

                if (exists)
                {
                    LOGGER.debug("Watermark index '{}' already exists", indexName);
                    return;
                }

                openSearchClient.indices().create(new CreateIndexRequest.Builder()
                        .index(indexName)
                        .settings(s -> s.hidden(true))
                        .build());
                LOGGER.info("Created watermark index '{}'", indexName);
                return;
            }
            catch (OpenSearchException e)
            {
                lastException = e;
                LOGGER.warn("Failed to create watermark index '{}' (attempt {}/{}): {}",
                        indexName, attempt, maxRetries, e.getMessage());
            }
            catch (Exception e)
            {
                lastException = e;
                LOGGER.warn("Failed to reach Elasticsearch for watermark index creation (attempt {}/{}): {}",
                        attempt, maxRetries, e.getMessage());
            }

            if (attempt < maxRetries)
            {
                sleepWithBackoff(attempt);
            }
        }

        throw new WatermarkAccessException("Cannot create or verify watermark index '" + indexName
                + "' after " + maxRetries + " attempts. Is Elasticsearch running?", lastException);
    }

    /**
     * Reads the full watermark document from Elasticsearch.
     * <p>
     * On success the response's {@code _seq_no} and {@code _primary_term} are captured.
     *
     * @return the watermark document, or empty if no watermark has been written yet
     * @throws WatermarkAccessException
     *             if Elasticsearch is unreachable
     */
    @SuppressWarnings("PMD.UnusedAssignment")
    public Optional<WatermarkDocument> read() throws WatermarkAccessException
    {
        try
        {
            GetResponse<WatermarkDocument> response = openSearchClient.get(
                    new GetRequest.Builder()
                            .index(indexName)
                            .id(WATERMARK_DOC_ID)
                            .build(),
                    WatermarkDocument.class);

            if (!response.found() || response.source() == null)
            {
                LOGGER.debug("No watermark document found in index '{}'", indexName);
                lastSeqNo = VERSION_UNSET;
                lastPrimaryTerm = VERSION_UNSET;
                return Optional.empty();
            }

            lastSeqNo = response.seqNo() != null ? response.seqNo() : VERSION_UNSET;
            lastPrimaryTerm = response.primaryTerm() != null ? response.primaryTerm() : VERSION_UNSET;

            WatermarkDocument doc = response.source();
            LOGGER.debug("Read watermark from Elasticsearch (seqNo={}, primaryTerm={}): {}",
                    lastSeqNo, lastPrimaryTerm, doc);
            return Optional.of(doc);
        }
        catch (OpenSearchException e)
        {
            if (e.status() == 404)
            {
                LOGGER.debug("Watermark index '{}' or document not found (404)", indexName);
                lastSeqNo = VERSION_UNSET;
                lastPrimaryTerm = VERSION_UNSET;
                return Optional.empty();
            }
            throw new WatermarkAccessException("Failed to read watermark from Elasticsearch", e);
        }
        catch (Exception e)
        {
            throw new WatermarkAccessException("Failed to read watermark from Elasticsearch", e);
        }
    }

    /**
     * Writes the watermark document after a reindexing cycle.
     * <p>
     * Retries up to {@code maxRetries} times.
     * <p>
     * If all retries fail, logs an error but does NOT throw — the next cycle will simply re-scan the same window (safe, because ES writes are idempotent upserts).
     *
     * @param document
     *            the fully populated watermark document to persist
     * @throws WatermarkConflictException
     *             if another instance has concurrently updated the watermark (HTTP 409)
     */
    public void write(WatermarkDocument document) throws WatermarkConflictException
    {
        document.setUpdatedAt(Instant.now().toString());

        for (int attempt = 1; attempt <= maxRetries; attempt++)
        {
            try
            {
                IndexRequest.Builder<WatermarkDocument> builder = new IndexRequest.Builder<WatermarkDocument>()
                        .index(indexName)
                        .id(WATERMARK_DOC_ID)
                        .document(document);

                if (lastSeqNo >= 0 && lastPrimaryTerm >= 0)
                {
                    builder.ifSeqNo(lastSeqNo).ifPrimaryTerm(lastPrimaryTerm);
                    LOGGER.debug("Writing watermark with OCC guard: ifSeqNo={}, ifPrimaryTerm={}",
                            lastSeqNo, lastPrimaryTerm);
                }

                IndexResponse response = openSearchClient.index(builder.build());
                lastSeqNo = response.seqNo();
                lastPrimaryTerm = response.primaryTerm();
                LOGGER.debug("Watermark written to Elasticsearch (seqNo={}, primaryTerm={}): {}",
                        lastSeqNo, lastPrimaryTerm, document);
                return;
            }
            catch (OpenSearchException e)
            {
                if (handleWriteFailure(e, attempt))
                {
                    continue;
                }
                if (attempt < maxRetries)
                {
                    sleepWithBackoff(attempt);
                }
            }
            catch (Exception e)
            {
                LOGGER.warn("Failed to write watermark (attempt {}/{}): {}",
                        attempt, maxRetries, e.getMessage());
                if (attempt < maxRetries)
                {
                    sleepWithBackoff(attempt);
                }
            }
        }

        LOGGER.error("Failed to persist watermark after {} retries. "
                + "Next cycle will re-scan the same window (safe due to idempotent writes). Document: {}",
                maxRetries, document);
    }

    private boolean occGuardActive()
    {
        return lastSeqNo >= 0 && lastPrimaryTerm >= 0;
    }

    /**
     * Classify an {@link OpenSearchException} thrown by the write path. Returns {@code true} when the caller should retry immediately (a recovery ran), {@code false} to fall through to the caller's backoff. Throws {@link WatermarkConflictException} on a genuine OCC conflict.
     */
    private boolean handleWriteFailure(OpenSearchException e, int attempt) throws WatermarkConflictException
    {
        if (e.status() == 409)
        {
            if (occGuardActive() && stateDocMissing())
            {
                recoverFromMissingStateDoc();
                return true;
            }
            throw new WatermarkConflictException(
                    "Watermark version conflict (HTTP 409): another reindexing instance has updated the "
                            + "watermark since our last read (ifSeqNo=" + lastSeqNo
                            + ", ifPrimaryTerm=" + lastPrimaryTerm + "). "
                            + "This instance must terminate to avoid data corruption.",
                    e);
        }
        if (e.status() == 404 && occGuardActive())
        {
            try
            {
                recoverFromMissingStateIndex();
                return true;
            }
            catch (WatermarkAccessException recreate)
            {
                LOGGER.warn("Failed to recreate state index after 404 on watermark write "
                        + "(attempt {}/{}): {}", attempt, maxRetries, recreate.getMessage());
            }
        }
        LOGGER.warn("Failed to write watermark (attempt {}/{}): {}",
                attempt, maxRetries, e.getMessage());
        return false;
    }

    /**
     * Best-effort check whether the watermark doc still exists. Used to disambiguate the two flavours of HTTP 409 on write:
     * <ul>
     * <li><em>doc missing</em> — the state doc (and possibly the whole index) was deleted since our last {@link #read()}. Recoverable by clearing the OCC guard and retrying unconditionally.</li>
     * <li><em>seq_no conflict</em> — another instance has updated the doc. Not recoverable — the other instance's cursor may be ahead.</li>
     * </ul>
     * On any read failure other than 404, conservatively returns {@code false} (treat as a real conflict and let the caller throw).
     */
    private boolean stateDocMissing()
    {
        try
        {
            GetResponse<WatermarkDocument> response = openSearchClient.get(
                    new GetRequest.Builder().index(indexName).id(WATERMARK_DOC_ID).build(),
                    WatermarkDocument.class);
            return !response.found();
        }
        catch (OpenSearchException e)
        {
            return e.status() == 404;
        }
        catch (Exception e)
        {
            if (LOGGER.isDebugEnabled())
            {
                LOGGER.debug("Could not probe watermark doc after 409 (treating as real conflict): {}",
                        e.getMessage());
            }
            return false;
        }
    }

    /**
     * The watermark doc (or the whole state index) was deleted between our last {@link #read()} and this {@link #write(WatermarkDocument)}. Clear the OCC guard so the next iteration writes unconditionally, preserving the in-memory cursor.
     */
    private void recoverFromMissingStateDoc()
    {
        LOGGER.info("Watermark state doc missing during OCC-guarded write — the state index or doc was deleted "
                + "since our last read. Clearing OCC guard and retrying unconditionally; the in-memory cursor "
                + "will be preserved.");
        lastSeqNo = VERSION_UNSET;
        lastPrimaryTerm = VERSION_UNSET;
    }

    /**
     * When the ES cluster has {@code action.auto_create_index} disabled our write hits 404 (index gone) rather than 409 (doc missing but index auto-recreated). Explicitly ensure the index exists, then clear the OCC guard so the next attempt writes unconditionally.
     */
    private void recoverFromMissingStateIndex() throws WatermarkAccessException
    {
        LOGGER.info("Watermark state index missing during OCC-guarded write — recreating it and retrying "
                + "unconditionally; the in-memory cursor will be preserved.");
        ensureIndexExists();
        lastSeqNo = VERSION_UNSET;
        lastPrimaryTerm = VERSION_UNSET;
    }

    /**
     * Exponential backoff: {@code retryDelayMs * 2^(attempt-1)}, capped at {@value MAX_BACKOFF_MS}ms.
     */
    private void sleepWithBackoff(int attempt)
    {
        long delay;
        try
        {
            delay = Math.min(Math.multiplyExact(retryDelayMs, 1L << Math.min(attempt - 1, 30)), MAX_BACKOFF_MS);
        }
        catch (ArithmeticException overflow)
        {
            delay = MAX_BACKOFF_MS;
        }

        try
        {
            Thread.sleep(delay);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
    }
}
