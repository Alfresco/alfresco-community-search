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
package org.alfresco.reindexing.opensearch.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.core.BulkRequest;
import org.opensearch.client.opensearch.core.BulkResponse;
import org.opensearch.client.opensearch.core.bulk.BulkOperation;
import org.opensearch.client.opensearch.core.bulk.BulkResponseItem;
import org.opensearch.client.opensearch.core.bulk.OperationType;

import org.alfresco.database.connector.model.Path;
import org.alfresco.indexing.shared.ElasticsearchProperties;
import org.alfresco.reindexing.model.ReindexingEvent;
import org.alfresco.reindexing.opensearch.request.ReindexingRequestBuilder;
import org.alfresco.reindexing.writers.ReindexPathFieldsBuilder;
import org.alfresco.repo.event.v1.model.EventData;
import org.alfresco.repo.event.v1.model.EventType;
import org.alfresco.repo.event.v1.model.NodeResource;
import org.alfresco.repo.event.v1.model.RepoEvent;

/**
 * Unit tests for the merged metadata+path bulk assembly in {@link ElasticSearchUpdateOperationsImpl#getMergedBulkRequest}. The collaborating builders are mocked so these tests pin the orchestration contract: metadata and path fields are combined into a single scripted upsert (path wins on key overlap), the content op is preserved, deletes bypass the upsert path, and the retry loop is driven exactly once.
 */
@DisplayName("merged metadata+path bulk assembly")
class ElasticSearchUpdateOperationsMergedTest
{
    private static final String INDEX = "test-index";
    private static final long START_TIME = 1000L;

    private OpenSearchClient openSearchClient;
    private ReindexingRequestBuilder requestBuilder;
    private ReindexPathFieldsBuilder pathFieldsBuilder;
    private ElasticSearchUpdateOperationsImpl operations;

    @BeforeEach
    void setUp()
    {
        openSearchClient = mock(OpenSearchClient.class);
        ElasticsearchProperties elasticsearchProperties = mock(ElasticsearchProperties.class);
        requestBuilder = mock(ReindexingRequestBuilder.class);
        pathFieldsBuilder = mock(ReindexPathFieldsBuilder.class);
        operations = new ElasticSearchUpdateOperationsImpl(openSearchClient, elasticsearchProperties, requestBuilder,
                false, pathFieldsBuilder);
    }

    @Test
    @DisplayName("create event folds metadata + path into one upsert; path wins on key overlap; content op preserved")
    void createEventMergesMetadataAndPathFields() throws Exception
    {
        ReindexingEvent event = createEventWithPaths(EventType.NODE_CREATED);

        when(requestBuilder.buildMetadataFields(any(), anyLong()))
                .thenReturn(Map.of("meta", "m", "shared", "fromMetadata"));
        when(pathFieldsBuilder.buildFields(any(), anyLong()))
                .thenReturn(Map.of("path", "p", "shared", "fromPath"));
        when(requestBuilder.buildReindexUpsertOperation(any(), any())).thenReturn(dummyOp("n1"));
        when(requestBuilder.buildContentUpsertOperation(any())).thenReturn(Optional.of(dummyOp("n1-content")));
        when(openSearchClient.bulk(any(BulkRequest.class)))
                .thenReturn(bulkResponse(successItem("n1"), successItem("n1-content")));

        var dto = operations.getMergedBulkRequest(List.of(event), START_TIME, 0, 1L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> combined = ArgumentCaptor.forClass(Map.class);
        verify(requestBuilder).buildReindexUpsertOperation(any(), combined.capture());
        assertThat(combined.getValue())
                .containsEntry("meta", "m")
                .containsEntry("path", "p")
                .as("path fields are merged last, so they win on an overlapping key")
                .containsEntry("shared", "fromPath");

        assertThat(dto.getNumberOfActions()).isEqualTo(1);
        assertThat(dto.getFailures()).isEmpty();
        verify(requestBuilder).buildContentUpsertOperation(any());
        verify(openSearchClient, times(1)).bulk(any(BulkRequest.class));
    }

    @Test
    @DisplayName("delete event uses the delete op and never builds metadata/path fields")
    void deleteEventUsesDeleteOperation() throws Exception
    {
        ReindexingEvent event = createEventWithPaths(EventType.NODE_DELETED);
        when(requestBuilder.buildDeleteOperation(any())).thenReturn(dummyOp("n1"));
        when(openSearchClient.bulk(any(BulkRequest.class))).thenReturn(bulkResponse(successItem("n1")));

        var dto = operations.getMergedBulkRequest(List.of(event), START_TIME, 0, 1L);

        assertThat(dto.getNumberOfActions()).isEqualTo(1);
        verify(requestBuilder).buildDeleteOperation(any());
        verify(requestBuilder, never()).buildMetadataFields(any(), anyLong());
        verify(pathFieldsBuilder, never()).buildFields(any(), anyLong());
        verify(openSearchClient, times(1)).bulk(any(BulkRequest.class));
    }

    @Test
    @DisplayName("empty input skips the bulk call entirely")
    void emptyInputSkipsBulk() throws Exception
    {
        var dto = operations.getMergedBulkRequest(List.of(), START_TIME, 0, 1L);

        assertThat(dto.getNumberOfActions()).isZero();
        verify(openSearchClient, never()).bulk(any(BulkRequest.class));
    }

    @Test
    @DisplayName("merged request without a path-fields builder fails fast")
    void missingPathBuilderFailsFast()
    {
        ElasticsearchProperties props = mock(ElasticsearchProperties.class);
        var opsWithoutPathBuilder = new ElasticSearchUpdateOperationsImpl(openSearchClient, props, requestBuilder);

        assertThatThrownBy(() -> opsWithoutPathBuilder.getMergedBulkRequest(
                List.of(createEventWithPaths(EventType.NODE_CREATED)), START_TIME, 0, 1L))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("ReindexPathFieldsBuilder");
    }

    private static ReindexingEvent createEventWithPaths(EventType type)
    {
        NodeResource resource = NodeResource.builder().setId("workspace://SpacesStore/node").build();
        EventData<NodeResource> data = EventData.<NodeResource> builder().setResource(resource).build();
        RepoEvent<EventData<NodeResource>> repoEvent = RepoEvent.<EventData<NodeResource>> builder()
                .setType(type.getType())
                .setData(data)
                .build();
        return ReindexingEvent.builder()
                .withRepoEvent(repoEvent)
                .withAllPaths(Set.<Path> of())
                .build();
    }

    private static BulkOperation dummyOp(String id)
    {
        return new BulkOperation.Builder().index(io -> io.index(INDEX).id(id).document(Map.of())).build();
    }

    private static BulkResponse bulkResponse(BulkResponseItem... items) throws IOException
    {
        return new BulkResponse.Builder()
                .errors(false)
                .took(1L)
                .items(List.of(items))
                .build();
    }

    private static BulkResponseItem successItem(String id)
    {
        return new BulkResponseItem.Builder()
                .operationType(OperationType.Update)
                .id(id)
                .index(INDEX)
                .status(200)
                .build();
    }
}
