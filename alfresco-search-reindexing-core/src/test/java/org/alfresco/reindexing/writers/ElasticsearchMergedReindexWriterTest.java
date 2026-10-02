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

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ExecutionContext;

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
 * Unit tests for {@link ElasticsearchMergedReindexWriter}: node-level success/failure counting, metric recording under the {@code merged} module tag, and per-item failure dispatch to the optional listener.
 */
class ElasticsearchMergedReindexWriterTest
{
    private static final String NODE_REF_A = "workspace://SpacesStore/aaaaaaaa";
    private static final String NODE_REF_B = "workspace://SpacesStore/bbbbbbbb";
    private static final ZonedDateTime EVENT_TIME_A = ZonedDateTime.of(2026, 6, 1, 12, 0, 0, 0, ZoneOffset.UTC);
    private static final ZonedDateTime EVENT_TIME_B = ZonedDateTime.of(2026, 6, 2, 12, 0, 0, 0, ZoneOffset.UTC);

    private ReindexingCoreProperties reindexProps;
    private ElasticSearchUpdateOperations operations;
    private ChunkWriteMetricsAccumulator accumulator;
    private MergedBulkItemFailureListener listener;
    private ExecutionContext executionContext;

    @BeforeEach
    void setUp()
    {
        reindexProps = mock(ReindexingCoreProperties.class);
        when(reindexProps.getWriterRetryCount()).thenReturn(3);
        when(reindexProps.getWriterRetryDelay()).thenReturn(10L);
        operations = mock(ElasticSearchUpdateOperations.class);
        accumulator = mock(ChunkWriteMetricsAccumulator.class);
        listener = mock(MergedBulkItemFailureListener.class);
        executionContext = new ExecutionContext();
    }

    @Test
    void recordsSuccessCountUnderMergedModuleWhenNoFailures() throws Exception
    {
        stubMergedResponse(2, 0, List.of());
        ElasticsearchMergedReindexWriter writer = newWriter(listener);
        writer.open(executionContext);

        writer.write(Chunk.of(event(1L, NODE_REF_A, EVENT_TIME_A), event(2L, NODE_REF_B, EVENT_TIME_B)));

        verify(accumulator).recordCount(eq(MetricsConstants.MERGED), eq(2L), eq(0L));
        verify(listener, never()).onMergedBulkItemFailure(any(), any(), any(), any(), any());
    }

    @Test
    void attributesFailuresPerNodeAndDispatches() throws Exception
    {
        stubMergedResponse(2, 1, List.of(new BulkItemFailure(NODE_REF_A, "mapper_parsing_exception", "bad")));
        ElasticsearchMergedReindexWriter writer = newWriter(listener);
        writer.open(executionContext);

        writer.write(Chunk.of(event(1L, NODE_REF_A, EVENT_TIME_A), event(2L, NODE_REF_B, EVENT_TIME_B)));

        // 2 nodes in the chunk, 1 failed -> 1 success, 1 failure recorded under the merged module.
        verify(accumulator).recordCount(eq(MetricsConstants.MERGED), eq(1L), eq(1L));
        verify(listener).onMergedBulkItemFailure(eq(1L), eq(NODE_REF_A),
                eq(EVENT_TIME_A.toInstant().toEpochMilli()), eq("mapper_parsing_exception"), eq("bad"));
        verify(listener, times(1)).onMergedBulkItemFailure(any(), any(), any(), any(), any());
    }

    @Test
    void listenerAbsentAndFailOnTerminalDisabledStillWritesAndLogs() throws Exception
    {
        stubMergedResponse(1, 1, List.of(new BulkItemFailure(NODE_REF_A, "x", "y")));
        when(reindexProps.isFailOnTerminalWriteErrors()).thenReturn(false);
        ElasticsearchMergedReindexWriter writer = newWriter(null);
        writer.open(executionContext);

        assertThatCode(() -> writer.write(Chunk.of(event(1L, NODE_REF_A, EVENT_TIME_A))))
                .doesNotThrowAnyException();
    }

    @Test
    void listenerAbsentAndFailOnTerminalEnabledFailsChunk() throws Exception
    {
        stubMergedResponse(1, 1, List.of(new BulkItemFailure(NODE_REF_A, "x", "y")));
        when(reindexProps.isFailOnTerminalWriteErrors()).thenReturn(true);
        ElasticsearchMergedReindexWriter writer = newWriter(null);
        writer.open(executionContext);

        assertThatThrownBy(() -> writer.write(Chunk.of(event(1L, NODE_REF_A, EVENT_TIME_A))))
                .isInstanceOf(TerminalWriteFailureException.class);
    }

    @Test
    void skipsCountingWhenNoActions() throws Exception
    {
        stubMergedResponse(0, 0, List.of());
        ElasticsearchMergedReindexWriter writer = newWriter(listener);
        writer.open(executionContext);

        writer.write(Chunk.of(event(1L, NODE_REF_A, EVENT_TIME_A)));

        verify(accumulator, never()).recordCount(any(), anyLong(), anyLong());
    }

    @Test
    void countsDistinctFailedNodesWhenOneNodeEmitsMultipleFailedOps() throws Exception
    {
        // A single node emits two bulk ops in the merged bulk (the combined metadata+path upsert plus a
        // content upsert) that share the node's document id; both fail. That is ONE failed node, not two,
        // so a two-node chunk must still record one success and dispatch the listener only once.
        stubMergedResponse(2, 2, List.of(
                new BulkItemFailure(NODE_REF_A, "mapper_parsing_exception", "bad"),
                new BulkItemFailure(NODE_REF_A, "mapper_parsing_exception", "bad")));
        ElasticsearchMergedReindexWriter writer = newWriter(listener);
        writer.open(executionContext);

        writer.write(Chunk.of(event(1L, NODE_REF_A, EVENT_TIME_A), event(2L, NODE_REF_B, EVENT_TIME_B)));

        verify(accumulator).recordCount(eq(MetricsConstants.MERGED), eq(1L), eq(1L));
        verify(listener, times(1)).onMergedBulkItemFailure(any(), any(), any(), any(), any());
    }

    private ElasticsearchMergedReindexWriter newWriter(MergedBulkItemFailureListener listener)
    {
        return new ElasticsearchMergedReindexWriter(operations, reindexProps, accumulator, listener);
    }

    private void stubMergedResponse(int numberOfActions, long failuresCount, List<BulkItemFailure> failures)
            throws IOException, InterruptedException
    {
        BulkRequestDto dto = new BulkRequestDto();
        dto.setNumberOfActions(numberOfActions);
        dto.setFailuresCount(failuresCount);
        failures.forEach(dto::addFailure);
        when(operations.getMergedBulkRequest(any(), anyLong(), anyInt(), anyLong())).thenReturn(dto);
    }

    private static ReindexingEvent event(long dbId, String nodeRef, ZonedDateTime time)
    {
        NodeResource resource = NodeResource.builder().setId(nodeRef).build();
        EventData<NodeResource> data = EventData.<NodeResource> builder().setResource(resource).build();
        RepoEvent<EventData<NodeResource>> repoEvent = RepoEvent.<EventData<NodeResource>> builder()
                .setType("org.alfresco.event.node.Updated")
                .setData(data)
                .setTime(time)
                .setId("evt-" + dbId)
                .build();
        return ReindexingEvent.builder()
                .withRepoEvent(repoEvent)
                .withNodeDbId(dbId)
                .build();
    }
}
