/*-
 * #%L
 * Alfresco Search Community :: Reindexing Core
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
package org.alfresco.reindexing.writers;

import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.REINDEXING_START_TIME;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.item.ItemStreamException;
import org.springframework.batch.infrastructure.item.ItemStreamWriter;

import org.alfresco.indexing.shared.model.BulkItemFailure;
import org.alfresco.indexing.shared.model.BulkRequestDto;
import org.alfresco.metrics.MetricsConstants;
import org.alfresco.reindexing.config.ReindexingCoreProperties;
import org.alfresco.reindexing.model.ReindexingEvent;
import org.alfresco.reindexing.opensearch.ElasticSearchUpdateOperations;
import org.alfresco.repo.event.v1.model.EventData;
import org.alfresco.repo.event.v1.model.NodeResource;
import org.alfresco.repo.event.v1.model.RepoEvent;

/**
 * Writer that folds the metadata and path writes into a single Elasticsearch bulk per chunk (see {@code alfresco.reindex.writersMergedEnabled}). It replaces both {@link ElasticsearchRepoEventItemWriter} and {@link ElasticsearchPathWriter}: each node produces one scripted upsert carrying both field sets, halving the ES round-trips for the two dominant writers and removing the create-order dependency (no {@code document_missing_exception} or metadata/path version conflict is possible) by construction. Content transform dispatch stays a separate writer.
 * <p>
 * Node-level counting mirrors {@link ElasticsearchRepoEventItemWriter}: successes/failures are attributed per source node in the chunk rather than per raw bulk operation.
 */
@SuppressWarnings({"rawtypes"})
public class ElasticsearchMergedReindexWriter implements ItemStreamWriter<ReindexingEvent>
{
    private static final Logger LOGGER = LoggerFactory.getLogger(ElasticsearchMergedReindexWriter.class);
    private static final long DEFAULT_REINDEXING_START_TIME = Long.MAX_VALUE;

    private final ElasticSearchUpdateOperations elasticSearchUpdateOperations;
    private final ReindexingCoreProperties reindexingCoreProperties;
    /** Optional. Nullable in tests; wired in production via SharedBatchProcessingConfig. */
    private final ChunkWriteMetricsAccumulator writeMetricsAccumulator;
    /** Optional. When null the writer only logs a WARN on per-item failure and moves on. */
    private final MergedBulkItemFailureListener failureListener;

    private final AtomicLong totalIndexedCounter = new AtomicLong();
    private final AtomicLong totalFailedCounter = new AtomicLong();
    // Baseline snapshots taken in open() so close() can report only this step run's delta. We snapshot rather
    // than reset the cumulative counters because in local partitioning a single writer instance is shared by
    // several partition worker steps running concurrently; a destructive reset in one partition's open() would
    // wipe another partition's in-flight counts. addAndGet on the cumulative counters stays atomic, so no count
    // is ever lost; the close() delta is exact for single-step JVMs (standalone/manager/worker) and a safe
    // aggregate under concurrent local partitions.
    private final AtomicLong indexedAtOpen = new AtomicLong();
    private final AtomicLong failedAtOpen = new AtomicLong();
    private volatile ExecutionContext executionContext;

    public ElasticsearchMergedReindexWriter(ElasticSearchUpdateOperations elasticSearchUpdateOperations,
            ReindexingCoreProperties reindexingCoreProperties)
    {
        this(elasticSearchUpdateOperations, reindexingCoreProperties, null, null);
    }

    public ElasticsearchMergedReindexWriter(ElasticSearchUpdateOperations elasticSearchUpdateOperations,
            ReindexingCoreProperties reindexingCoreProperties,
            ChunkWriteMetricsAccumulator writeMetricsAccumulator,
            MergedBulkItemFailureListener failureListener)
    {
        this.elasticSearchUpdateOperations = elasticSearchUpdateOperations;
        this.reindexingCoreProperties = reindexingCoreProperties;
        this.writeMetricsAccumulator = writeMetricsAccumulator;
        this.failureListener = failureListener;
    }

    @Override
    public void write(Chunk<? extends ReindexingEvent> chunk) throws Exception
    {
        List<ReindexingEvent> items = new ArrayList<>(chunk.getItems());
        long reindexingStartTime = executionContext.getLong(REINDEXING_START_TIME, DEFAULT_REINDEXING_START_TIME);

        BulkRequestDto bulkRequestDto = elasticSearchUpdateOperations.getMergedBulkRequest(items, reindexingStartTime,
                reindexingCoreProperties.getWriterRetryCount(),
                reindexingCoreProperties.getWriterRetryDelay());

        long failuresCount = bulkRequestDto.getFailuresCount();
        if (bulkRequestDto.getNumberOfActions() > 0)
        {
            long failedNodes = countFailedNodes(bulkRequestDto.getFailures(), chunk.size());
            long successCount = chunk.size() - failedNodes;
            totalIndexedCounter.addAndGet(successCount);
            totalFailedCounter.addAndGet(failedNodes);
            if (writeMetricsAccumulator != null)
            {
                writeMetricsAccumulator.recordCount(MetricsConstants.MERGED, successCount, failedNodes);
            }

            if (failuresCount > 0)
            {
                LOGGER.warn("Merged write: {} items failed in ES bulk request after internal retries. "
                        + "These nodes are recorded per-item on the returned DTO for observability sinks (e.g. DLQ).",
                        failuresCount);
                dispatchFailures(items, bulkRequestDto.getFailures());
                failIfUnhandled(failuresCount, "merged");
            }
        }
    }

    /**
     * Attribute bulk-item failures back to source nodes. A single node can emit more than one bulk op in the merged bulk (the combined metadata+path upsert plus an optional content upsert), and those ops share the node's document id, so counting raw failed operations would double-count a node. Count distinct failed document ids instead, capped at the chunk size defensively.
     */
    private static long countFailedNodes(List<BulkItemFailure> failures, int chunkSize)
    {
        long distinctFailedNodes = failures.stream()
                .map(BulkItemFailure::documentId)
                .filter(Objects::nonNull)
                .distinct()
                .count();
        return Math.min(distinctFailedNodes, chunkSize);
    }

    /**
     * Fail the chunk when per-item bulk failures remain and no dead-letter listener is configured, so the reindex ends FAILED rather than silently dropping the nodes. Gated by {@code alfresco.reindex.failOnTerminalWriteErrors}; a no-op when a listener is present or the flag is off.
     */
    private void failIfUnhandled(long failuresCount, String stage)
    {
        if (failuresCount > 0 && failureListener == null
                && reindexingCoreProperties.isFailOnTerminalWriteErrors())
        {
            throw new TerminalWriteFailureException(stage, failuresCount);
        }
    }

    /**
     * Correlate per-item bulk failures back to the source {@link ReindexingEvent} in the chunk and hand each one off to the optional listener. A single node can produce more than one bulk op (the merged metadata+path upsert plus a content upsert), so we dedupe by nodeRef.
     */
    private void dispatchFailures(List<ReindexingEvent> items, List<BulkItemFailure> failures)
    {
        if (failureListener == null || failures.isEmpty())
        {
            return;
        }
        Map<String, ReindexingEvent> byNodeRef = new HashMap<>();
        for (ReindexingEvent item : items)
        {
            String nodeRef = extractNodeRef(item);
            if (nodeRef != null)
            {
                byNodeRef.putIfAbsent(nodeRef, item);
            }
        }
        Set<String> alreadyDispatched = new HashSet<>();
        for (BulkItemFailure failure : failures)
        {
            String nodeRef = failure.documentId();
            if (nodeRef == null || !alreadyDispatched.add(nodeRef))
            {
                continue;
            }
            ReindexingEvent event = byNodeRef.get(nodeRef);
            Long dbId = event == null ? null : event.getNodeDbId();
            Long commitTimeMs = extractCommitTimeMs(event);
            try
            {
                failureListener.onMergedBulkItemFailure(dbId, nodeRef, commitTimeMs, failure.errorType(), failure.errorReason());
            }
            catch (RuntimeException ex)
            {
                // Isolate the listener — a broken DLQ (or metrics sink) must not fail the writer.
                LOGGER.warn("MergedBulkItemFailureListener threw for nodeRef={}: {}", nodeRef, ex.getMessage(), ex);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static String extractNodeRef(ReindexingEvent item)
    {
        RepoEvent<EventData<NodeResource>> repoEvent = item.getRepoEvent();
        if (repoEvent == null || repoEvent.getData() == null)
        {
            return null;
        }
        NodeResource resource = repoEvent.getData().getResource();
        return resource == null ? null : resource.getId();
    }

    private static Long extractCommitTimeMs(ReindexingEvent item)
    {
        if (item == null || item.getTime() == null)
        {
            return null;
        }
        return item.getTime().toInstant().toEpochMilli();
    }

    @Override
    public void open(ExecutionContext executionContext) throws ItemStreamException
    {
        this.executionContext = executionContext;
        // Snapshot the cumulative counters at the start of this run (non-destructive; see field comment) so the
        // totals logged in close() cover only this run rather than the writer instance's whole lifetime.
        indexedAtOpen.set(totalIndexedCounter.get());
        failedAtOpen.set(totalFailedCounter.get());
    }

    @Override
    public void update(ExecutionContext executionContext) throws ItemStreamException
    {
        // No intermediate state to persist between chunks
    }

    @Override
    public void close() throws ItemStreamException
    {
        LOGGER.info("Total number of indexed nodes via merged writer (includes retried items) {} ", totalIndexedCounter.get() - indexedAtOpen.get());
        LOGGER.info("Total number of failed nodes via merged writer (includes retried items) {} ", totalFailedCounter.get() - failedAtOpen.get());
    }
}
