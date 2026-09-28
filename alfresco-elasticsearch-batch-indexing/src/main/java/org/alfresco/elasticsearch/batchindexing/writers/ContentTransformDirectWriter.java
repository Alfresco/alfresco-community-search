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
package org.alfresco.elasticsearch.batchindexing.writers;

import static org.alfresco.indexing.shared.utils.EventUtils.getEventTimestamp;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;
import jakarta.annotation.PreDestroy;

import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;

import org.alfresco.elasticsearch.batchindexing.domain.transform.ContentTransformClient;
import org.alfresco.elasticsearch.batchindexing.domain.transform.ContentTransformFailure;
import org.alfresco.elasticsearch.batchindexing.domain.transform.ContentTransformResult;
import org.alfresco.indexing.shared.opensearch.ElasticsearchContentModifyPort;
import org.alfresco.indexing.shared.opensearch.command.ContentIndexCmd;
import org.alfresco.metrics.MetricsConstants;
import org.alfresco.reindexing.domain.transform.ContentTransformSupportService;
import org.alfresco.reindexing.model.ReindexingEvent;
import org.alfresco.reindexing.writers.ChunkWriteMetricsAccumulator;
import org.alfresco.repo.event.v1.model.ContentInfo;
import org.alfresco.repo.event.v1.model.DataAttributes;
import org.alfresco.repo.event.v1.model.NodeResource;

@Slf4j
public class ContentTransformDirectWriter implements ItemWriter<ReindexingEvent>
{
    private final ContentTransformSupportService contentTransformSupportService;
    private final ContentTransformClient contentTransformClient;
    private final ElasticsearchContentModifyPort contentModifyPort;
    private final ChunkWriteMetricsAccumulator writeMetricsAccumulator;
    /**
     * Store reference prefix (e.g. {@code workspace://SpacesStore/}).
     */
    private final String storeRef;
    /**
     * Parallelism cap for per-chunk content fetches. {@code <= 1} means run serially on the calling Spring Batch thread (legacy behaviour). {@code > 1} owns a {@link ForkJoinPool} of that size and fans the per-node ACS calls out over it.
     */
    private final int maxConcurrency;
    private final ForkJoinPool transformPool;
    /**
     * Optional. When null the writer only logs a WARN on 4xx and moves on; consumers that want a per-item hook (e.g. batch-indexing's DLQ) register a bean of this type.
     */
    private final ContentTransformFailureListener failureListener;

    public ContentTransformDirectWriter(
            ContentTransformSupportService contentTransformSupportService,
            ContentTransformClient contentTransformClient,
            ElasticsearchContentModifyPort contentModifyPort,
            String storeRef)
    {
        this(contentTransformSupportService, contentTransformClient, contentModifyPort, storeRef, 1, null, null);
    }

    public ContentTransformDirectWriter(
            ContentTransformSupportService contentTransformSupportService,
            ContentTransformClient contentTransformClient,
            ElasticsearchContentModifyPort contentModifyPort,
            String storeRef,
            int maxConcurrency,
            ContentTransformFailureListener failureListener)
    {
        this(contentTransformSupportService, contentTransformClient, contentModifyPort, storeRef, maxConcurrency, null, failureListener);
    }

    public ContentTransformDirectWriter(
            ContentTransformSupportService contentTransformSupportService,
            ContentTransformClient contentTransformClient,
            ElasticsearchContentModifyPort contentModifyPort,
            String storeRef,
            int maxConcurrency,
            ChunkWriteMetricsAccumulator writeMetricsAccumulator,
            ContentTransformFailureListener failureListener)
    {
        if (storeRef == null || storeRef.isBlank())
        {
            throw new IllegalArgumentException("storeRef must not be null or blank");
        }
        this.contentTransformSupportService = contentTransformSupportService;
        this.contentTransformClient = contentTransformClient;
        this.contentModifyPort = contentModifyPort;
        this.writeMetricsAccumulator = writeMetricsAccumulator;
        this.storeRef = storeRef.endsWith("/") ? storeRef : storeRef + "/";
        this.maxConcurrency = Math.max(1, maxConcurrency);
        this.transformPool = this.maxConcurrency > 1 ? new ForkJoinPool(this.maxConcurrency) : null;
        this.failureListener = failureListener;
        if (this.maxConcurrency > 1)
        {
            log.info("ContentTransformDirectWriter parallel mode enabled: {} threads", this.maxConcurrency);
        }
    }

    @PreDestroy
    public void shutdown()
    {
        if (transformPool != null)
        {
            transformPool.shutdown();
        }
    }

    /**
     * Failures are intentionally caught and logged rather than propagated to Spring Batch. Content transformation is a best-effort secondary indexing step — a failed transform should not cause the entire chunk to be retried or the step to fail, because the metadata and path indexing (handled by other writers) can still succeed. Non-retryable failures (e.g. ACS 4xx) are additionally forwarded to the optional {@link ContentTransformFailureListener} so an operator sees them in the DLQ; the affected nodes are otherwise still eligible for retry on the next reindexing window overlap.
     */
    @Override
    public void write(Chunk<? extends ReindexingEvent> chunk) throws Exception
    {
        List<? extends ReindexingEvent> events = chunk.getItems().stream()
                .filter(contentTransformSupportService::isContentTransformSupported)
                .toList();

        if (events.isEmpty())
        {
            return;
        }

        if (log.isDebugEnabled())
        {
            log.debug("Processing {} events for direct content transformation", events.size());
        }

        AtomicInteger failureCount = new AtomicInteger();
        if (transformPool == null)
        {
            for (ReindexingEvent event : events)
            {
                if (!transformAndIndexContent(event))
                {
                    failureCount.incrementAndGet();
                }
            }
        }
        else
        {
            try
            {
                transformPool.submit(() -> events.parallelStream().forEach(event -> {
                    if (!transformAndIndexContent(event))
                    {
                        failureCount.incrementAndGet();
                    }
                })).get();
            }
            catch (ExecutionException e)
            {
                log.error("Parallel content transform task failed; counting whole chunk as failed", e.getCause());
                failureCount.set(events.size());
            }
        }

        int failures = failureCount.get();
        if (writeMetricsAccumulator != null)
        {
            writeMetricsAccumulator.recordCount(MetricsConstants.CONTENT, (long) events.size() - failures, failures);
        }
        if (failures > 0)
        {
            log.warn("Content transform: {} out of {} transformable items failed in this chunk. "
                    + "Content for these nodes will not be searchable until next re-scan.",
                    failures, events.size());
        }
    }

    /**
     * @return {@code true} if the transform+index succeeded, {@code false} on failure
     */
    private boolean transformAndIndexContent(ReindexingEvent event)
    {
        try
        {
            Long dbId = event.getNodeDbId();
            String resourceId = getResourceId(event);

            if (dbId == null)
            {
                log.warn("Skipping transformation for {}: node DB ID is not available", resourceId);
                return true;
            }

            String mimeType = getMimeType(event).orElse(null);

            if (mimeType == null)
            {
                log.warn("Skipping transformation for {}: MIME type is not available", resourceId);
                return true;
            }

            ContentTransformResult result = contentTransformClient.transformContent(dbId);

            if (result.text().isPresent())
            {
                String uuid = extractUuid(resourceId);
                long modifiedAt = getEventTimestamp(event);
                ContentIndexCmd contentIndexCmd = ContentIndexCmd.withTextScript(
                        uuid, modifiedAt, result.text().get());
                contentModifyPort.upsertContent(contentIndexCmd);

                log.debug("Successfully transformed and indexed content for {}", resourceId);
                return true;
            }

            if (result.failure().isPresent())
            {
                dispatchFailure(event, dbId, resourceId, result.failure().get());
                return false;
            }

            // No text, no failure — treated as "empty body, nothing to index". Not a DLQ event.
            log.debug("Empty transform result for {}: no content indexed for this cycle", resourceId);
            return true;
        }
        catch (Exception e)
        {
            log.error("Failed to transform and index content for event: {}", event, e);
            return false;
        }
    }

    private void dispatchFailure(ReindexingEvent event, Long dbId, String resourceId, ContentTransformFailure failure)
    {
        log.warn("Transformation failed for {}: {} ({})", resourceId, failure.failureType(), failure.failureReason());
        if (failureListener == null)
        {
            return;
        }
        try
        {
            failureListener.onContentTransformFailure(
                    dbId,
                    resourceId,
                    extractCommitTimeMs(event),
                    failure.failureType(),
                    failure.failureReason());
        }
        catch (RuntimeException ex)
        {
            // Isolate the listener — a broken DLQ (or metrics sink) must not fail the writer.
            log.warn("ContentTransformFailureListener threw for nodeRef={}: {}", resourceId, ex.getMessage(), ex);
        }
    }

    private String getResourceId(ReindexingEvent event)
    {
        return storeRef + event.getData().getResource().getId();
    }

    private Optional<String> getMimeType(ReindexingEvent event)
    {
        return Optional.ofNullable(event.getData())
                .map(DataAttributes::getResource)
                .map(NodeResource::getContent)
                .map(ContentInfo::getMimeType);
    }

    private String extractUuid(String nodeRef)
    {
        int lastSlash = nodeRef.lastIndexOf('/');
        if (lastSlash == -1)
        {
            return nodeRef;
        }
        return nodeRef.substring(lastSlash + 1);
    }

    private static Long extractCommitTimeMs(ReindexingEvent event)
    {
        if (event == null || event.getTime() == null)
        {
            return null;
        }
        return event.getTime().toInstant().toEpochMilli();
    }
}
