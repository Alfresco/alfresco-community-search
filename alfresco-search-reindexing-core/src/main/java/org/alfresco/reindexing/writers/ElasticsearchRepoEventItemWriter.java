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
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

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

@SuppressWarnings({"rawtypes"})
public class ElasticsearchRepoEventItemWriter implements ItemStreamWriter<ReindexingEvent>
{
    private static final Logger LOGGER = LoggerFactory.getLogger(ElasticsearchRepoEventItemWriter.class);
    private static final long DEFAULT_REINDEXING_START_TIME = Long.MAX_VALUE;

    private final ElasticSearchUpdateOperations elasticSearchUpdateOperations;
    private final ReindexingCoreProperties elasticsearchReindexProperties;
    /** Optional. Nullable in tests; wired in production via SharedBatchProcessingConfig. */
    private final ChunkWriteMetricsAccumulator writeMetricsAccumulator;
    /** Optional. When null the writer only logs a WARN on per-item failure and moves on. */
    private final BulkItemFailureListener failureListener;

    private final AtomicLong totalIndexedCounter = new AtomicLong();
    private final AtomicLong totalFailedCounter = new AtomicLong();
    private ExecutionContext executionContext;

    public ElasticsearchRepoEventItemWriter(ElasticSearchUpdateOperations elasticSearchUpdateOperations,
            ReindexingCoreProperties elasticsearchReindexProperties)
    {
        this(elasticSearchUpdateOperations, elasticsearchReindexProperties, null, null);
    }

    public ElasticsearchRepoEventItemWriter(ElasticSearchUpdateOperations elasticSearchUpdateOperations,
            ReindexingCoreProperties elasticsearchReindexProperties,
            BulkItemFailureListener failureListener)
    {
        this(elasticSearchUpdateOperations, elasticsearchReindexProperties, null, failureListener);
    }

    public ElasticsearchRepoEventItemWriter(ElasticSearchUpdateOperations elasticSearchUpdateOperations,
            ReindexingCoreProperties elasticsearchReindexProperties,
            ChunkWriteMetricsAccumulator writeMetricsAccumulator,
            BulkItemFailureListener failureListener)
    {
        this.elasticSearchUpdateOperations = elasticSearchUpdateOperations;
        this.elasticsearchReindexProperties = elasticsearchReindexProperties;
        this.writeMetricsAccumulator = writeMetricsAccumulator;
        this.failureListener = failureListener;
    }

    @Override
    public void write(Chunk<? extends ReindexingEvent> chunk) throws Exception
    {
        List<? extends ReindexingEvent> items = new ArrayList<>(chunk.getItems());
        List<RepoEvent> repoEvents = items.stream()
                .map(ReindexingEvent::getRepoEvent)
                .collect(Collectors.toList());

        long reindexingStartTime = executionContext.getLong(REINDEXING_START_TIME, DEFAULT_REINDEXING_START_TIME);

        BulkRequestDto bulkRequestDto = elasticSearchUpdateOperations.getBulkRequest(repoEvents, reindexingStartTime,
                elasticsearchReindexProperties.getWriterRetryCount(),
                elasticsearchReindexProperties.getWriterRetryDelay());
        long failuresCount = bulkRequestDto.getFailuresCount();
        if (bulkRequestDto.getNumberOfActions() > 0)
        {
            long failedNodes = Math.min(failuresCount, chunk.size());
            long successCount = chunk.size() - failedNodes;
            totalIndexedCounter.addAndGet(successCount);
            totalFailedCounter.addAndGet(failedNodes);
            if (writeMetricsAccumulator != null)
            {
                writeMetricsAccumulator.recordCount(MetricsConstants.METADATA, successCount, failedNodes);
            }

            if (failuresCount > 0)
            {
                LOGGER.warn("Metadata write: {} items failed in ES bulk request after internal retries. "
                        + "These nodes are recorded per-item on the returned DTO for observability sinks (e.g. DLQ).",
                        failuresCount);
                dispatchFailures(items, bulkRequestDto.getFailures());
            }
        }
    }

    /**
     * Correlate per-item bulk failures back to the source {@link ReindexingEvent} in the chunk and hand each one off to the optional listener. A single node can produce more than one bulk op (metadata + content upsert), so we dedupe by nodeRef.
     */
    private void dispatchFailures(List<? extends ReindexingEvent> items, List<BulkItemFailure> failures)
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
                failureListener.onBulkItemFailure(dbId, nodeRef, commitTimeMs, failure.errorType(), failure.errorReason());
            }
            catch (RuntimeException ex)
            {
                // Isolate the listener — a broken DLQ (or metrics sink) must not fail the writer.
                LOGGER.warn("BulkItemFailureListener threw for nodeRef={}: {}", nodeRef, ex.getMessage(), ex);
            }
        }
    }

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
    }

    @Override
    public void update(ExecutionContext executionContext) throws ItemStreamException
    {
        // No intermediate state to persist between chunks
    }

    @Override
    public void close() throws ItemStreamException
    {
        LOGGER.info("Total number of indexed nodes (includes retried items) {} ", totalIndexedCounter.get());
        LOGGER.info("Total number of failed nodes (includes retried items) {} ", totalFailedCounter.get());
    }
}
