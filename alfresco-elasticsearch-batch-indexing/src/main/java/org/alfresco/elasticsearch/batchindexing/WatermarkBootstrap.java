/*-
 * #%L
 * Alfresco Search Community :: Batch Indexing
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

import java.time.Duration;
import java.util.Optional;

import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.OpenSearchException;
import org.opensearch.client.opensearch._types.aggregations.Aggregate;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.indices.ExistsRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.alfresco.database.connector.AlfrescoMetadataRepository;
import org.alfresco.elasticsearch.batchindexing.config.ContinuousReindexingProperties;
import org.alfresco.elasticsearch.batchindexing.model.WatermarkDocument;
import org.alfresco.indexing.shared.ElasticsearchProperties;

/**
 * One-shot startup guard and cursor seed for the batch-indexing watermark. Bundles three opt-in behaviours so the {@link ContinuousReindexingConfig} bean wiring reads like a single call:
 *
 * <ul>
 * <li>{@link ContinuousReindexingProperties#isAutoCreateStateIndex()} — when {@code false}, refuse to start on a missing state index instead of silently bootstrapping fresh.</li>
 * <li>{@link ContinuousReindexingProperties#isBootstrapFromAlfrescoIndex()} — when {@code true} and the state doc is missing, seed the cursor from {@code MAX(METADATA_INDEXING_LAST_UPDATE)} on the Alfresco index instead of the "start from {@code now − overlap}" first-run default.</li>
 * <li>{@link ContinuousReindexingProperties#isRequireAlfrescoIndex()} — when {@code true} and the state doc records real indexing progress, refuse to start if the Alfresco index is missing (silent data loss guard).</li>
 * </ul>
 *
 * All three are opt-in: leaving the flags at their defaults means no functional change for existing installs.
 */
public class WatermarkBootstrap
{
    private static final Logger LOGGER = LoggerFactory.getLogger(WatermarkBootstrap.class);

    static final String BOOTSTRAP_STATUS = "BOOTSTRAPPED_FROM_ALFRESCO_INDEX";
    static final String SEED_FROM_EARLIEST_STATUS = "SEEDED_FROM_EARLIEST_NODE";
    static final String METADATA_TIMESTAMP_FIELD = "METADATA_INDEXING_LAST_UPDATE";
    static final String MAX_AGG_NAME = "max_cursor";

    /**
     * Floor for a seeded cursor. Keeps it a positive, respected watermark even when {@code overlap} exceeds the source timestamp — {@link ContinuousReindexingService#computeWindowStart} treats {@code <= 0} as "no watermark" and would fall back to {@code now}, silently skipping the backfill. Above the {@code 1} sentinel so it still reads as real progress.
     */
    static final long MIN_SEED_CURSOR_MS = 2L;

    private final ElasticsearchWatermarkStore store;
    private final OpenSearchClient openSearchClient;
    private final ContinuousReindexingProperties properties;
    private final ElasticsearchProperties elasticsearchProperties;
    private final AlfrescoMetadataRepository metadataRepository;

    public WatermarkBootstrap(ElasticsearchWatermarkStore store,
            OpenSearchClient openSearchClient,
            ContinuousReindexingProperties properties,
            ElasticsearchProperties elasticsearchProperties,
            AlfrescoMetadataRepository metadataRepository)
    {
        this.store = store;
        this.openSearchClient = openSearchClient;
        this.properties = properties;
        this.elasticsearchProperties = elasticsearchProperties;
        this.metadataRepository = metadataRepository;
    }

    /**
     * Run the startup dance: enforce the state-index guard, ensure the state index exists, seed the cursor from the Alfresco index if applicable, enforce the Alfresco-index presence guard. Any failure aborts startup by throwing.
     */
    public void bootstrap() throws WatermarkAccessException
    {
        enforceStateIndexGuard();
        store.ensureIndexExists();
        Optional<WatermarkDocument> existing = store.read();

        if (existing.isEmpty())
        {
            boolean seeded = properties.isSeedFromEarliest() && seedFromEarliestNode();
            if (!seeded && properties.isBootstrapFromAlfrescoIndex())
            {
                seedFromAlfrescoIndex();
            }
        }

        enforceAlfrescoIndexPresenceGuard(existing);
    }

    private void enforceStateIndexGuard() throws WatermarkAccessException
    {
        if (properties.isAutoCreateStateIndex())
        {
            return;
        }
        boolean exists = store.indexExists();
        if (!exists)
        {
            throw new WatermarkAccessException(
                    "Refusing to start: watermark state index '" + properties.getWatermarkIndexName()
                            + "' does not exist and alfresco.reindex.continuous.autoCreateStateIndex=false. "
                            + "Restore the index from backup or seed a watermark doc manually before starting the "
                            + "batch-indexer, or set autoCreateStateIndex=true to bootstrap fresh.");
        }
        LOGGER.info("State-index guard passed: '{}' exists (autoCreateStateIndex=false).",
                properties.getWatermarkIndexName());
    }

    /**
     * On missing state doc, seed the cursor from the Alfresco index's most recent commit timestamp instead of falling through to {@code [now − overlap, now]}.
     *
     * <p>
     * Falls through silently to the first-run bootstrap when the aggregation returns no value (empty or missing Alfresco index). Propagates on cluster errors so the outer cycle backoff retries — silently defaulting to first-run bootstrap would defeat the point.
     */
    private void seedFromAlfrescoIndex() throws WatermarkAccessException
    {
        Long maxCursor = queryAlfrescoMaxCursor();
        if (maxCursor == null)
        {
            LOGGER.info("Cursor bootstrap from Alfresco index skipped: '{}' has no {} value (empty or missing). "
                    + "Falling through to first-run bootstrap.",
                    elasticsearchProperties.getIndexName(), METADATA_TIMESTAMP_FIELD);
            return;
        }
        long overlapMs = safeOverlapMs();
        long bootstrappedCursor = Math.max(maxCursor - overlapMs, MIN_SEED_CURSOR_MS);
        WatermarkDocument seed = new WatermarkDocument();
        seed.setLastSuccessfulToTimeEpochMs(bootstrappedCursor);
        seed.setLastRunStatus(BOOTSTRAP_STATUS);
        try
        {
            store.write(seed);
        }
        catch (WatermarkConflictException conflict)
        {
            LOGGER.warn("Cursor bootstrap from Alfresco index lost a race to another writer — someone else wrote "
                    + "the state doc between our probe and our seed. Not fatal; falling through to normal cycle "
                    + "behaviour. {}", conflict.getMessage());
            return;
        }
        LOGGER.info("Cursor bootstrapped from Alfresco index: seeded watermark from MAX({}) on '{}' — max={}, overlapMs={}, cursor={}. "
                + "Next cycle will scan [{}, now].",
                METADATA_TIMESTAMP_FIELD, elasticsearchProperties.getIndexName(),
                maxCursor, overlapMs, bootstrappedCursor, bootstrappedCursor);
    }

    /**
     * Smart-seed: on missing state doc, seed the cursor from the <em>DB's</em> earliest node commit time instead of the ES-max-based {@link #seedFromAlfrescoIndex()}, so normal catch-up walks forward and backfills everything — pair with {@code maxGapAge=0} for a full backfill.
     *
     * @return {@code true} if a seed was written (including a lost race — still "handled"), {@code false} if the database has no nodes and the caller should fall through to {@link #seedFromAlfrescoIndex()}
     */
    private boolean seedFromEarliestNode() throws WatermarkAccessException
    {
        Long minCommitTimeMs;
        try
        {
            minCommitTimeMs = metadataRepository.getMinCommitTimeMs();
        }
        catch (Exception e)
        {
            throw new WatermarkAccessException("Failed to query the earliest node commit time for smart-seed: " + e.getMessage(), e);
        }
        if (minCommitTimeMs == null)
        {
            LOGGER.info("smart-seed skipped: the Alfresco database has no nodes. Falling through to the next bootstrap option.");
            return false;
        }
        long overlapMs = safeOverlapMs();
        long seedCursor = Math.max(minCommitTimeMs - overlapMs, MIN_SEED_CURSOR_MS);
        WatermarkDocument seed = new WatermarkDocument();
        seed.setLastSuccessfulToTimeEpochMs(seedCursor);
        seed.setLastRunStatus(SEED_FROM_EARLIEST_STATUS);
        try
        {
            store.write(seed);
        }
        catch (WatermarkConflictException conflict)
        {
            LOGGER.warn("smart-seed lost a race to another writer — someone else wrote the state doc between our "
                    + "probe and our seed. Not fatal; falling through to normal cycle behaviour. {}", conflict.getMessage());
            return true;
        }
        LOGGER.info("Cursor smart-seeded from earliest node commit time: min={}, overlapMs={}, cursor={}. "
                + "Next cycle will scan [{}, now].",
                minCommitTimeMs, overlapMs, seedCursor, seedCursor);
        return true;
    }

    private Long queryAlfrescoMaxCursor() throws WatermarkAccessException
    {
        try
        {
            SearchRequest request = new SearchRequest.Builder()
                    .index(elasticsearchProperties.getIndexName())
                    .size(0)
                    .aggregations(MAX_AGG_NAME, a -> a.max(m -> m.field(METADATA_TIMESTAMP_FIELD)))
                    .build();
            SearchResponse<Void> response = openSearchClient.search(request, Void.class);
            Aggregate aggregate = response.aggregations().get(MAX_AGG_NAME);
            if (aggregate == null || !aggregate.isMax())
            {
                return null;
            }
            // MaxAggregate.value() returns a nullable Double; it is null when the index has no docs at all,
            // or when no doc carries the aggregated field. Both mean "no cursor to bootstrap from".
            Double boxed = aggregate.max().value();
            if (boxed == null || Double.isNaN(boxed) || Double.isInfinite(boxed) || boxed <= 0)
            {
                return null;
            }
            return boxed.longValue();
        }
        catch (OpenSearchException e)
        {
            if (e.status() == 404)
            {
                LOGGER.info("Cursor bootstrap from Alfresco index skipped: '{}' is missing — "
                        + "falling through to first-run bootstrap.", elasticsearchProperties.getIndexName());
                return null;
            }
            throw new WatermarkAccessException("Failed to query Alfresco index '"
                    + elasticsearchProperties.getIndexName() + "' for cursor bootstrap. "
                    + "Not silently defaulting to first-run bootstrap — restart will retry.", e);
        }
        catch (Exception e)
        {
            throw new WatermarkAccessException("Failed to query Alfresco index '"
                    + elasticsearchProperties.getIndexName() + "' for cursor bootstrap: "
                    + e.getMessage(), e);
        }
    }

    private long safeOverlapMs()
    {
        Duration overlap = properties.getOverlap();
        return overlap == null ? 0L : Math.max(0L, overlap.toMillis());
    }

    /**
     * Alfresco-index presence guard — if the state doc says we've indexed but the Alfresco index is missing, refuse to start. Silent data loss guard: without this, the app would happily scan {@code [T − overlap, now]} and write to an empty Alfresco index while the older data goes unnoticed.
     *
     * <p>
     * Only applies when the state doc <em>already</em> records a real cursor. A fresh install (no state doc) or a cursor seeded from the Alfresco index is not subject to this check — those paths have their own bootstrap semantics.
     */
    private void enforceAlfrescoIndexPresenceGuard(Optional<WatermarkDocument> existingBeforeBootstrap) throws WatermarkAccessException
    {
        if (!properties.isRequireAlfrescoIndex())
        {
            return;
        }
        WatermarkDocument existing = existingBeforeBootstrap.orElse(null);
        if (existing == null || existing.getLastSuccessfulToTimeEpochMs() <= 1L)
        {
            return;
        }
        boolean alfrescoExists;
        try
        {
            alfrescoExists = openSearchClient.indices()
                    .exists(new ExistsRequest.Builder().index(elasticsearchProperties.getIndexName()).build())
                    .value();
        }
        catch (Exception e)
        {
            throw new WatermarkAccessException("Alfresco-index presence check failed: could not probe Alfresco index '"
                    + elasticsearchProperties.getIndexName() + "' for existence: " + e.getMessage(), e);
        }
        if (!alfrescoExists)
        {
            throw new WatermarkAccessException(
                    "Refusing to start: watermark records lastSuccessfulToTimeEpochMs="
                            + existing.getLastSuccessfulToTimeEpochMs()
                            + " but Alfresco index '" + elasticsearchProperties.getIndexName()
                            + "' does not exist. This is silent-data-loss territory: the batch-indexer "
                            + "would scan [T − overlap, now] and write to an empty index, leaving all older "
                            + "data unindexed. Restore the Alfresco index from backup, run the reindexing "
                            + "CLI to rebuild it, or delete the state doc to re-bootstrap. Set "
                            + "alfresco.reindex.continuous.requireAlfrescoIndex=false to opt out of this "
                            + "check.");
        }
        LOGGER.info("Alfresco-index presence check passed: '{}' exists (requireAlfrescoIndex=true).",
                elasticsearchProperties.getIndexName());
    }
}
