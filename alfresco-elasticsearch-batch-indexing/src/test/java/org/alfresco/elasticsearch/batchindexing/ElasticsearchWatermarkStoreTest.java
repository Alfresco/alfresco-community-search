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

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.time.Instant;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonMappingException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.OpenSearchException;
import org.opensearch.client.opensearch.core.GetRequest;
import org.opensearch.client.opensearch.core.GetResponse;
import org.opensearch.client.opensearch.core.IndexRequest;
import org.opensearch.client.opensearch.core.IndexResponse;
import org.opensearch.client.opensearch.indices.CreateIndexRequest;
import org.opensearch.client.opensearch.indices.ExistsRequest;
import org.opensearch.client.opensearch.indices.OpenSearchIndicesClient;
import org.opensearch.client.transport.endpoints.BooleanResponse;

import org.alfresco.elasticsearch.batchindexing.model.WatermarkDocument;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("PMD.SignatureDeclareThrowsException")
class ElasticsearchWatermarkStoreTest
{
    private static final String INDEX_NAME = "test-reindex-state";
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 1L;

    @Mock
    private OpenSearchClient openSearchClient;

    private ElasticsearchWatermarkStore store;

    @BeforeEach
    void setUp()
    {
        store = new ElasticsearchWatermarkStore(openSearchClient, INDEX_NAME, MAX_RETRIES, RETRY_DELAY_MS);
    }

    @Nested
    @DisplayName("ensureIndexExists")
    class EnsureIndexExistsTests
    {
        private OpenSearchIndicesClient indicesClient;

        @BeforeEach
        void setUpIndices()
        {
            indicesClient = mock(OpenSearchIndicesClient.class);
            when(openSearchClient.indices()).thenReturn(indicesClient);
        }

        @Test
        @DisplayName("creates hidden index with the configured name")
        void createsHiddenIndexOfGivenName() throws Exception
        {
            when(indicesClient.exists(any(ExistsRequest.class)))
                    .thenReturn(new BooleanResponse(false));

            store.ensureIndexExists();

            ArgumentCaptor<CreateIndexRequest> captor = ArgumentCaptor.forClass(CreateIndexRequest.class);
            verify(indicesClient).create(captor.capture());
            assertThat(captor.getValue().index()).isEqualTo(INDEX_NAME);
        }

        @Test
        @DisplayName("does not create index when it already exists")
        void handlesExistingIndex() throws Exception
        {
            when(indicesClient.exists(any(ExistsRequest.class)))
                    .thenReturn(new BooleanResponse(true));

            store.ensureIndexExists();

            verify(indicesClient, never()).create(any(CreateIndexRequest.class));
        }

        @Test
        @DisplayName("retries on IOException and succeeds on subsequent attempt")
        void retriesOnIOException() throws Exception
        {
            when(indicesClient.exists(any(ExistsRequest.class)))
                    .thenThrow(new IOException("connection refused"))
                    .thenReturn(new BooleanResponse(true));

            store.ensureIndexExists();

            verify(indicesClient, times(2)).exists(any(ExistsRequest.class));
        }

        @Test
        @DisplayName("retries on OpenSearchException and succeeds on subsequent attempt")
        void retriesOnOpenSearchException() throws Exception
        {
            OpenSearchException osEx = mock(OpenSearchException.class);
            when(osEx.getMessage()).thenReturn("cluster unavailable");

            when(indicesClient.exists(any(ExistsRequest.class)))
                    .thenThrow(osEx)
                    .thenReturn(new BooleanResponse(false));

            store.ensureIndexExists();

            verify(indicesClient, times(2)).exists(any(ExistsRequest.class));
            verify(indicesClient).create(any(CreateIndexRequest.class));
        }

        @Test
        @DisplayName("throws WatermarkAccessException when all retry attempts are exhausted")
        void throwsIfNoRetriesLeft() throws Exception
        {
            when(indicesClient.exists(any(ExistsRequest.class)))
                    .thenThrow(new IOException("connection refused"));

            assertThatThrownBy(store::ensureIndexExists)
                    .isInstanceOf(WatermarkAccessException.class)
                    .hasMessageContaining(INDEX_NAME)
                    .hasMessageContaining(String.valueOf(MAX_RETRIES))
                    .hasCauseInstanceOf(IOException.class);

            verify(indicesClient, times(MAX_RETRIES)).exists(any(ExistsRequest.class));
        }

        @Test
        @DisplayName("thrown exception carries the last failure as cause")
        void exceptionCarriesLastCause() throws Exception
        {
            when(indicesClient.exists(any(ExistsRequest.class)))
                    .thenThrow(new IOException("first"))
                    .thenThrow(new IOException("second"))
                    .thenThrow(new IOException("third and final"));

            assertThatThrownBy(store::ensureIndexExists)
                    .isInstanceOf(WatermarkAccessException.class)
                    .hasCauseInstanceOf(IOException.class)
                    .cause().hasMessage("third and final");
        }
    }

    @Nested
    @DisplayName("read")
    class ReadTests
    {
        @Test
        @DisplayName("returns empty when document is not found (found=false)")
        void handlesNotFoundResponse() throws Exception
        {
            @SuppressWarnings("unchecked")
            GetResponse<WatermarkDocument> response = mock(GetResponse.class);
            when(response.found()).thenReturn(false);
            when(openSearchClient.get(any(GetRequest.class), eq(WatermarkDocument.class)))
                    .thenReturn(response);

            Optional<WatermarkDocument> result = store.read();

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("returns empty when source is null despite found=true")
        void handlesNullSource() throws Exception
        {
            @SuppressWarnings("unchecked")
            GetResponse<WatermarkDocument> response = mock(GetResponse.class);
            when(response.found()).thenReturn(true);
            when(response.source()).thenReturn(null);
            when(openSearchClient.get(any(GetRequest.class), eq(WatermarkDocument.class)))
                    .thenReturn(response);

            Optional<WatermarkDocument> result = store.read();

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("handles null seqNo without NPE")
        void handlesNullSeqNo() throws Exception
        {
            WatermarkDocument doc = new WatermarkDocument();
            @SuppressWarnings("unchecked")
            GetResponse<WatermarkDocument> response = mock(GetResponse.class);
            when(response.found()).thenReturn(true);
            when(response.source()).thenReturn(doc);
            when(response.seqNo()).thenReturn(null);
            when(response.primaryTerm()).thenReturn(1L);
            when(openSearchClient.get(any(GetRequest.class), eq(WatermarkDocument.class)))
                    .thenReturn(response);

            Optional<WatermarkDocument> result = store.read();

            assertThat(result).contains(doc);
        }

        @Test
        @DisplayName("handles null primaryTerm without NPE")
        void handlesNullPrimaryTerm() throws Exception
        {
            WatermarkDocument doc = new WatermarkDocument();
            @SuppressWarnings("unchecked")
            GetResponse<WatermarkDocument> response = mock(GetResponse.class);
            when(response.found()).thenReturn(true);
            when(response.source()).thenReturn(doc);
            when(response.seqNo()).thenReturn(5L);
            when(response.primaryTerm()).thenReturn(null);
            when(openSearchClient.get(any(GetRequest.class), eq(WatermarkDocument.class)))
                    .thenReturn(response);

            Optional<WatermarkDocument> result = store.read();

            assertThat(result).contains(doc);
        }

        @Test
        @DisplayName("maps response fields to WatermarkDocument and returns it")
        void mapsResponseToWatermarkDocument() throws Exception
        {
            WatermarkDocument doc = new WatermarkDocument();
            doc.setLastSuccessfulToTimeEpochMs(1_234_567_890L);
            doc.setLastRunStatus("COMPLETED");
            doc.setLastRunReadCount(100);
            doc.setLastRunWriteCount(95);

            @SuppressWarnings("unchecked")
            GetResponse<WatermarkDocument> response = mock(GetResponse.class);
            when(response.found()).thenReturn(true);
            when(response.source()).thenReturn(doc);
            when(response.seqNo()).thenReturn(5L);
            when(response.primaryTerm()).thenReturn(1L);
            when(openSearchClient.get(any(GetRequest.class), eq(WatermarkDocument.class)))
                    .thenReturn(response);

            Optional<WatermarkDocument> result = store.read();

            assertThat(result).isPresent();
            WatermarkDocument returned = result.get();
            assertThat(returned.getLastSuccessfulToTimeEpochMs()).isEqualTo(1_234_567_890L);
            assertThat(returned.getLastRunStatus()).isEqualTo("COMPLETED");
            assertThat(returned.getLastRunReadCount()).isEqualTo(100);
            assertThat(returned.getLastRunWriteCount()).isEqualTo(95);
            assertThat(returned).isSameAs(doc);
        }

        @Test
        @DisplayName("returns empty on 404 OpenSearchException")
        void handles404() throws Exception
        {
            OpenSearchException ex = mock(OpenSearchException.class);
            when(ex.status()).thenReturn(404);
            when(openSearchClient.get(any(GetRequest.class), eq(WatermarkDocument.class)))
                    .thenThrow(ex);

            Optional<WatermarkDocument> result = store.read();

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("throws WatermarkAccessException on non-404 OpenSearchException")
        void throwsOnNon404OpenSearchError() throws Exception
        {
            OpenSearchException ex = mock(OpenSearchException.class);
            when(ex.status()).thenReturn(500);
            when(openSearchClient.get(any(GetRequest.class), eq(WatermarkDocument.class)))
                    .thenThrow(ex);

            assertThatThrownBy(store::read)
                    .isInstanceOf(WatermarkAccessException.class)
                    .hasCauseInstanceOf(OpenSearchException.class);
        }

        @Test
        @DisplayName("throws WatermarkAccessException on generic IOException")
        void throwsOnGenericException() throws Exception
        {
            when(openSearchClient.get(any(GetRequest.class), eq(WatermarkDocument.class)))
                    .thenThrow(new IOException("network error"));

            assertThatThrownBy(store::read)
                    .isInstanceOf(WatermarkAccessException.class)
                    .hasCauseInstanceOf(IOException.class);
        }

        @Test
        @DisplayName("wraps deserialization failure as WatermarkAccessException (operator-tampered doc)")
        void wrapsDeserializationFailure() throws Exception
        {
            // Stands in for the "operator wrote garbage into the watermark doc" case:
            // ES returns 200 with a body the client can't map to WatermarkDocument
            // (e.g. a string where a long is expected).
            JsonMappingException parseError = JsonMappingException.from((JsonParser) null,
                    "Cannot deserialize value of type long from String \"corrupted\"");
            when(openSearchClient.get(any(GetRequest.class), eq(WatermarkDocument.class)))
                    .thenThrow(parseError);

            assertThatThrownBy(store::read)
                    .isInstanceOf(WatermarkAccessException.class)
                    .hasCauseInstanceOf(JsonMappingException.class)
                    .cause().hasMessageContaining("Cannot deserialize");
        }

        @Test
        @DisplayName("resets OCC fields when document disappears after a previous successful read")
        @SuppressWarnings("unchecked")
        void resetsOccFieldsOnNotFound() throws Exception
        {
            WatermarkDocument doc = new WatermarkDocument();
            GetResponse<WatermarkDocument> foundResponse = mock(GetResponse.class);
            when(foundResponse.found()).thenReturn(true);
            when(foundResponse.source()).thenReturn(doc);
            when(foundResponse.seqNo()).thenReturn(5L);
            when(foundResponse.primaryTerm()).thenReturn(1L);

            GetResponse<WatermarkDocument> notFoundResponse = mock(GetResponse.class);
            when(notFoundResponse.found()).thenReturn(false);

            when(openSearchClient.get(any(GetRequest.class), eq(WatermarkDocument.class)))
                    .thenReturn(foundResponse)
                    .thenReturn(notFoundResponse);

            store.read();
            store.read();

            IndexResponse indexResponse = mock(IndexResponse.class);
            when(indexResponse.seqNo()).thenReturn(1L);
            when(indexResponse.primaryTerm()).thenReturn(1L);
            when(openSearchClient.index(any(IndexRequest.class))).thenReturn(indexResponse);

            store.write(new WatermarkDocument());

            @SuppressWarnings("rawtypes")
            ArgumentCaptor<IndexRequest> captor = ArgumentCaptor.forClass(IndexRequest.class);
            verify(openSearchClient).index((IndexRequest<WatermarkDocument>) captor.capture());
            assertThat(captor.getValue().ifSeqNo()).isNull();
            assertThat(captor.getValue().ifPrimaryTerm()).isNull();
        }

        @Test
        @DisplayName("resets OCC fields on 404 after a previous successful read")
        @SuppressWarnings("unchecked")
        void resetsOccFieldsOn404() throws Exception
        {
            WatermarkDocument doc = new WatermarkDocument();
            GetResponse<WatermarkDocument> foundResponse = mock(GetResponse.class);
            when(foundResponse.found()).thenReturn(true);
            when(foundResponse.source()).thenReturn(doc);
            when(foundResponse.seqNo()).thenReturn(5L);
            when(foundResponse.primaryTerm()).thenReturn(1L);

            OpenSearchException notFoundEx = mock(OpenSearchException.class);
            when(notFoundEx.status()).thenReturn(404);

            when(openSearchClient.get(any(GetRequest.class), eq(WatermarkDocument.class)))
                    .thenReturn(foundResponse)
                    .thenThrow(notFoundEx);

            store.read();
            store.read();

            IndexResponse indexResponse = mock(IndexResponse.class);
            when(indexResponse.seqNo()).thenReturn(1L);
            when(indexResponse.primaryTerm()).thenReturn(1L);
            when(openSearchClient.index(any(IndexRequest.class))).thenReturn(indexResponse);

            store.write(new WatermarkDocument());

            @SuppressWarnings("rawtypes")
            ArgumentCaptor<IndexRequest> captor = ArgumentCaptor.forClass(IndexRequest.class);
            verify(openSearchClient).index((IndexRequest<WatermarkDocument>) captor.capture());
            assertThat(captor.getValue().ifSeqNo()).isNull();
            assertThat(captor.getValue().ifPrimaryTerm()).isNull();
        }
    }

    @Nested
    @DisplayName("write")
    class WriteTests
    {
        @Test
        @DisplayName("sets updatedAt field on the document")
        void setsUpdatedAtField() throws Exception
        {
            IndexResponse indexResponse = mock(IndexResponse.class);
            when(indexResponse.seqNo()).thenReturn(1L);
            when(indexResponse.primaryTerm()).thenReturn(1L);
            when(openSearchClient.index(any(IndexRequest.class))).thenReturn(indexResponse);

            WatermarkDocument doc = new WatermarkDocument();
            assertThat(doc.getUpdatedAt()).isNull();

            Instant before = Instant.now();
            store.write(doc);
            Instant after = Instant.now();

            assertThat(doc.getUpdatedAt()).isNotNull();
            Instant written = Instant.parse(doc.getUpdatedAt());
            assertThat(written).isBetween(before, after);
        }

        @Test
        @DisplayName("sets OCC fields (ifSeqNo/ifPrimaryTerm) from a prior read")
        void setsOccFieldsFromPriorRead() throws Exception
        {
            simulateSuccessfulRead(5L, 1L);

            IndexResponse indexResponse = mock(IndexResponse.class);
            when(indexResponse.seqNo()).thenReturn(6L);
            when(indexResponse.primaryTerm()).thenReturn(1L);
            when(openSearchClient.index(any(IndexRequest.class))).thenReturn(indexResponse);

            store.write(new WatermarkDocument());

            @SuppressWarnings("rawtypes")
            ArgumentCaptor<IndexRequest> captor = ArgumentCaptor.forClass(IndexRequest.class);
            verify(openSearchClient).index((IndexRequest<WatermarkDocument>) captor.capture());
            assertThat(captor.getValue().ifSeqNo()).isEqualTo(5L);
            assertThat(captor.getValue().ifPrimaryTerm()).isEqualTo(1L);
        }

        @Test
        @DisplayName("omits OCC guard on first-ever write (no prior read)")
        void omitsOccFieldsForFirstWrite() throws Exception
        {
            IndexResponse indexResponse = mock(IndexResponse.class);
            when(indexResponse.seqNo()).thenReturn(1L);
            when(indexResponse.primaryTerm()).thenReturn(1L);
            when(openSearchClient.index(any(IndexRequest.class))).thenReturn(indexResponse);

            store.write(new WatermarkDocument());

            @SuppressWarnings("rawtypes")
            ArgumentCaptor<IndexRequest> captor = ArgumentCaptor.forClass(IndexRequest.class);
            verify(openSearchClient).index((IndexRequest<WatermarkDocument>) captor.capture());
            assertThat(captor.getValue().ifSeqNo()).isNull();
            assertThat(captor.getValue().ifPrimaryTerm()).isNull();
        }

        @Test
        @DisplayName("omits OCC guard when prior read returned null seqNo/primaryTerm")
        void omitsOccFieldsWhenReadReturnedNulls() throws Exception
        {
            simulateReadWithNullOcc();

            IndexResponse indexResponse = mock(IndexResponse.class);
            when(indexResponse.seqNo()).thenReturn(1L);
            when(indexResponse.primaryTerm()).thenReturn(1L);
            when(openSearchClient.index(any(IndexRequest.class))).thenReturn(indexResponse);

            store.write(new WatermarkDocument());

            @SuppressWarnings("rawtypes")
            ArgumentCaptor<IndexRequest> captor = ArgumentCaptor.forClass(IndexRequest.class);
            verify(openSearchClient).index((IndexRequest<WatermarkDocument>) captor.capture());
            assertThat(captor.getValue().ifSeqNo()).isNull();
            assertThat(captor.getValue().ifPrimaryTerm()).isNull();
        }

        @Test
        @DisplayName("sends request with correct index name, document ID, and document body")
        void sendsRequestWithCorrectFields() throws Exception
        {
            IndexResponse indexResponse = mock(IndexResponse.class);
            when(indexResponse.seqNo()).thenReturn(1L);
            when(indexResponse.primaryTerm()).thenReturn(1L);
            when(openSearchClient.index(any(IndexRequest.class))).thenReturn(indexResponse);

            WatermarkDocument doc = new WatermarkDocument();
            doc.setLastSuccessfulToTimeEpochMs(999L);

            store.write(doc);

            @SuppressWarnings("rawtypes")
            ArgumentCaptor<IndexRequest> captor = ArgumentCaptor.forClass(IndexRequest.class);
            verify(openSearchClient).index((IndexRequest<WatermarkDocument>) captor.capture());
            IndexRequest<WatermarkDocument> captured = captor.getValue();
            assertThat(captured.index()).isEqualTo(INDEX_NAME);
            assertThat(captured.id()).isEqualTo(ElasticsearchWatermarkStore.WATERMARK_DOC_ID);
            assertThat(captured.document()).isSameAs(doc);
        }

        @Test
        @DisplayName("updates OCC fields from the response for the next write")
        void updatesOccFieldsAfterSuccess() throws Exception
        {
            IndexResponse firstResponse = mock(IndexResponse.class);
            when(firstResponse.seqNo()).thenReturn(10L);
            when(firstResponse.primaryTerm()).thenReturn(2L);

            IndexResponse secondResponse = mock(IndexResponse.class);
            when(secondResponse.seqNo()).thenReturn(11L);
            when(secondResponse.primaryTerm()).thenReturn(2L);

            when(openSearchClient.index(any(IndexRequest.class)))
                    .thenReturn(firstResponse)
                    .thenReturn(secondResponse);

            store.write(new WatermarkDocument());
            store.write(new WatermarkDocument());

            @SuppressWarnings("rawtypes")
            ArgumentCaptor<IndexRequest> captor = ArgumentCaptor.forClass(IndexRequest.class);
            verify(openSearchClient, times(2)).index((IndexRequest<WatermarkDocument>) captor.capture());

            IndexRequest<WatermarkDocument> secondRequest = captor.getAllValues().get(1);
            assertThat(secondRequest.ifSeqNo()).isEqualTo(10L);
            assertThat(secondRequest.ifPrimaryTerm()).isEqualTo(2L);
        }

        @Test
        @DisplayName("throws WatermarkConflictException on HTTP 409 without retrying")
        void throwsConflictOn409() throws Exception
        {
            simulateSuccessfulRead(5L, 1L);

            OpenSearchException ex = mock(OpenSearchException.class);
            when(ex.status()).thenReturn(409);
            when(openSearchClient.index(any(IndexRequest.class))).thenThrow(ex);

            assertThatThrownBy(() -> store.write(new WatermarkDocument()))
                    .isInstanceOf(WatermarkConflictException.class)
                    .hasMessageContaining("409")
                    .hasCauseInstanceOf(OpenSearchException.class);

            verify(openSearchClient, times(1)).index(any(IndexRequest.class));
        }

        @Test
        @DisplayName("conflict exception message includes current OCC field values")
        void conflictExceptionIncludesOccDetails() throws Exception
        {
            simulateSuccessfulRead(42L, 7L);

            OpenSearchException ex = mock(OpenSearchException.class);
            when(ex.status()).thenReturn(409);
            when(openSearchClient.index(any(IndexRequest.class))).thenThrow(ex);

            assertThatThrownBy(() -> store.write(new WatermarkDocument()))
                    .isInstanceOf(WatermarkConflictException.class)
                    .hasMessageContaining("ifSeqNo=42")
                    .hasMessageContaining("ifPrimaryTerm=7");
        }

        @Test
        @DisplayName("retries on transient error and succeeds on subsequent attempt")
        void retriesThenSucceeds() throws Exception
        {
            IndexResponse indexResponse = mock(IndexResponse.class);
            when(indexResponse.seqNo()).thenReturn(1L);
            when(indexResponse.primaryTerm()).thenReturn(1L);

            when(openSearchClient.index(any(IndexRequest.class)))
                    .thenThrow(new IOException("transient failure"))
                    .thenReturn(indexResponse);

            assertThatCode(() -> store.write(new WatermarkDocument()))
                    .doesNotThrowAnyException();

            verify(openSearchClient, times(2)).index(any(IndexRequest.class));
        }

        @Test
        @DisplayName("retries on OpenSearchException (non-409) and succeeds on subsequent attempt")
        void retriesOnNon409OpenSearchException() throws Exception
        {
            OpenSearchException transientEx = mock(OpenSearchException.class);
            when(transientEx.status()).thenReturn(503);
            when(transientEx.getMessage()).thenReturn("service unavailable");

            IndexResponse indexResponse = mock(IndexResponse.class);
            when(indexResponse.seqNo()).thenReturn(1L);
            when(indexResponse.primaryTerm()).thenReturn(1L);

            when(openSearchClient.index(any(IndexRequest.class)))
                    .thenThrow(transientEx)
                    .thenReturn(indexResponse);

            assertThatCode(() -> store.write(new WatermarkDocument()))
                    .doesNotThrowAnyException();

            verify(openSearchClient, times(2)).index(any(IndexRequest.class));
        }

        @Test
        @DisplayName("does not throw when all retry attempts are exhausted (silent failure)")
        void silentFailureAfterExhaustedRetries() throws Exception
        {
            when(openSearchClient.index(any(IndexRequest.class)))
                    .thenThrow(new IOException("persistent failure"));

            assertThatCode(() -> store.write(new WatermarkDocument()))
                    .doesNotThrowAnyException();

            verify(openSearchClient, times(MAX_RETRIES)).index(any(IndexRequest.class));
        }

        @Test
        @DisplayName("retries exactly maxRetries times before giving up")
        void retriesExactlyMaxRetries() throws Exception
        {
            OpenSearchException ex = mock(OpenSearchException.class);
            when(ex.status()).thenReturn(503);
            when(ex.getMessage()).thenReturn("unavailable");
            when(openSearchClient.index(any(IndexRequest.class))).thenThrow(ex);

            store.write(new WatermarkDocument());

            verify(openSearchClient, times(MAX_RETRIES)).index(any(IndexRequest.class));
        }
    }

    /**
     * State doc or index deleted between our last {@link ElasticsearchWatermarkStore#read()} and the write. The store must distinguish this from a real OCC conflict (someone else won the race) and, in the delete case, drop the OCC guard and retry unconditionally so the in-memory cursor is preserved.
     */
    @Nested
    @DisplayName("Recovery — state deleted between read and write")
    class StateDeletedRecoveryTests
    {
        @Test
        @DisplayName("409 followed by doc-missing probe -> drops OCC and retries unconditionally, preserving cursor")
        @SuppressWarnings("unchecked")
        void doc_missing_after_409_triggers_unconditional_retry() throws Exception
        {
            simulateSuccessfulRead(5L, 1L);

            OpenSearchException conflict = mock(OpenSearchException.class);
            when(conflict.status()).thenReturn(409);

            GetResponse<WatermarkDocument> missing = mock(GetResponse.class);
            when(missing.found()).thenReturn(false);
            when(openSearchClient.get(any(GetRequest.class), eq(WatermarkDocument.class)))
                    .thenReturn(missing);

            IndexResponse okResponse = mock(IndexResponse.class);
            when(okResponse.seqNo()).thenReturn(1L);
            when(okResponse.primaryTerm()).thenReturn(1L);
            when(openSearchClient.index(any(IndexRequest.class)))
                    .thenThrow(conflict)
                    .thenReturn(okResponse);

            WatermarkDocument doc = new WatermarkDocument();
            doc.setLastSuccessfulToTimeEpochMs(1_000_000L);

            assertThatCode(() -> store.write(doc)).doesNotThrowAnyException();

            ArgumentCaptor<IndexRequest> captor = ArgumentCaptor.forClass(IndexRequest.class);
            verify(openSearchClient, times(2)).index((IndexRequest<WatermarkDocument>) captor.capture());

            IndexRequest<WatermarkDocument> firstAttempt = captor.getAllValues().get(0);
            assertThat(firstAttempt.ifSeqNo())
                    .as("first write is OCC-guarded with the previously-read seqNo")
                    .isEqualTo(5L);

            IndexRequest<WatermarkDocument> retry = captor.getAllValues().get(1);
            assertThat(retry.ifSeqNo())
                    .as("retry after recovery must be unconditional so the in-memory cursor wins")
                    .isNull();
            assertThat(retry.ifPrimaryTerm()).isNull();
            assertThat(retry.document())
                    .as("retry keeps the same document (cursor preserved)")
                    .isSameAs(doc);
        }

        @Test
        @DisplayName("409 followed by doc-present probe -> real conflict, throws WatermarkConflictException")
        @SuppressWarnings("unchecked")
        void doc_present_after_409_stays_a_real_conflict() throws Exception
        {
            simulateSuccessfulRead(5L, 1L);

            OpenSearchException conflict = mock(OpenSearchException.class);
            when(conflict.status()).thenReturn(409);
            when(openSearchClient.index(any(IndexRequest.class))).thenThrow(conflict);

            GetResponse<WatermarkDocument> present = mock(GetResponse.class);
            when(present.found()).thenReturn(true);
            when(openSearchClient.get(any(GetRequest.class), eq(WatermarkDocument.class)))
                    .thenReturn(present);

            assertThatThrownBy(() -> store.write(new WatermarkDocument()))
                    .isInstanceOf(WatermarkConflictException.class)
                    .hasMessageContaining("ifSeqNo=5");

            verify(openSearchClient, times(1)).index(any(IndexRequest.class));
        }

        @Test
        @DisplayName("409 with no prior OCC guard (first write) does not attempt state-deleted recovery")
        void first_write_409_is_not_recoverable() throws Exception
        {
            OpenSearchException conflict = mock(OpenSearchException.class);
            when(conflict.status()).thenReturn(409);
            when(openSearchClient.index(any(IndexRequest.class))).thenThrow(conflict);

            assertThatThrownBy(() -> store.write(new WatermarkDocument()))
                    .isInstanceOf(WatermarkConflictException.class);

            verify(openSearchClient, never()).get(any(GetRequest.class), eq(WatermarkDocument.class));
            verify(openSearchClient, times(1)).index(any(IndexRequest.class));
        }

        @Test
        @DisplayName("404 on write with OCC guard active -> recreates the state index then retries unconditionally")
        @SuppressWarnings("unchecked")
        void index_missing_on_write_recreates_and_retries() throws Exception
        {
            simulateSuccessfulRead(5L, 1L);

            OpenSearchException notFound = mock(OpenSearchException.class);
            when(notFound.status()).thenReturn(404);

            IndexResponse okResponse = mock(IndexResponse.class);
            when(okResponse.seqNo()).thenReturn(1L);
            when(okResponse.primaryTerm()).thenReturn(1L);

            when(openSearchClient.index(any(IndexRequest.class)))
                    .thenThrow(notFound)
                    .thenReturn(okResponse);

            OpenSearchIndicesClient indicesClient = mock(OpenSearchIndicesClient.class);
            when(openSearchClient.indices()).thenReturn(indicesClient);
            when(indicesClient.exists(any(ExistsRequest.class))).thenReturn(new BooleanResponse(false));

            assertThatCode(() -> store.write(new WatermarkDocument())).doesNotThrowAnyException();

            verify(indicesClient).create(any(CreateIndexRequest.class));

            ArgumentCaptor<IndexRequest> captor = ArgumentCaptor.forClass(IndexRequest.class);
            verify(openSearchClient, times(2)).index((IndexRequest<WatermarkDocument>) captor.capture());
            IndexRequest<WatermarkDocument> retry = captor.getAllValues().get(1);
            assertThat(retry.ifSeqNo()).isNull();
            assertThat(retry.ifPrimaryTerm()).isNull();
        }

        @Test
        @DisplayName("409 followed by a probe that itself fails (not 404) -> treated as real conflict")
        @SuppressWarnings("unchecked")
        void probe_failure_conservatively_treated_as_conflict() throws Exception
        {
            simulateSuccessfulRead(5L, 1L);

            OpenSearchException conflict = mock(OpenSearchException.class);
            when(conflict.status()).thenReturn(409);
            when(openSearchClient.index(any(IndexRequest.class))).thenThrow(conflict);

            OpenSearchException probeError = mock(OpenSearchException.class);
            when(probeError.status()).thenReturn(500);
            when(openSearchClient.get(any(GetRequest.class), eq(WatermarkDocument.class)))
                    .thenThrow(probeError);

            assertThatThrownBy(() -> store.write(new WatermarkDocument()))
                    .isInstanceOf(WatermarkConflictException.class);
        }
    }

    @SuppressWarnings("unchecked")
    private void simulateSuccessfulRead(long seqNo, long primaryTerm) throws Exception
    {
        GetResponse<WatermarkDocument> response = mock(GetResponse.class);
        when(response.found()).thenReturn(true);
        when(response.source()).thenReturn(new WatermarkDocument());
        when(response.seqNo()).thenReturn(seqNo);
        when(response.primaryTerm()).thenReturn(primaryTerm);
        when(openSearchClient.get(any(GetRequest.class), eq(WatermarkDocument.class)))
                .thenReturn(response);
        store.read();
    }

    @SuppressWarnings("unchecked")
    private void simulateReadWithNullOcc() throws Exception
    {
        GetResponse<WatermarkDocument> response = mock(GetResponse.class);
        when(response.found()).thenReturn(true);
        when(response.source()).thenReturn(new WatermarkDocument());
        when(response.seqNo()).thenReturn(null);
        when(response.primaryTerm()).thenReturn(null);
        when(openSearchClient.get(any(GetRequest.class), eq(WatermarkDocument.class)))
                .thenReturn(response);
        store.read();
    }
}
