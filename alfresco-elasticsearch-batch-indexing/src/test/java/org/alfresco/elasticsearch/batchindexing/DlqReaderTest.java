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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.ErrorCause;
import org.opensearch.client.opensearch._types.ErrorResponse;
import org.opensearch.client.opensearch._types.OpenSearchException;
import org.opensearch.client.opensearch._types.SortOrder;
import org.opensearch.client.opensearch.core.CountResponse;
import org.opensearch.client.opensearch.core.MgetRequest;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.core.search.Hit;
import org.opensearch.client.opensearch.core.search.HitsMetadata;
import org.opensearch.client.opensearch.core.search.TotalHits;
import org.opensearch.client.opensearch.core.search.TotalHitsRelation;

import org.alfresco.elasticsearch.batchindexing.DlqReader.DlqEntry;
import org.alfresco.elasticsearch.batchindexing.DlqReader.DlqScanException;
import org.alfresco.elasticsearch.batchindexing.model.DeadLetterDocument;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings({"PMD.SignatureDeclareThrowsException", "PMD.CouplingBetweenObjects"})
class DlqReaderTest
{
    private static final String INDEX_NAME = "test-dead-letter";

    @Mock
    private OpenSearchClient openSearchClient;

    private DlqReader reader;

    @BeforeEach
    void setUp()
    {
        reader = new DlqReader(openSearchClient, INDEX_NAME);
    }

    @Nested
    @DisplayName("scanEntries")
    @SuppressWarnings("unchecked")
    class ScanEntriesTests
    {
        @Test
        @DisplayName("queries node-level only, sorted oldest-tried first, and maps hits to entries")
        void queriesNodeLevelSortedAndMapsHits() throws Exception
        {
            DeadLetterDocument nodeDoc = new DeadLetterDocument();
            nodeDoc.setDbId(42L);
            stubSearch(List.of(hit("42", nodeDoc)));

            List<DlqEntry> entries = reader.scanEntries(100);

            assertThat(entries).singleElement().satisfies(e -> {
                assertThat(e.documentId()).isEqualTo("42");
                assertThat(e.document().getDbId()).isEqualTo(42L);
            });

            ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
            verify(openSearchClient).search(captor.capture(), eq(DeadLetterDocument.class));
            SearchRequest request = captor.getValue();
            // Node-level filter: window-level records (no dbId) are excluded from the drain.
            assertThat(request.query()).isNotNull();
            assertThat(request.query().isExists()).isTrue();
            assertThat(request.query().exists().field()).isEqualTo("dbId");
            // Rotation: oldest-tried first — failureCount asc primary, sourceCommitTimeMs asc tiebreak.
            assertThat(request.sort()).hasSize(2);
            assertThat(request.sort().get(0).field().field()).isEqualTo("failureCount");
            assertThat(request.sort().get(0).field().order()).isEqualTo(SortOrder.Asc);
        }

        @Test
        @DisplayName("logs but does not throw when the hit count reaches maxItems")
        void doesNotThrowWhenCapped() throws Exception
        {
            DeadLetterDocument doc = new DeadLetterDocument();
            doc.setDbId(1L);
            stubSearch(List.of(hit("1", doc)));

            assertThatCode(() -> reader.scanEntries(1)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("returns empty list when the DLQ index doesn't exist yet")
        void returnsEmptyWhenIndexMissing() throws Exception
        {
            ErrorCause errorCause = mock(ErrorCause.class);
            ErrorResponse errorResponse = mock(ErrorResponse.class);
            when(errorResponse.status()).thenReturn(404);
            when(errorResponse.error()).thenReturn(errorCause);
            OpenSearchException notFound = new OpenSearchException(errorResponse);
            when(openSearchClient.search(any(SearchRequest.class), eq(DeadLetterDocument.class)))
                    .thenThrow(notFound);

            List<DlqEntry> entries = reader.scanEntries(100);

            assertThat(entries).isEmpty();
        }

        @Test
        @DisplayName("throws DlqScanException on a non-404 failure, rather than silently reporting nothing to drain")
        void throwsOnScanFailure() throws Exception
        {
            when(openSearchClient.search(any(SearchRequest.class), eq(DeadLetterDocument.class)))
                    .thenThrow(new IOException("cluster unreachable"));

            assertThatThrownBy(() -> reader.scanEntries(100))
                    .isInstanceOf(DlqScanException.class);
        }

        private Hit<DeadLetterDocument> hit(String id, DeadLetterDocument document)
        {
            Hit<DeadLetterDocument> hit = mock(Hit.class);
            when(hit.id()).thenReturn(id);
            when(hit.source()).thenReturn(document);
            return hit;
        }

        private void stubSearch(List<Hit<DeadLetterDocument>> hits) throws Exception
        {
            HitsMetadata<DeadLetterDocument> hitsMetadata = mock(HitsMetadata.class);
            when(hitsMetadata.hits()).thenReturn(hits);
            SearchResponse<DeadLetterDocument> response = mock(SearchResponse.class);
            when(response.hits()).thenReturn(hitsMetadata);
            when(openSearchClient.search(any(SearchRequest.class), eq(DeadLetterDocument.class))).thenReturn(response);
        }
    }

    @Nested
    @DisplayName("scanMayHaveMoreEntries")
    @SuppressWarnings({"unchecked", "rawtypes"})
    class ScanMayHaveMoreEntriesTests
    {
        @Test
        @DisplayName("false when fewer hits than the cap were returned")
        void falseBelowCap()
        {
            assertThat(DlqReader.scanMayHaveMoreEntries(mockHits(3, totalOf(3, TotalHitsRelation.Eq)), 10)).isFalse();
        }

        @Test
        @DisplayName("false when the cap is reached but the exact total equals the cap (no false positive)")
        void falseWhenTotalExactlyEqualsCap()
        {
            assertThat(DlqReader.scanMayHaveMoreEntries(mockHits(10, totalOf(10, TotalHitsRelation.Eq)), 10)).isFalse();
        }

        @Test
        @DisplayName("true when the cap is reached and the exact total exceeds it")
        void trueWhenTotalExceedsCap()
        {
            assertThat(DlqReader.scanMayHaveMoreEntries(mockHits(10, totalOf(25, TotalHitsRelation.Eq)), 10)).isTrue();
        }

        @Test
        @DisplayName("true when the cap is reached and the total is a lower bound (Gte, capped by track_total_hits)")
        void trueWhenTotalIsLowerBound()
        {
            assertThat(DlqReader.scanMayHaveMoreEntries(mockHits(10, totalOf(10, TotalHitsRelation.Gte)), 10)).isTrue();
        }

        @Test
        @DisplayName("true when the cap is reached and no total is reported")
        void trueWhenTotalUnavailable()
        {
            assertThat(DlqReader.scanMayHaveMoreEntries(mockHits(10, null), 10)).isTrue();
        }

        private HitsMetadata mockHits(int returnedSize, TotalHits total)
        {
            HitsMetadata hits = mock(HitsMetadata.class);
            when(hits.hits()).thenReturn(Collections.nCopies(returnedSize, mock(Hit.class)));
            // Not consulted on the below-cap short-circuit, so keep it lenient.
            lenient().when(hits.total()).thenReturn(total);
            return hits;
        }

        private TotalHits totalOf(long value, TotalHitsRelation relation)
        {
            return TotalHits.of(t -> t.value(value).relation(relation));
        }
    }

    @Nested
    @DisplayName("countWindowLevelEntries")
    class CountWindowLevelEntriesTests
    {
        @Test
        @DisplayName("returns the count reported by OpenSearch")
        @SuppressWarnings("unchecked")
        void returnsCount() throws Exception
        {
            CountResponse response = mock(CountResponse.class);
            when(response.count()).thenReturn(3L);
            when(openSearchClient.count(any(Function.class))).thenReturn(response);

            assertThat(reader.countWindowLevelEntries()).isEqualTo(3L);
        }

        @Test
        @DisplayName("best-effort: returns 0 and never throws when the count fails")
        @SuppressWarnings("unchecked")
        void returnsZeroOnFailure() throws Exception
        {
            when(openSearchClient.count(any(Function.class))).thenThrow(new IOException("cluster down"));

            assertThat(reader.countWindowLevelEntries()).isZero();
        }
    }

    @Nested
    @DisplayName("readByIds")
    class ReadByIdsTests
    {
        @Test
        @DisplayName("returns an empty map for empty input without querying")
        void emptyInputShortCircuits() throws Exception
        {
            assertThat(reader.readByIds(List.of())).isEmpty();

            verify(openSearchClient, never()).mget(any(MgetRequest.class), eq(DeadLetterDocument.class));
        }
    }
}
