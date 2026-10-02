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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.ErrorCause;
import org.opensearch.client.opensearch.core.BulkRequest;
import org.opensearch.client.opensearch.core.BulkResponse;
import org.opensearch.client.opensearch.core.bulk.BulkResponseItem;
import org.opensearch.client.opensearch.core.bulk.OperationType;

import org.alfresco.indexing.shared.ElasticsearchProperties;
import org.alfresco.indexing.shared.model.NodeResourceDto;
import org.alfresco.reindexing.opensearch.request.ReindexingRequestBuilder;
import org.alfresco.reindexing.writers.ReindexPathFieldsBuilder;

/**
 * Focused unit tests for the writer-side retry loop in {@link ElasticSearchUpdateOperationsImpl}. The retry loop is shared by the metadata and path bulk paths; exercising it through {@link ElasticSearchUpdateOperationsImpl#updateElasticSearchWithBulkRequests} (the path-writer entry point) is enough to pin every branch of the classifier + retry contract.
 */
@DisplayName("bulk write retry loop")
class ElasticSearchUpdateOperationsImplTest
{
    private static final String INDEX = "test-index";

    private OpenSearchClient openSearchClient;
    private ElasticSearchUpdateOperationsImpl operations;

    @BeforeEach
    void setUp()
    {
        openSearchClient = mock(OpenSearchClient.class);
        ElasticsearchProperties elasticsearchProperties = mock(ElasticsearchProperties.class);
        ReindexingRequestBuilder builder = mock(ReindexingRequestBuilder.class);
        operations = new ElasticSearchUpdateOperationsImpl(openSearchClient, elasticsearchProperties, builder);
    }

    @Nested
    @DisplayName("empty or all-success inputs")
    class TrivialPaths
    {
        @Test
        @DisplayName("empty input skips the bulk call entirely")
        void emptyInputSkipsBulk() throws Exception
        {
            var dto = operations.updateElasticSearchWithBulkRequests(List.of(), 3, 1L);
            assertThat(dto.getNumberOfActions()).isZero();
            assertThat(dto.getFailures()).isEmpty();
            verify(openSearchClient, times(0)).bulk(any(BulkRequest.class));
        }

        @Test
        @DisplayName("all items succeed on the first attempt — no retry, no failures")
        void allSucceedOnFirstAttempt() throws Exception
        {
            when(openSearchClient.bulk(any(BulkRequest.class))).thenReturn(bulkResponse(
                    successItem("id-1"), successItem("id-2")));

            var dto = operations.updateElasticSearchWithBulkRequests(
                    List.of(node("id-1"), node("id-2")), 3, 1L);

            assertThat(dto.getNumberOfActions()).isEqualTo(2);
            assertThat(dto.getFailures()).isEmpty();
            assertThat(dto.getInitialFailuresCount()).isZero();
            verify(openSearchClient, times(1)).bulk(any(BulkRequest.class));
        }
    }

    @Nested
    @DisplayName("terminal failures")
    class TerminalFailures
    {
        @Test
        @DisplayName("mapping errors are terminal and skip the retry loop")
        void mappingErrorLandsInFailuresImmediately() throws Exception
        {
            when(openSearchClient.bulk(any(BulkRequest.class))).thenReturn(bulkResponse(
                    successItem("id-1"),
                    errorItem("id-2", 400, "mapper_parsing_exception", "bad type")));

            var dto = operations.updateElasticSearchWithBulkRequests(
                    List.of(node("id-1"), node("id-2")), 3, 1L);

            assertThat(dto.getNumberOfActions()).isEqualTo(2);
            assertThat(dto.getFailures())
                    .singleElement()
                    .satisfies(f -> {
                        assertThat(f.documentId()).isEqualTo("id-2");
                        assertThat(f.errorType()).isEqualTo("mapper_parsing_exception");
                    });
            assertThat(dto.getFailuresCount()).isEqualTo(1);
            assertThat(dto.getInitialFailuresCount()).isEqualTo(1);
            verify(openSearchClient, times(1)).bulk(any(BulkRequest.class));
        }
    }

    @Nested
    @DisplayName("retryable failures — 400 vs 429/5xx differentiation")
    class RetryableFailures
    {
        @Test
        @DisplayName("transient 429 is retried and succeeds on round 2 — no failures at the end")
        void transient429SucceedsOnRetry() throws Exception
        {
            when(openSearchClient.bulk(any(BulkRequest.class)))
                    .thenReturn(bulkResponse(errorItem("id-1", 429, "es_rejected_execution_exception", "rejected")))
                    .thenReturn(bulkResponse(successItem("id-1")));

            var dto = operations.updateElasticSearchWithBulkRequests(List.of(node("id-1")), 3, 1L);

            assertThat(dto.getFailures()).isEmpty();
            assertThat(dto.getFailuresCount()).isZero();
            assertThat(dto.getInitialFailuresCount())
                    .as("first-attempt failure count still records the transient hit for the counter log")
                    .isEqualTo(1);
            verify(openSearchClient, times(2)).bulk(any(BulkRequest.class));
        }

        @Test
        @DisplayName("mixed round-1 response: terminal recorded once, retryable retried, others left alone")
        void mixedRoundOneSplitsTerminalFromRetryable() throws Exception
        {
            when(openSearchClient.bulk(any(BulkRequest.class)))
                    .thenReturn(bulkResponse(
                            successItem("id-ok"),
                            errorItem("id-perm", 400, "mapper_parsing_exception", "bad"),
                            errorItem("id-transient", 503, "unavailable_shards_exception", "wait")))
                    .thenReturn(bulkResponse(successItem("id-transient")));

            var dto = operations.updateElasticSearchWithBulkRequests(
                    List.of(node("id-ok"), node("id-perm"), node("id-transient")), 3, 1L);

            assertThat(dto.getFailures())
                    .singleElement()
                    .satisfies(f -> assertThat(f.documentId()).isEqualTo("id-perm"));
            assertThat(dto.getInitialFailuresCount()).isEqualTo(2);
            verify(openSearchClient, times(2)).bulk(any(BulkRequest.class));
        }

        @Test
        @DisplayName("sustained 429 across every round lands in the final failures list (round budget respected)")
        void sustained429HitsRetryCeiling() throws Exception
        {
            when(openSearchClient.bulk(any(BulkRequest.class))).thenReturn(bulkResponse(
                    errorItem("id-1", 429, "es_rejected_execution_exception", "rejected")));

            var dto = operations.updateElasticSearchWithBulkRequests(List.of(node("id-1")), 2, 1L);

            assertThat(dto.getFailures())
                    .singleElement()
                    .satisfies(f -> {
                        assertThat(f.documentId()).isEqualTo("id-1");
                        assertThat(f.errorType()).isEqualTo("es_rejected_execution_exception");
                    });
            assertThat(dto.getFailuresCount()).isEqualTo(1);
            assertThat(dto.getInitialFailuresCount()).isEqualTo(1);
            verify(openSearchClient, times(3)).bulk(any(BulkRequest.class));
        }

        @Test
        @DisplayName("retryCount=0 disables the loop — retryable failures still surface as failures")
        void retryCountZeroDisablesLoop() throws Exception
        {
            when(openSearchClient.bulk(any(BulkRequest.class))).thenReturn(bulkResponse(
                    errorItem("id-1", 429, "es_rejected_execution_exception", "rejected")));

            var dto = operations.updateElasticSearchWithBulkRequests(List.of(node("id-1")), 0, 1L);

            assertThat(dto.getFailures()).hasSize(1);
            verify(openSearchClient, times(1)).bulk(any(BulkRequest.class));
        }
    }

    @Nested
    @DisplayName("path scripted-upsert branch")
    class PathScriptedUpsert
    {
        @Test
        @DisplayName("pathUpsertEnabled=true builds each path update as a scripted upsert with retryOnConflict")
        void pathUpsertEnabledBuildsScriptedUpsert() throws Exception
        {
            ElasticsearchProperties props = mock(ElasticsearchProperties.class);
            when(props.getRetryOnConflict()).thenReturn(5);
            ReindexingRequestBuilder builder = mock(ReindexingRequestBuilder.class);
            ReindexPathFieldsBuilder pathFieldsBuilder = mock(ReindexPathFieldsBuilder.class);
            var upsertOps = new ElasticSearchUpdateOperationsImpl(openSearchClient, props, builder, true, pathFieldsBuilder);
            when(openSearchClient.bulk(any(BulkRequest.class))).thenReturn(bulkResponse(successItem("id-1")));

            upsertOps.updateElasticSearchWithBulkRequests(List.of(nodeWithFields("id-1")), 3, 1L);

            ArgumentCaptor<BulkRequest> captor = ArgumentCaptor.forClass(BulkRequest.class);
            verify(openSearchClient).bulk(captor.capture());
            var op = captor.getValue().operations().get(0);
            assertThat(op.isUpdate()).isTrue();
            // retryOnConflict is set in the SAME branch as the upsert seed, so it is a faithful
            // discriminator that the scripted-upsert path (upsert + retryOnConflict) was taken: the doc
            // is seeded on create (no document_missing 404) and version conflicts retry in-place instead
            // of burning a writer retry round. The upsert body itself has no public getter to assert on.
            assertThat(op.update().retryOnConflict()).isEqualTo(5);
        }

        @Test
        @DisplayName("pathUpsert disabled builds a plain scripted update (no upsert, no retryOnConflict)")
        void pathUpsertDisabledBuildsPlainUpdate() throws Exception
        {
            ElasticsearchProperties props = mock(ElasticsearchProperties.class);
            ReindexingRequestBuilder builder = mock(ReindexingRequestBuilder.class);
            var plainOps = new ElasticSearchUpdateOperationsImpl(openSearchClient, props, builder);
            when(openSearchClient.bulk(any(BulkRequest.class))).thenReturn(bulkResponse(successItem("id-1")));

            plainOps.updateElasticSearchWithBulkRequests(List.of(nodeWithFields("id-1")), 3, 1L);

            ArgumentCaptor<BulkRequest> captor = ArgumentCaptor.forClass(BulkRequest.class);
            verify(openSearchClient).bulk(captor.capture());
            var op = captor.getValue().operations().get(0);
            assertThat(op.isUpdate()).isTrue();
            // No upsert branch taken: retryOnConflict stays null, matching the historical plain path-update.
            assertThat(op.update().retryOnConflict()).isNull();
        }
    }

    private static NodeResourceDto node(String id)
    {
        NodeResourceDto dto = new NodeResourceDto();
        dto.setResourceId(id);
        dto.setIndexName(INDEX);
        dto.setFields(new HashMap<>());
        return dto;
    }

    private static NodeResourceDto nodeWithFields(String id)
    {
        NodeResourceDto dto = new NodeResourceDto();
        dto.setResourceId(id);
        dto.setIndexName(INDEX);
        Map<String, Object> fields = new HashMap<>();
        fields.put("PATH", List.of("/a/b"));
        dto.setFields(fields);
        return dto;
    }

    private static BulkResponse bulkResponse(BulkResponseItem... items) throws IOException
    {
        boolean hasErrors = false;
        for (BulkResponseItem item : items)
        {
            if (item.error() != null)
            {
                hasErrors = true;
                break;
            }
        }
        return new BulkResponse.Builder()
                .errors(hasErrors)
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

    private static BulkResponseItem errorItem(String id, int status, String errorType, String reason)
    {
        return new BulkResponseItem.Builder()
                .operationType(OperationType.Update)
                .id(id)
                .index(INDEX)
                .status(status)
                .error(new ErrorCause.Builder().type(errorType).reason(reason).build())
                .build();
    }
}
