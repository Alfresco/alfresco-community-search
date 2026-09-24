/*-
 * #%L
 * Alfresco Elasticsearch Connector :: Batch Indexing Distribution
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
package org.alfresco.elasticsearch.batchindexing.reliability.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.alfresco.elasticsearch.batchindexing.reliability.harness.AlfrescoIndexHelper;
import org.alfresco.elasticsearch.batchindexing.reliability.harness.BaseReliabilityIT;
import org.alfresco.elasticsearch.batchindexing.reliability.harness.ReliabilityStack;
import org.alfresco.elasticsearch.batchindexing.reliability.harness.WatermarkProbe;

/**
 * Cold-start bootstrap without a seeded watermark.
 * <p>
 * The scenario is deliberately the opposite of the memory / capacity ITs, which seed {@code lastSuccessfulToTimeEpochMs=1} to force a full historical scan. Here we start with no watermark document at all — this is the operator-does-nothing path — and assert the safe default: the indexer picks up at {@code now}, only scans the recent overlap window, and does <b>not</b> re-index the historical DB.
 * <p>
 * Assertions:
 * <ul>
 * <li>The watermark document appears after the first cycle completes.</li>
 * <li>Its {@code lastSuccessfulToTimeEpochMs} sits within a small window of test wall-clock (proving the cursor is anchored to real time, not to the pre-seeded epoch).</li>
 * <li>The Alfresco index picks up no repository nodes because historical data is outside the overlap band. The bookkeeping {@code sys:descriptor} doc that the validate step always writes on first run is filtered out of the count.</li>
 * </ul>
 */
@Tag("lifecycle")
public class ColdStartFirstRunIT extends BaseReliabilityIT
{
    private static final Logger LOG = LoggerFactory.getLogger(ColdStartFirstRunIT.class);

    // Grace window for wall-clock drift and cycle latency. The compose file
    // sets overlap=30s and polling=2s; the watermark should sit within a few
    // seconds of test start.
    private static final Duration CURSOR_WALL_CLOCK_TOLERANCE = Duration.ofMinutes(2);

    // Bound on node docs picked up by the initial [now-overlap, now] scan.
    // The failure we care about is "cold start silently re-indexed the whole
    // history", which the ~70-node dump would surface as ~70. The dump seed
    // is April 2021 and outside the window, so 0 is the expected value; the
    // slack covers any node the ACS container bootstraps with a recent
    // db.time. The sys:descriptor bookkeeping doc is excluded by
    // AlfrescoIndexHelper.countNodeDocuments().
    private static final long HISTORICAL_DOCS_CEILING = 2;

    private static ReliabilityStack stack;
    private static AlfrescoIndexHelper alfrescoIndex;
    private static WatermarkProbe watermarkProbe;
    private static long testStartEpochMs;

    @BeforeAll
    static void startStack() throws IOException
    {
        testStartEpochMs = Instant.now().toEpochMilli();

        stack = ReliabilityStack.builder(LOG, "batch-indexing-reliability-lifecycle-compose.yaml").start();
        alfrescoIndex = new AlfrescoIndexHelper(stack.openSearch().client());
        watermarkProbe = new WatermarkProbe(stack.openSearch().client());

        // Create the Alfresco index so any accidental writes could land somewhere.
        // We deliberately do NOT seed the watermark — that's the whole point of the test.
        alfrescoIndex.createIfMissing();
    }

    @AfterAll
    static void tearDown() throws IOException
    {
        if (stack != null)
        {
            stack.close();
        }
    }

    @Test
    void freshStartPicksUpAtNowAndDoesNotReindexHistory() throws IOException
    {
        LOG.info("[lifecycle] Waiting for first continuous cycle to write the watermark");
        await().atMost(Duration.ofMinutes(2))
                .pollInterval(Duration.ofSeconds(1))
                .ignoreExceptions()
                .until(() -> watermarkProbe.read().isPresent());

        WatermarkProbe.Watermark watermark = watermarkProbe.read().orElseThrow();
        long cursor = watermark.lastSuccessfulToTimeEpochMs();
        long now = Instant.now().toEpochMilli();

        LOG.info("[lifecycle] Watermark after first cycle: cursor={} ({}ms after test start; {}ms behind now)",
                cursor, cursor - testStartEpochMs, now - cursor);

        assertThat(cursor)
                .as("The cursor must be anchored to test wall-clock, not to some pre-seeded epoch. "
                        + "cursor=%d, testStart=%d, now=%d", cursor, testStartEpochMs, now)
                .isBetween(testStartEpochMs - CURSOR_WALL_CLOCK_TOLERANCE.toMillis(),
                        now + CURSOR_WALL_CLOCK_TOLERANCE.toMillis());

        long docCount = countDocumentsQuietly();
        if (docCount > 0)
        {
            logIndexedDocs();
        }
        assertThat(docCount)
                .as("Historical DB data must not be silently re-indexed on cold start. "
                        + "The scan window is [now-overlap, now] which excludes the aged dump.")
                .isLessThanOrEqualTo(HISTORICAL_DOCS_CEILING);
    }

    private static long countDocumentsQuietly()
    {
        try
        {
            return alfrescoIndex.countNodeDocuments();
        }
        catch (IOException e)
        {
            throw new AssertionError("Failed to count documents in Alfresco index", e);
        }
    }

    /**
     * Best-effort diagnostic: dumps the first few documents currently in the Alfresco index so a mystery non-zero count is easy to explain the next time this test misbehaves.
     */
    private static void logIndexedDocs()
    {
        try
        {
            SearchResponse<Object> search = stack.openSearch().client().search(
                    s -> s.index(AlfrescoIndexHelper.INDEX_NAME).size(5),
                    Object.class);
            search.hits().hits().forEach(hit -> LOG.info("[lifecycle] indexed doc id={} source={}",
                    hit.id(), hit.source()));
        }
        catch (IOException e)
        {
            LOG.warn("[lifecycle] could not query Alfresco index for diagnostics: {}", e.getMessage());
        }
    }
}
