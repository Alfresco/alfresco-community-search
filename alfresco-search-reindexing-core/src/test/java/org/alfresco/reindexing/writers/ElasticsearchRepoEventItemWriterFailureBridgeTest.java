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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.REINDEXING_START_TIME;

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
import org.alfresco.reindexing.config.ReindexingCoreProperties;
import org.alfresco.reindexing.model.ReindexingEvent;
import org.alfresco.reindexing.opensearch.ElasticSearchUpdateOperations;
import org.alfresco.repo.event.v1.model.EventData;
import org.alfresco.repo.event.v1.model.NodeResource;
import org.alfresco.repo.event.v1.model.RepoEvent;

/**
 * Focused unit tests for the write-side failure bridge. Only the correlation + dispatch logic is exercised — the real ES write path lives in {@link ElasticsearchRepoEventItemWriterIT}.
 */
class ElasticsearchRepoEventItemWriterFailureBridgeTest
{
    private static final String NODE_REF_A = "workspace://SpacesStore/aaaaaaaa";
    private static final String NODE_REF_B = "workspace://SpacesStore/bbbbbbbb";
    private static final ZonedDateTime EVENT_TIME_A = ZonedDateTime.of(2026, 6, 1, 12, 0, 0, 0, ZoneOffset.UTC);
    private static final ZonedDateTime EVENT_TIME_B = ZonedDateTime.of(2026, 6, 2, 12, 0, 0, 0, ZoneOffset.UTC);

    private ElasticSearchUpdateOperations operations;
    private BulkItemFailureListener listener;
    private ExecutionContext executionContext;
    private ReindexingCoreProperties reindexProperties;

    @BeforeEach
    void setUp()
    {
        operations = mock(ElasticSearchUpdateOperations.class);
        listener = mock(BulkItemFailureListener.class);
        executionContext = new ExecutionContext();
        executionContext.putLong(REINDEXING_START_TIME, System.currentTimeMillis());
        reindexProperties = new ReindexingCoreProperties();
        reindexProperties.setWriterRetryCount(3);
        reindexProperties.setWriterRetryDelay(1L);
    }

    @Test
    void doesNotDispatchWhenNoFailures() throws Exception
    {
        stubBulkResponse(1, 0, List.of());
        ElasticsearchRepoEventItemWriter writer = new ElasticsearchRepoEventItemWriter(operations, reindexProperties, listener);
        writer.open(executionContext);

        writer.write(Chunk.of(event(1L, NODE_REF_A, EVENT_TIME_A)));

        verify(listener, never()).onBulkItemFailure(any(), any(), any(), any(), any());
    }

    @Test
    void dispatchesOnePerUniqueNodeRefWhenListenerPresent() throws Exception
    {
        stubBulkResponse(2, 1, List.of(new BulkItemFailure(NODE_REF_A, "mapper_parsing_exception", "bad type")));
        ElasticsearchRepoEventItemWriter writer = new ElasticsearchRepoEventItemWriter(operations, reindexProperties, listener);
        writer.open(executionContext);

        writer.write(Chunk.of(
                event(101L, NODE_REF_A, EVENT_TIME_A),
                event(102L, NODE_REF_B, EVENT_TIME_B)));

        verify(listener).onBulkItemFailure(eq(101L), eq(NODE_REF_A),
                eq(EVENT_TIME_A.toInstant().toEpochMilli()),
                eq("mapper_parsing_exception"), eq("bad type"));
        verify(listener, times(1)).onBulkItemFailure(any(), any(), any(), any(), any());
    }

    @Test
    void dedupesMultipleFailuresForSameNodeRef() throws Exception
    {
        // Metadata + content bulk ops both fail for the same node.
        stubBulkResponse(2, 2, List.of(
                new BulkItemFailure(NODE_REF_A, "mapper_parsing_exception", "bad type"),
                new BulkItemFailure(NODE_REF_A, "version_conflict_engine_exception", "conflict")));
        ElasticsearchRepoEventItemWriter writer = new ElasticsearchRepoEventItemWriter(operations, reindexProperties, listener);
        writer.open(executionContext);

        writer.write(Chunk.of(event(101L, NODE_REF_A, EVENT_TIME_A)));

        verify(listener, times(1)).onBulkItemFailure(eq(101L), eq(NODE_REF_A), anyLong(), any(), any());
    }

    @Test
    void dispatchesWithNullDbIdWhenNodeRefNotInChunk() throws Exception
    {
        // Defensive: shouldn't happen in practice, but proves the dispatcher doesn't NPE on drift.
        stubBulkResponse(1, 1, List.of(new BulkItemFailure(NODE_REF_B, "internal_server_error", "boom")));
        ElasticsearchRepoEventItemWriter writer = new ElasticsearchRepoEventItemWriter(operations, reindexProperties, listener);
        writer.open(executionContext);

        writer.write(Chunk.of(event(101L, NODE_REF_A, EVENT_TIME_A)));

        verify(listener).onBulkItemFailure(eq(null), eq(NODE_REF_B), eq(null),
                eq("internal_server_error"), eq("boom"));
    }

    @Test
    void listenerAbsent_stillWritesAndLogs() throws Exception
    {
        stubBulkResponse(1, 1, List.of(new BulkItemFailure(NODE_REF_A, "x", "y")));
        ElasticsearchRepoEventItemWriter writer = new ElasticsearchRepoEventItemWriter(operations, reindexProperties);
        writer.open(executionContext);

        // No listener bean, but the write path must still complete cleanly.
        assertThatCode(() -> writer.write(Chunk.of(event(101L, NODE_REF_A, EVENT_TIME_A))))
                .doesNotThrowAnyException();
    }

    @Test
    void isolatesListenerException() throws Exception
    {
        stubBulkResponse(1, 1, List.of(new BulkItemFailure(NODE_REF_A, "x", "y")));
        doThrow(new RuntimeException("listener down"))
                .when(listener).onBulkItemFailure(any(), any(), any(), any(), any());
        ElasticsearchRepoEventItemWriter writer = new ElasticsearchRepoEventItemWriter(operations, reindexProperties, listener);
        writer.open(executionContext);

        assertThatCode(() -> writer.write(Chunk.of(event(101L, NODE_REF_A, EVENT_TIME_A))))
                .doesNotThrowAnyException();
        verify(listener).onBulkItemFailure(any(), any(), any(), any(), any());
    }

    private void stubBulkResponse(int numberOfActions, long failuresCount, List<BulkItemFailure> failures) throws IOException, InterruptedException
    {
        BulkRequestDto dto = new BulkRequestDto();
        dto.setNumberOfActions(numberOfActions);
        dto.setFailuresCount(failuresCount);
        failures.forEach(dto::addFailure);
        when(operations.getBulkRequest(any(), anyLong(), anyInt(), anyLong())).thenReturn(dto);
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
