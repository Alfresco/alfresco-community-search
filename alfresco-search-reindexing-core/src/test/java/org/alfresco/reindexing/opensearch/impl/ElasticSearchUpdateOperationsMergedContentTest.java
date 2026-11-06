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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
 * The merged writer passes its reindexing start time to the content operation, so excluded content is cleared against the same cutoff as the metadata path.
 */
@DisplayName("merged writer content operation cutoff")
class ElasticSearchUpdateOperationsMergedContentTest
{
    private static final String INDEX = "test-index";
    private static final long START_TIME = 1000L;

    private OpenSearchClient openSearchClient;
    private ReindexingRequestBuilder requestBuilder;
    private ElasticSearchUpdateOperationsImpl operations;

    @BeforeEach
    void setUp()
    {
        openSearchClient = mock(OpenSearchClient.class);
        requestBuilder = mock(ReindexingRequestBuilder.class);
        ReindexPathFieldsBuilder pathFieldsBuilder = mock(ReindexPathFieldsBuilder.class);
        operations = new ElasticSearchUpdateOperationsImpl(openSearchClient, mock(ElasticsearchProperties.class),
                requestBuilder, false, pathFieldsBuilder);

        when(requestBuilder.buildMetadataFields(any(), anyLong(), anyBoolean())).thenReturn(Map.of("meta", "m"));
        when(pathFieldsBuilder.buildFields(any(), anyLong())).thenReturn(Map.of("path", "p"));
        when(requestBuilder.buildReindexUpsertOperation(any(), any())).thenReturn(dummyOp("n1"));
        when(requestBuilder.buildContentUpsertOperation(any(), eq(START_TIME)))
                .thenReturn(Optional.of(dummyOp("n1-content")));
    }

    @Test
    @DisplayName("create event passes the reindexing start time to the content operation")
    void createEventUsesReindexingStartTime() throws Exception
    {
        when(openSearchClient.bulk(any(BulkRequest.class)))
                .thenReturn(bulkResponse(successItem("n1"), successItem("n1-content")));

        operations.getMergedBulkRequest(List.of(createEventWithPaths(EventType.NODE_CREATED)), START_TIME, 0, 1L);

        verify(requestBuilder).buildContentUpsertOperation(any(), eq(START_TIME));
        assertThat(sentOperations()).hasSize(2);
    }

    @Test
    @DisplayName("archive delete passes the reindexing start time to the content operation")
    void archiveDeleteUsesReindexingStartTime() throws Exception
    {
        when(openSearchClient.bulk(any(BulkRequest.class)))
                .thenReturn(bulkResponse(successItem("n1"), successItem("n1-content")));

        operations.getMergedBulkRequest(List.of(createArchiveDeleteEvent()), START_TIME, 0, 1L);

        verify(requestBuilder).buildContentUpsertOperation(any(), eq(START_TIME));
        assertThat(sentOperations()).hasSize(2);
    }

    private List<BulkOperation> sentOperations() throws Exception
    {
        ArgumentCaptor<BulkRequest> captor = ArgumentCaptor.forClass(BulkRequest.class);
        verify(openSearchClient).bulk(captor.capture());
        return captor.getValue().operations();
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

    private static ReindexingEvent createArchiveDeleteEvent()
    {
        NodeResource resource = NodeResource.builder()
                .setId("workspace://SpacesStore/node")
                .setIsPermanentlyDeleted(false)
                .build();
        EventData<NodeResource> data = EventData.<NodeResource> builder().setResource(resource).build();
        RepoEvent<EventData<NodeResource>> repoEvent = RepoEvent.<EventData<NodeResource>> builder()
                .setType(EventType.NODE_DELETED.getType())
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

    private static BulkResponse bulkResponse(BulkResponseItem... items)
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
