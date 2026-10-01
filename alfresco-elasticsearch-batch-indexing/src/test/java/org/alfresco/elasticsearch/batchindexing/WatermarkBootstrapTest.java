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

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

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
import org.opensearch.client.opensearch._types.aggregations.Aggregate;
import org.opensearch.client.opensearch._types.aggregations.MaxAggregate;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.indices.ExistsRequest;
import org.opensearch.client.opensearch.indices.OpenSearchIndicesClient;
import org.opensearch.client.transport.endpoints.BooleanResponse;

import org.alfresco.database.connector.AlfrescoMetadataRepository;
import org.alfresco.elasticsearch.batchindexing.config.ContinuousReindexingProperties;
import org.alfresco.elasticsearch.batchindexing.model.WatermarkDocument;
import org.alfresco.indexing.shared.ElasticsearchProperties;

/**
 * Bootstrap-level coverage for the three opt-in startup guards ({@code autoCreateStateIndex}, {@code bootstrapFromAlfrescoIndex}, {@code requireAlfrescoIndex}). The persistence-side contracts (write OCC, read 404 handling, missing-doc/missing-index recovery on write) are covered by {@link ElasticsearchWatermarkStoreTest}.
 */
@ExtendWith(MockitoExtension.class)
@SuppressWarnings("PMD.SignatureDeclareThrowsException")
class WatermarkBootstrapTest
{
    private static final String STATE_INDEX = "test-reindex-state";
    private static final String ALFRESCO_INDEX = "alfresco";

    @Mock
    private ElasticsearchWatermarkStore store;
    @Mock
    private OpenSearchClient openSearchClient;
    @Mock
    private AlfrescoMetadataRepository metadataRepository;

    private ContinuousReindexingProperties properties;
    private ElasticsearchProperties elasticsearchProperties;

    @BeforeEach
    void setUp()
    {
        properties = new ContinuousReindexingProperties();
        properties.setWatermarkIndexName(STATE_INDEX);
        properties.setOverlap(Duration.ofMinutes(10));
        elasticsearchProperties = new ElasticsearchProperties();
        elasticsearchProperties.setIndexName(ALFRESCO_INDEX);
    }

    // ---------------------------------------------------------------------
    // State-index guard (autoCreateStateIndex)
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("State-index guard (autoCreateStateIndex)")
    class StateIndexGuard
    {
        @Test
        @DisplayName("autoCreateStateIndex=true skips the pre-check and ensures the index")
        void auto_create_true_ensures_index() throws Exception
        {
            properties.setAutoCreateStateIndex(true);
            properties.setBootstrapFromAlfrescoIndex(false);
            properties.setRequireAlfrescoIndex(false);
            when(store.read()).thenReturn(Optional.of(watermarkAt(1234L)));

            new WatermarkBootstrap(store, openSearchClient, properties, elasticsearchProperties, metadataRepository).bootstrap();

            verify(store).ensureIndexExists();
            verify(store, never()).indexExists();
        }

        @Test
        @DisplayName("autoCreateStateIndex=false with existing index passes")
        void auto_create_false_passes_when_index_exists() throws Exception
        {
            properties.setAutoCreateStateIndex(false);
            properties.setBootstrapFromAlfrescoIndex(false);
            properties.setRequireAlfrescoIndex(false);
            when(store.indexExists()).thenReturn(true);
            when(store.read()).thenReturn(Optional.of(watermarkAt(1234L)));

            new WatermarkBootstrap(store, openSearchClient, properties, elasticsearchProperties, metadataRepository).bootstrap();

            verify(store).indexExists();
            verify(store).ensureIndexExists();
        }

        @Test
        @DisplayName("autoCreateStateIndex=false with missing index refuses to start (throws with actionable message)")
        void auto_create_false_refuses_when_index_missing() throws Exception
        {
            properties.setAutoCreateStateIndex(false);
            when(store.indexExists()).thenReturn(false);

            assertThatThrownBy(() -> new WatermarkBootstrap(store, openSearchClient, properties, elasticsearchProperties, metadataRepository).bootstrap())
                    .isInstanceOf(WatermarkAccessException.class)
                    .hasMessageContaining("Refusing to start")
                    .hasMessageContaining(STATE_INDEX)
                    .hasMessageContaining("autoCreateStateIndex");

            verify(store, never()).ensureIndexExists();
        }
    }

    // ---------------------------------------------------------------------
    // Cursor bootstrap from Alfresco index (bootstrapFromAlfrescoIndex)
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("Cursor bootstrap from Alfresco index (bootstrapFromAlfrescoIndex)")
    class BootstrapFromAlfrescoIndex
    {
        @Test
        @DisplayName("state doc missing + alfresco has data -> seeds cursor at max - overlap with BOOTSTRAP status")
        void seeds_from_max_when_state_missing() throws Exception
        {
            properties.setAutoCreateStateIndex(true);
            properties.setBootstrapFromAlfrescoIndex(true);
            properties.setRequireAlfrescoIndex(false);
            properties.setOverlap(Duration.ofMinutes(10));

            when(store.read()).thenReturn(Optional.empty());
            simulateAlfrescoMax(1_500_000_000_000L);

            new WatermarkBootstrap(store, openSearchClient, properties, elasticsearchProperties, metadataRepository).bootstrap();

            ArgumentCaptor<WatermarkDocument> captor = ArgumentCaptor.forClass(WatermarkDocument.class);
            verify(store).write(captor.capture());
            WatermarkDocument seed = captor.getValue();
            long expected = 1_500_000_000_000L - Duration.ofMinutes(10).toMillis();
            assertThat(seed.getLastSuccessfulToTimeEpochMs()).isEqualTo(expected);
            assertThat(seed.getLastRunStatus()).isEqualTo(WatermarkBootstrap.BOOTSTRAP_STATUS);
        }

        @Test
        @DisplayName("clamps the seeded cursor to a positive value when overlap exceeds the Alfresco max")
        void clampsSeedCursorWhenOverlapExceedsAlfrescoMax() throws Exception
        {
            properties.setAutoCreateStateIndex(true);
            properties.setBootstrapFromAlfrescoIndex(true);
            properties.setRequireAlfrescoIndex(false);
            // 36500d with alfrescomax will make max - overlap negative.
            properties.setOverlap(Duration.ofDays(36500));

            when(store.read()).thenReturn(Optional.empty());
            simulateAlfrescoMax(1_500_000_000_000L);

            new WatermarkBootstrap(store, openSearchClient, properties, elasticsearchProperties, metadataRepository).bootstrap();

            ArgumentCaptor<WatermarkDocument> captor = ArgumentCaptor.forClass(WatermarkDocument.class);
            verify(store).write(captor.capture());

            assertThat(captor.getValue().getLastSuccessfulToTimeEpochMs())
                    .isEqualTo(WatermarkBootstrap.MIN_SEED_CURSOR_MS);
        }

        @Test
        @DisplayName("state doc missing + alfresco empty -> no seed, falls through to first-run bootstrap")
        void skips_when_alfresco_returns_no_value() throws Exception
        {
            properties.setBootstrapFromAlfrescoIndex(true);
            when(store.read()).thenReturn(Optional.empty());
            simulateAlfrescoMaxAggregate(Double.NaN);

            new WatermarkBootstrap(store, openSearchClient, properties, elasticsearchProperties, metadataRepository).bootstrap();

            verify(store, never()).write(any());
        }

        @Test
        @DisplayName("state doc missing + alfresco max is null (no docs / no field) -> no seed, no NPE")
        void skips_when_alfresco_max_value_is_null() throws Exception
        {
            // MaxAggregate.value() returns a nullable Double: null when the index has no docs at all,
            // or has docs but none carry the aggregated field. Unboxing without a guard NPEs in prod.
            properties.setBootstrapFromAlfrescoIndex(true);
            when(store.read()).thenReturn(Optional.empty());
            simulateAlfrescoMaxAggregate(null);

            assertThatCode(() -> new WatermarkBootstrap(store, openSearchClient, properties, elasticsearchProperties, metadataRepository).bootstrap())
                    .doesNotThrowAnyException();

            verify(store, never()).write(any());
        }

        @Test
        @DisplayName("state doc missing + alfresco index missing (404) -> no seed, falls through")
        void skips_when_alfresco_index_missing() throws Exception
        {
            properties.setBootstrapFromAlfrescoIndex(true);
            when(store.read()).thenReturn(Optional.empty());

            OpenSearchException notFound = mock(OpenSearchException.class);
            when(notFound.status()).thenReturn(404);
            when(openSearchClient.search(any(SearchRequest.class), eq(Void.class))).thenThrow(notFound);

            new WatermarkBootstrap(store, openSearchClient, properties, elasticsearchProperties, metadataRepository).bootstrap();

            verify(store, never()).write(any());
        }

        @Test
        @DisplayName("state doc present -> skip seed")
        void skips_when_state_doc_exists() throws Exception
        {
            properties.setBootstrapFromAlfrescoIndex(true);
            when(store.read()).thenReturn(Optional.of(watermarkAt(999L)));

            new WatermarkBootstrap(store, openSearchClient, properties, elasticsearchProperties, metadataRepository).bootstrap();

            verifyNoInteractions(openSearchClient);
            verify(store, never()).write(any());
        }

        @Test
        @DisplayName("bootstrapFromAlfrescoIndex=false -> skip seed even when state doc missing")
        void skips_when_flag_off() throws Exception
        {
            properties.setBootstrapFromAlfrescoIndex(false);
            when(store.read()).thenReturn(Optional.empty());

            new WatermarkBootstrap(store, openSearchClient, properties, elasticsearchProperties, metadataRepository).bootstrap();

            verifyNoInteractions(openSearchClient);
            verify(store, never()).write(any());
        }

        @Test
        @DisplayName("alfresco index returns a non-404 error -> propagates as WatermarkAccessException (no silent fallback)")
        void propagates_alfresco_cluster_error() throws Exception
        {
            properties.setBootstrapFromAlfrescoIndex(true);
            when(store.read()).thenReturn(Optional.empty());
            OpenSearchException clusterError = mock(OpenSearchException.class);
            when(clusterError.status()).thenReturn(503);
            when(openSearchClient.search(any(SearchRequest.class), eq(Void.class))).thenThrow(clusterError);

            assertThatThrownBy(() -> new WatermarkBootstrap(store, openSearchClient, properties, elasticsearchProperties, metadataRepository).bootstrap())
                    .isInstanceOf(WatermarkAccessException.class)
                    .hasMessageContaining("for cursor bootstrap");
        }

        @Test
        @DisplayName("seed write hits WatermarkConflictException -> logged and swallowed (another instance won)")
        void swallows_seed_conflict() throws Exception
        {
            properties.setBootstrapFromAlfrescoIndex(true);
            when(store.read()).thenReturn(Optional.empty());
            simulateAlfrescoMax(2_000_000_000_000L);
            doThrow(new WatermarkConflictException("someone else seeded", null))
                    .when(store).write(any());

            assertThatCode(() -> new WatermarkBootstrap(store, openSearchClient, properties, elasticsearchProperties, metadataRepository).bootstrap())
                    .doesNotThrowAnyException();
        }
    }

    // ---------------------------------------------------------------------
    // Alfresco-index presence guard (requireAlfrescoIndex)
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("Alfresco-index presence guard (requireAlfrescoIndex)")
    class AlfrescoIndexPresenceGuard
    {
        @Test
        @DisplayName("requireAlfrescoIndex=true + state has real cursor + alfresco exists -> passes")
        void passes_when_alfresco_index_exists() throws Exception
        {
            properties.setRequireAlfrescoIndex(true);
            properties.setBootstrapFromAlfrescoIndex(false);
            when(store.read()).thenReturn(Optional.of(watermarkAt(1_000_000L)));
            OpenSearchIndicesClient indices = mock(OpenSearchIndicesClient.class);
            when(openSearchClient.indices()).thenReturn(indices);
            when(indices.exists(any(ExistsRequest.class))).thenReturn(new BooleanResponse(true));

            new WatermarkBootstrap(store, openSearchClient, properties, elasticsearchProperties, metadataRepository).bootstrap();

            verify(indices).exists(any(ExistsRequest.class));
        }

        @Test
        @DisplayName("requireAlfrescoIndex=true + state has real cursor + alfresco missing -> refuses to start")
        void refuses_when_alfresco_index_missing() throws Exception
        {
            properties.setRequireAlfrescoIndex(true);
            properties.setBootstrapFromAlfrescoIndex(false);
            when(store.read()).thenReturn(Optional.of(watermarkAt(1_000_000L)));
            OpenSearchIndicesClient indices = mock(OpenSearchIndicesClient.class);
            when(openSearchClient.indices()).thenReturn(indices);
            when(indices.exists(any(ExistsRequest.class))).thenReturn(new BooleanResponse(false));

            assertThatThrownBy(() -> new WatermarkBootstrap(store, openSearchClient, properties, elasticsearchProperties, metadataRepository).bootstrap())
                    .isInstanceOf(WatermarkAccessException.class)
                    .hasMessageContaining("Refusing to start")
                    .hasMessageContaining(ALFRESCO_INDEX)
                    .hasMessageContaining("requireAlfrescoIndex");
        }

        @Test
        @DisplayName("state doc absent -> guard does not apply (fresh install path)")
        void skips_when_state_doc_absent() throws Exception
        {
            properties.setRequireAlfrescoIndex(true);
            properties.setBootstrapFromAlfrescoIndex(false);
            when(store.read()).thenReturn(Optional.empty());

            new WatermarkBootstrap(store, openSearchClient, properties, elasticsearchProperties, metadataRepository).bootstrap();

            verifyNoInteractions(openSearchClient);
        }

        @Test
        @DisplayName("state doc with sentinel cursor (=1) -> guard does not apply")
        void skips_when_state_cursor_is_sentinel() throws Exception
        {
            properties.setRequireAlfrescoIndex(true);
            properties.setBootstrapFromAlfrescoIndex(false);
            when(store.read()).thenReturn(Optional.of(watermarkAt(1L)));

            new WatermarkBootstrap(store, openSearchClient, properties, elasticsearchProperties, metadataRepository).bootstrap();

            verifyNoInteractions(openSearchClient);
        }

        @Test
        @DisplayName("requireAlfrescoIndex=false -> skip check")
        void skips_when_flag_off() throws Exception
        {
            properties.setRequireAlfrescoIndex(false);
            properties.setBootstrapFromAlfrescoIndex(false);
            when(store.read()).thenReturn(Optional.of(watermarkAt(1_000_000L)));

            new WatermarkBootstrap(store, openSearchClient, properties, elasticsearchProperties, metadataRepository).bootstrap();

            verifyNoInteractions(openSearchClient);
        }
    }

    // ---------------------------------------------------------------------
    // Smart-seed (seedFromEarliest)
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("Smart-seed (seedFromEarliest)")
    class SmartSeed
    {
        @Test
        @DisplayName("state doc missing + DB has nodes -> seeds cursor at min - overlap with SEED_FROM_EARLIEST status")
        void seeds_from_earliest_when_state_missing() throws Exception
        {
            properties.setSeedFromEarliest(true);
            properties.setOverlap(Duration.ofMinutes(10));
            when(store.read()).thenReturn(Optional.empty());
            when(metadataRepository.getMinCommitTimeMs()).thenReturn(1_000_000_000_000L);

            new WatermarkBootstrap(store, openSearchClient, properties, elasticsearchProperties, metadataRepository).bootstrap();

            ArgumentCaptor<WatermarkDocument> captor = ArgumentCaptor.forClass(WatermarkDocument.class);
            verify(store).write(captor.capture());
            WatermarkDocument seed = captor.getValue();
            long expected = 1_000_000_000_000L - Duration.ofMinutes(10).toMillis();
            assertThat(seed.getLastSuccessfulToTimeEpochMs()).isEqualTo(expected);
            assertThat(seed.getLastRunStatus()).isEqualTo(WatermarkBootstrap.SEED_FROM_EARLIEST_STATUS);
            verifyNoInteractions(openSearchClient);
        }

        @Test
        @DisplayName("clamps the seeded cursor to a positive sentinel when overlap exceeds the earliest commit time")
        void clampsSeedCursorWhenOverlapExceedsMin() throws Exception
        {
            properties.setSeedFromEarliest(true);
            properties.setOverlap(Duration.ofMinutes(10));
            when(store.read()).thenReturn(Optional.empty());
            // Earliest commit is smaller than the overlap, so min - overlap would be negative.
            when(metadataRepository.getMinCommitTimeMs()).thenReturn(1000L);

            new WatermarkBootstrap(store, openSearchClient, properties, elasticsearchProperties, metadataRepository).bootstrap();

            ArgumentCaptor<WatermarkDocument> captor = ArgumentCaptor.forClass(WatermarkDocument.class);
            verify(store).write(captor.capture());
            // Clamped to the positive sentinel so computeWindowStart still respects it (a <=0 cursor reads as "no watermark").
            assertThat(captor.getValue().getLastSuccessfulToTimeEpochMs())
                    .isEqualTo(WatermarkBootstrap.MIN_SEED_CURSOR_MS);
        }

        @Test
        @DisplayName("takes priority over bootstrapFromAlfrescoIndex when both are enabled")
        void takesPriorityOverBootstrapFromAlfrescoIndex() throws Exception
        {
            properties.setSeedFromEarliest(true);
            properties.setBootstrapFromAlfrescoIndex(true);
            when(store.read()).thenReturn(Optional.empty());
            when(metadataRepository.getMinCommitTimeMs()).thenReturn(1_000_000_000_000L);

            new WatermarkBootstrap(store, openSearchClient, properties, elasticsearchProperties, metadataRepository).bootstrap();

            verify(store).write(any());
            verifyNoInteractions(openSearchClient);
        }

        @Test
        @DisplayName("DB has no nodes -> falls through to bootstrapFromAlfrescoIndex")
        void fallsThroughToBootstrapFromAlfrescoIndexWhenDbEmpty() throws Exception
        {
            properties.setSeedFromEarliest(true);
            properties.setBootstrapFromAlfrescoIndex(true);
            when(store.read()).thenReturn(Optional.empty());
            when(metadataRepository.getMinCommitTimeMs()).thenReturn(null);
            simulateAlfrescoMax(2_000_000_000_000L);

            new WatermarkBootstrap(store, openSearchClient, properties, elasticsearchProperties, metadataRepository).bootstrap();

            ArgumentCaptor<WatermarkDocument> captor = ArgumentCaptor.forClass(WatermarkDocument.class);
            verify(store).write(captor.capture());
            assertThat(captor.getValue().getLastRunStatus()).isEqualTo(WatermarkBootstrap.BOOTSTRAP_STATUS);
        }

        @Test
        @DisplayName("DB has no nodes + bootstrapFromAlfrescoIndex off -> no seed at all")
        void noSeedWhenBothOptionsExhausted() throws Exception
        {
            properties.setSeedFromEarliest(true);
            properties.setBootstrapFromAlfrescoIndex(false);
            when(store.read()).thenReturn(Optional.empty());
            when(metadataRepository.getMinCommitTimeMs()).thenReturn(null);

            new WatermarkBootstrap(store, openSearchClient, properties, elasticsearchProperties, metadataRepository).bootstrap();

            verify(store, never()).write(any());
            verifyNoInteractions(openSearchClient);
        }

        @Test
        @DisplayName("seedFromEarliest=false -> skip, even when the DB has nodes")
        void skipsWhenFlagOff() throws Exception
        {
            properties.setSeedFromEarliest(false);
            properties.setBootstrapFromAlfrescoIndex(false);
            when(store.read()).thenReturn(Optional.empty());

            new WatermarkBootstrap(store, openSearchClient, properties, elasticsearchProperties, metadataRepository).bootstrap();

            verifyNoInteractions(metadataRepository);
            verify(store, never()).write(any());
        }

        @Test
        @DisplayName("state doc present -> skip seed")
        void skipsWhenStateDocExists() throws Exception
        {
            properties.setSeedFromEarliest(true);
            when(store.read()).thenReturn(Optional.of(watermarkAt(999L)));

            new WatermarkBootstrap(store, openSearchClient, properties, elasticsearchProperties, metadataRepository).bootstrap();

            verifyNoInteractions(metadataRepository);
            verify(store, never()).write(any());
        }

        @Test
        @DisplayName("seed write hits WatermarkConflictException -> logged and swallowed, does not also try bootstrapFromAlfrescoIndex")
        void swallowsSeedConflictWithoutFallingThrough() throws Exception
        {
            properties.setSeedFromEarliest(true);
            properties.setBootstrapFromAlfrescoIndex(true);
            when(store.read()).thenReturn(Optional.empty());
            when(metadataRepository.getMinCommitTimeMs()).thenReturn(1_000_000_000_000L);
            doThrow(new WatermarkConflictException("someone else seeded", null)).when(store).write(any());

            assertThatCode(() -> new WatermarkBootstrap(store, openSearchClient, properties, elasticsearchProperties, metadataRepository).bootstrap())
                    .doesNotThrowAnyException();

            verifyNoInteractions(openSearchClient);
        }

        @Test
        @DisplayName("metadata repository query failure propagates as WatermarkAccessException")
        void propagatesQueryFailure() throws Exception
        {
            properties.setSeedFromEarliest(true);
            when(store.read()).thenReturn(Optional.empty());
            when(metadataRepository.getMinCommitTimeMs()).thenThrow(new RuntimeException("db down"));

            assertThatThrownBy(() -> new WatermarkBootstrap(store, openSearchClient, properties, elasticsearchProperties, metadataRepository).bootstrap())
                    .isInstanceOf(WatermarkAccessException.class)
                    .hasMessageContaining("earliest node commit time");
        }
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private static WatermarkDocument watermarkAt(long cursor)
    {
        WatermarkDocument w = new WatermarkDocument();
        w.setLastSuccessfulToTimeEpochMs(cursor);
        return w;
    }

    private void simulateAlfrescoMax(long maxValue) throws Exception
    {
        simulateAlfrescoMaxAggregate(Double.valueOf(maxValue));
    }

    @SuppressWarnings("unchecked")
    private void simulateAlfrescoMaxAggregate(Double value) throws Exception
    {
        MaxAggregate max = mock(MaxAggregate.class);
        when(max.value()).thenReturn(value);
        Aggregate aggregate = mock(Aggregate.class);
        when(aggregate.isMax()).thenReturn(true);
        when(aggregate.max()).thenReturn(max);
        SearchResponse<Void> response = mock(SearchResponse.class);
        when(response.aggregations()).thenReturn(Map.of(WatermarkBootstrap.MAX_AGG_NAME, aggregate));
        when(openSearchClient.search(any(SearchRequest.class), eq(Void.class))).thenReturn(response);
    }
}
