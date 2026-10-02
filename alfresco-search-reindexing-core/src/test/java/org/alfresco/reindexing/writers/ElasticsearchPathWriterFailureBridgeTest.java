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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ExecutionContext;

import org.alfresco.database.connector.model.ChildAssocMetaData;
import org.alfresco.database.connector.model.Path;
import org.alfresco.indexing.shared.ElasticsearchProperties;
import org.alfresco.indexing.shared.model.BulkItemFailure;
import org.alfresco.indexing.shared.model.BulkRequestDto;
import org.alfresco.reindexing.config.PrefixNamespaceConfig;
import org.alfresco.reindexing.config.ReindexingCoreProperties;
import org.alfresco.reindexing.model.ReindexingEvent;
import org.alfresco.reindexing.opensearch.ElasticSearchUpdateOperations;
import org.alfresco.repo.event.v1.model.EventData;
import org.alfresco.repo.event.v1.model.NodeResource;
import org.alfresco.repo.event.v1.model.RepoEvent;

/**
 * Focused unit tests for the path writer's failure bridge. Mirrors {@link ElasticsearchRepoEventItemWriterFailureBridgeTest}; the real ES call is stubbed out via a mocked {@link ElasticSearchUpdateOperations}.
 */
class ElasticsearchPathWriterFailureBridgeTest
{
    private static final String NODE_REF_A = "workspace://SpacesStore/aaaaaaaa";
    private static final String NODE_REF_B = "workspace://SpacesStore/bbbbbbbb";
    private static final ZonedDateTime EVENT_TIME_A = ZonedDateTime.of(2026, 6, 1, 12, 0, 0, 0, ZoneOffset.UTC);
    private static final ZonedDateTime EVENT_TIME_B = ZonedDateTime.of(2026, 6, 2, 12, 0, 0, 0, ZoneOffset.UTC);

    private ElasticsearchProperties esProps;
    private ReindexingCoreProperties reindexProps;
    private PrefixNamespaceConfig prefixConfig;
    private ElasticSearchUpdateOperations operations;
    private PathBulkItemFailureListener listener;
    private ExecutionContext executionContext;

    @BeforeEach
    void setUp()
    {
        esProps = mock(ElasticsearchProperties.class);
        when(esProps.getIndexName()).thenReturn("alfresco");
        reindexProps = mock(ReindexingCoreProperties.class);
        when(reindexProps.getWriterRetryCount()).thenReturn(3);
        when(reindexProps.getWriterRetryDelay()).thenReturn(10L);
        prefixConfig = mock(PrefixNamespaceConfig.class);
        when(prefixConfig.prefixUriMapper()).thenReturn(uri -> uri);
        operations = mock(ElasticSearchUpdateOperations.class);
        listener = mock(PathBulkItemFailureListener.class);
        executionContext = new ExecutionContext();
    }

    @Test
    void doesNotDispatchWhenNoFailures() throws Exception
    {
        stubBulkResponse(1, 0, List.of());
        ElasticsearchPathWriter writer = newWriterWith(listener);
        writer.open(executionContext);

        writer.write(Chunk.of(event(1L, NODE_REF_A, EVENT_TIME_A)));

        verify(listener, never()).onPathBulkItemFailure(any(), any(), any(), any(), any());
    }

    @Test
    void dispatchesOnePerUniqueNodeRefWhenListenerPresent() throws Exception
    {
        stubBulkResponse(2, 1, List.of(new BulkItemFailure(NODE_REF_A, "strict_dynamic_mapping_exception", "no such field")));
        ElasticsearchPathWriter writer = newWriterWith(listener);
        writer.open(executionContext);

        writer.write(Chunk.of(
                event(101L, NODE_REF_A, EVENT_TIME_A),
                event(102L, NODE_REF_B, EVENT_TIME_B)));

        verify(listener).onPathBulkItemFailure(eq(101L), eq(NODE_REF_A),
                eq(EVENT_TIME_A.toInstant().toEpochMilli()),
                eq("strict_dynamic_mapping_exception"), eq("no such field"));
        verify(listener, times(1)).onPathBulkItemFailure(any(), any(), any(), any(), any());
    }

    @Test
    void dedupesMultipleFailuresForSameNodeRef() throws Exception
    {
        // Same node produced two per-item failures across retries — dispatch only once.
        stubBulkResponse(2, 2, List.of(
                new BulkItemFailure(NODE_REF_A, "strict_dynamic_mapping_exception", "no such field"),
                new BulkItemFailure(NODE_REF_A, "internal_server_error", "boom")));
        ElasticsearchPathWriter writer = newWriterWith(listener);
        writer.open(executionContext);

        writer.write(Chunk.of(event(101L, NODE_REF_A, EVENT_TIME_A)));

        verify(listener, times(1)).onPathBulkItemFailure(eq(101L), eq(NODE_REF_A), anyLong(), any(), any());
    }

    @Test
    void dispatchesWithNullDbIdWhenNodeRefNotInChunk() throws Exception
    {
        stubBulkResponse(1, 1, List.of(new BulkItemFailure(NODE_REF_B, "internal_server_error", "boom")));
        ElasticsearchPathWriter writer = newWriterWith(listener);
        writer.open(executionContext);

        writer.write(Chunk.of(event(101L, NODE_REF_A, EVENT_TIME_A)));

        verify(listener).onPathBulkItemFailure(eq(null), eq(NODE_REF_B), eq(null),
                eq("internal_server_error"), eq("boom"));
    }

    @Test
    void listenerAbsentAndFailOnTerminalDisabled_stillWritesAndLogs() throws Exception
    {
        stubBulkResponse(1, 1, List.of(new BulkItemFailure(NODE_REF_A, "x", "y")));
        when(reindexProps.isFailOnTerminalWriteErrors()).thenReturn(false);
        ElasticsearchPathWriter writer = newWriterWith(null);
        writer.open(executionContext);

        assertThatCode(() -> writer.write(Chunk.of(event(101L, NODE_REF_A, EVENT_TIME_A))))
                .doesNotThrowAnyException();
    }

    @Test
    void listenerAbsentAndFailOnTerminalEnabled_failsChunk() throws Exception
    {
        stubBulkResponse(1, 1, List.of(new BulkItemFailure(NODE_REF_A, "x", "y")));
        when(reindexProps.isFailOnTerminalWriteErrors()).thenReturn(true);
        ElasticsearchPathWriter writer = newWriterWith(null);
        writer.open(executionContext);

        assertThatThrownBy(() -> writer.write(Chunk.of(event(101L, NODE_REF_A, EVENT_TIME_A))))
                .isInstanceOf(TerminalWriteFailureException.class);
    }

    @Test
    void isolatesListenerException() throws Exception
    {
        stubBulkResponse(1, 1, List.of(new BulkItemFailure(NODE_REF_A, "x", "y")));
        doThrow(new RuntimeException("listener down"))
                .when(listener).onPathBulkItemFailure(any(), any(), any(), any(), any());
        ElasticsearchPathWriter writer = newWriterWith(listener);
        writer.open(executionContext);

        assertThatCode(() -> writer.write(Chunk.of(event(101L, NODE_REF_A, EVENT_TIME_A))))
                .doesNotThrowAnyException();
        verify(listener).onPathBulkItemFailure(any(), any(), any(), any(), any());
    }

    private ElasticsearchPathWriter newWriterWith(PathBulkItemFailureListener listener)
    {
        return new ElasticsearchPathWriter(esProps, reindexProps, prefixConfig, operations, null, listener);
    }

    private void stubBulkResponse(int numberOfActions, long failuresCount, List<BulkItemFailure> failures)
            throws IOException, InterruptedException
    {
        BulkRequestDto dto = new BulkRequestDto();
        dto.setNumberOfActions(numberOfActions);
        dto.setFailuresCount(failuresCount);
        failures.forEach(dto::addFailure);
        when(operations.updateElasticSearchWithBulkRequests(any(), anyInt(), anyLong())).thenReturn(dto);
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
        // Empty paths pass the buildRequestList filter (non-null) and produce empty field sets in
        // createNodeResource without triggering any category / ancestor logic.
        Path emptyPath = mock(Path.class);
        when(emptyPath.getNodeMetadata()).thenReturn(new ArrayDeque<ChildAssocMetaData>());
        when(emptyPath.asString(any())).thenReturn("/");
        when(emptyPath.asStringNoPrefix()).thenReturn("/");
        return ReindexingEvent.builder()
                .withRepoEvent(repoEvent)
                .withNodeDbId(dbId)
                .withAllPaths(Set.of(emptyPath))
                .withPrimaryPath(emptyPath)
                .build();
    }
}
