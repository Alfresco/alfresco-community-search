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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.core.DeleteRequest;
import org.opensearch.client.opensearch.core.GetRequest;
import org.opensearch.client.opensearch.core.GetResponse;
import org.opensearch.client.opensearch.core.IndexRequest;
import org.opensearch.client.opensearch.indices.CreateIndexRequest;
import org.opensearch.client.opensearch.indices.ExistsRequest;
import org.opensearch.client.opensearch.indices.OpenSearchIndicesClient;
import org.opensearch.client.transport.endpoints.BooleanResponse;

import org.alfresco.elasticsearch.batchindexing.model.DeadLetterDocument;
import org.alfresco.elasticsearch.batchindexing.model.DeadLetterDocument.FailureStage;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings({"PMD.SignatureDeclareThrowsException", "PMD.CouplingBetweenObjects"})
class DlqWriterTest
{
    private static final String INDEX_NAME = "test-dead-letter";

    @Mock
    private OpenSearchClient openSearchClient;

    private DlqWriter writer;

    @BeforeEach
    void setUp()
    {
        writer = new DlqWriter(openSearchClient, INDEX_NAME);
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
        void createsHiddenIndex() throws Exception
        {
            when(indicesClient.exists(any(ExistsRequest.class)))
                    .thenReturn(new BooleanResponse(false));

            writer.ensureIndexExists();

            ArgumentCaptor<CreateIndexRequest> captor = ArgumentCaptor.forClass(CreateIndexRequest.class);
            verify(indicesClient).create(captor.capture());
            assertThat(captor.getValue().index()).isEqualTo(INDEX_NAME);
        }

        @Test
        @DisplayName("does not create the index when it already exists")
        void skipsCreationWhenPresent() throws Exception
        {
            when(indicesClient.exists(any(ExistsRequest.class)))
                    .thenReturn(new BooleanResponse(true));

            writer.ensureIndexExists();

            verify(indicesClient, never()).create(any(CreateIndexRequest.class));
        }

        @Test
        @DisplayName("swallows failures so a broken DLQ never crashes startup")
        void swallowsFailures() throws Exception
        {
            when(indicesClient.exists(any(ExistsRequest.class)))
                    .thenThrow(new IOException("cluster unreachable"));

            assertThatCode(writer::ensureIndexExists).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("recordSkip")
    class RecordSkipTests
    {
        @Test
        @DisplayName("writes a fresh document with failureCount=1 when nothing exists yet")
        void writesFreshDocumentOnFirstFailure() throws Exception
        {
            stubGetMiss();

            writer.recordSkip(42L, "workspace://SpacesStore/abc", 1_700_000_000_000L, FailureStage.PROCESSOR,
                    new IllegalStateException("bad node"));

            DeadLetterDocument doc = captureIndexed();
            assertThat(doc.getDbId()).isEqualTo(42L);
            assertThat(doc.getNodeRef()).isEqualTo("workspace://SpacesStore/abc");
            assertThat(doc.getSourceCommitTimeMs()).isEqualTo(1_700_000_000_000L);
            assertThat(doc.getFailureStage()).isEqualTo(FailureStage.PROCESSOR);
            assertThat(doc.getFailureType()).isEqualTo("IllegalStateException");
            assertThat(doc.getFailureReason()).isEqualTo("bad node");
            assertThat(doc.getFailureCount()).isEqualTo(1);
            assertThat(doc.getFirstFailedAt()).isEqualTo(doc.getLastFailedAt());
        }

        @Test
        @DisplayName("preserves firstFailedAt and increments failureCount on repeat")
        void mergesRepeatFailure() throws Exception
        {
            DeadLetterDocument existing = new DeadLetterDocument();
            existing.setDbId(42L);
            existing.setFirstFailedAt("2026-01-01T00:00:00Z");
            existing.setLastFailedAt("2026-01-01T00:00:00Z");
            existing.setFailureCount(3);
            stubGetHit(existing);

            writer.recordSkip(42L, null, null, FailureStage.METADATA_WRITER, new IOException("boom"));

            DeadLetterDocument doc = captureIndexed();
            assertThat(doc.getFirstFailedAt()).isEqualTo("2026-01-01T00:00:00Z");
            assertThat(doc.getLastFailedAt()).isNotEqualTo("2026-01-01T00:00:00Z");
            assertThat(doc.getFailureCount()).isEqualTo(4);
            assertThat(doc.getFailureStage()).isEqualTo(FailureStage.METADATA_WRITER);
            assertThat(doc.getFailureType()).isEqualTo("IOException");
        }

        @Test
        @DisplayName("no-op when dbId is null (read-side skip)")
        void noOpWithoutDbId() throws Exception
        {
            writer.recordSkip(null, null, null, FailureStage.READER, new IOException("db down"));

            verify(openSearchClient, never()).index(any(IndexRequest.class));
        }

        @Test
        @DisplayName("truncates oversized failure messages to MAX_REASON_LENGTH")
        void truncatesLongMessage() throws Exception
        {
            stubGetMiss();
            String longMessage = "x".repeat(DlqWriter.MAX_REASON_LENGTH * 2);

            writer.recordSkip(1L, null, null, FailureStage.PROCESSOR, new RuntimeException(longMessage));

            DeadLetterDocument doc = captureIndexed();
            assertThat(doc.getFailureReason()).hasSize(DlqWriter.MAX_REASON_LENGTH);
        }

        @Test
        @DisplayName("swallows write failures so indexing never blocks on a broken DLQ")
        void swallowsIndexWriteFailure() throws Exception
        {
            stubGetMiss();
            when(openSearchClient.index(any(IndexRequest.class))).thenThrow(new IOException("ES gone"));

            assertThatCode(() -> writer.recordSkip(1L, null, null, FailureStage.PROCESSOR, new RuntimeException("x")))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("string-overload preserves explicit failure type and reason")
        void stringOverloadWritesExplicitTypeAndReason() throws Exception
        {
            stubGetMiss();

            writer.recordSkip(99L, "workspace://SpacesStore/xyz", 1_700_000_000_000L, FailureStage.METADATA_WRITER,
                    "mapper_parsing_exception", "invalid keyword");

            DeadLetterDocument doc = captureIndexed();
            assertThat(doc.getFailureType()).isEqualTo("mapper_parsing_exception");
            assertThat(doc.getFailureReason()).isEqualTo("invalid keyword");
            assertThat(doc.getFailureStage()).isEqualTo(FailureStage.METADATA_WRITER);
        }

        @Test
        @DisplayName("string-overload truncates oversized reasons")
        void stringOverloadTruncates() throws Exception
        {
            stubGetMiss();
            String longReason = "y".repeat(DlqWriter.MAX_REASON_LENGTH * 2);

            writer.recordSkip(100L, null, null, FailureStage.METADATA_WRITER, "some_type", longReason);

            DeadLetterDocument doc = captureIndexed();
            assertThat(doc.getFailureReason()).hasSize(DlqWriter.MAX_REASON_LENGTH);
        }

        private void stubGetMiss() throws Exception
        {
            @SuppressWarnings("unchecked")
            GetResponse<DeadLetterDocument> response = mock(GetResponse.class);
            when(response.found()).thenReturn(false);
            when(openSearchClient.get(any(GetRequest.class), eq(DeadLetterDocument.class))).thenReturn(response);
        }

        private void stubGetHit(DeadLetterDocument existing) throws Exception
        {
            @SuppressWarnings("unchecked")
            GetResponse<DeadLetterDocument> response = mock(GetResponse.class);
            when(response.found()).thenReturn(true);
            when(response.source()).thenReturn(existing);
            when(openSearchClient.get(any(GetRequest.class), eq(DeadLetterDocument.class))).thenReturn(response);
        }

        @SuppressWarnings("unchecked")
        private DeadLetterDocument captureIndexed() throws Exception
        {
            ArgumentCaptor<IndexRequest<DeadLetterDocument>> captor = ArgumentCaptor.forClass(IndexRequest.class);
            verify(openSearchClient).index(captor.capture());
            return captor.getValue().document();
        }
    }

    @Nested
    @DisplayName("recordWindowFailure")
    class RecordWindowFailureTests
    {
        private static final long TS_FROM = 1_700_000_000_000L;
        private static final long TS_TO = 1_700_000_060_000L;

        @Test
        @DisplayName("writes a fresh READER document keyed by the window bounds")
        void writesFreshWindowDocument() throws Exception
        {
            stubGetMissForWindow();

            writer.recordWindowFailure(TS_FROM, TS_TO, new RuntimeException("bad row"));

            ArgumentCaptor<IndexRequest<DeadLetterDocument>> captor = capturingIndex();
            verify(openSearchClient).index(captor.capture());
            IndexRequest<DeadLetterDocument> request = captor.getValue();

            assertThat(request.id()).isEqualTo("window:" + TS_FROM + "-" + TS_TO);
            DeadLetterDocument doc = request.document();
            assertThat(doc.getDbId()).isNull();
            assertThat(doc.getNodeRef()).isNull();
            assertThat(doc.getSourceCommitTimeMs()).isEqualTo(TS_FROM);
            assertThat(doc.getFailureStage()).isEqualTo(FailureStage.READER);
            assertThat(doc.getFailureType()).isEqualTo("RuntimeException");
            assertThat(doc.getFailureReason()).contains(String.valueOf(TS_FROM)).contains(String.valueOf(TS_TO)).contains("bad row");
            assertThat(doc.getFailureCount()).isEqualTo(1);
            assertThat(doc.getFirstFailedAt()).isEqualTo(doc.getLastFailedAt());
        }

        @Test
        @DisplayName("merges repeat failures on the same window into one document with incrementing failureCount")
        void mergesRepeatWindowFailure() throws Exception
        {
            DeadLetterDocument existing = new DeadLetterDocument();
            existing.setSourceCommitTimeMs(TS_FROM);
            existing.setFirstFailedAt("2026-01-01T00:00:00Z");
            existing.setLastFailedAt("2026-01-01T00:00:00Z");
            existing.setFailureCount(2);
            existing.setFailureStage(FailureStage.READER);
            stubGetHitForWindow(existing);

            writer.recordWindowFailure(TS_FROM, TS_TO, new IllegalStateException("still bad"));

            ArgumentCaptor<IndexRequest<DeadLetterDocument>> captor = capturingIndex();
            verify(openSearchClient).index(captor.capture());
            DeadLetterDocument doc = captor.getValue().document();

            assertThat(doc.getFirstFailedAt()).isEqualTo("2026-01-01T00:00:00Z");
            assertThat(doc.getLastFailedAt()).isNotEqualTo("2026-01-01T00:00:00Z");
            assertThat(doc.getFailureCount()).isEqualTo(3);
            assertThat(doc.getFailureType()).isEqualTo("IllegalStateException");
        }

        @Test
        @DisplayName("swallows write failures so indexing never blocks on a broken DLQ")
        void swallowsIndexWriteFailure() throws Exception
        {
            stubGetMissForWindow();
            when(openSearchClient.index(any(IndexRequest.class))).thenThrow(new IOException("ES gone"));

            assertThatCode(() -> writer.recordWindowFailure(TS_FROM, TS_TO, new RuntimeException("x")))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("handles null cause without crashing")
        void handlesNullCause() throws Exception
        {
            stubGetMissForWindow();

            writer.recordWindowFailure(TS_FROM, TS_TO, null);

            ArgumentCaptor<IndexRequest<DeadLetterDocument>> captor = capturingIndex();
            verify(openSearchClient).index(captor.capture());
            DeadLetterDocument doc = captor.getValue().document();
            assertThat(doc.getFailureType()).isEqualTo("unknown");
            assertThat(doc.getFailureReason()).contains(String.valueOf(TS_FROM)).contains(String.valueOf(TS_TO));
        }

        private void stubGetMissForWindow() throws Exception
        {
            @SuppressWarnings("unchecked")
            GetResponse<DeadLetterDocument> response = mock(GetResponse.class);
            when(response.found()).thenReturn(false);
            when(openSearchClient.get(any(GetRequest.class), eq(DeadLetterDocument.class))).thenReturn(response);
        }

        private void stubGetHitForWindow(DeadLetterDocument existing) throws Exception
        {
            @SuppressWarnings("unchecked")
            GetResponse<DeadLetterDocument> response = mock(GetResponse.class);
            when(response.found()).thenReturn(true);
            when(response.source()).thenReturn(existing);
            when(openSearchClient.get(any(GetRequest.class), eq(DeadLetterDocument.class))).thenReturn(response);
        }

        @SuppressWarnings("unchecked")
        private ArgumentCaptor<IndexRequest<DeadLetterDocument>> capturingIndex()
        {
            return ArgumentCaptor.forClass(IndexRequest.class);
        }
    }

    @Nested
    @DisplayName("delete")
    class DeleteTests
    {
        @Test
        @DisplayName("deletes the document by id")
        void deletesById() throws Exception
        {
            writer.delete("42");

            ArgumentCaptor<DeleteRequest> captor = ArgumentCaptor.forClass(DeleteRequest.class);
            verify(openSearchClient).delete(captor.capture());
            assertThat(captor.getValue().index()).isEqualTo(INDEX_NAME);
            assertThat(captor.getValue().id()).isEqualTo("42");
        }

        @Test
        @DisplayName("swallows failures — a stale DLQ entry is safe, re-running the drain is idempotent")
        void swallowsFailures() throws Exception
        {
            when(openSearchClient.delete(any(DeleteRequest.class))).thenThrow(new IOException("ES gone"));

            assertThatCode(() -> writer.delete("42")).doesNotThrowAnyException();
        }
    }
}
