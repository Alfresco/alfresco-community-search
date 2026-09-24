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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.alfresco.elasticsearch.batchindexing.reliability.harness.AlfrescoIndexHelper;
import org.alfresco.elasticsearch.batchindexing.reliability.harness.BaseReliabilityIT;
import org.alfresco.elasticsearch.batchindexing.reliability.harness.ReliabilityStack;
import org.alfresco.elasticsearch.batchindexing.reliability.harness.WatermarkProbe;

/**
 * Operator-seeded watermark.
 * <p>
 * Simulates the release-plan scenario where an operator writes a custom cursor before starting the indexer (e.g. to force a re-scan of a specific window). The seed lands in {@code alfresco-reindex-state} before the app starts; the app must read it on the first cycle and resume from there rather than treating the missing history like a cold start.
 * <p>
 * Assertions:
 * <ul>
 * <li>After the first cycle, the cursor is <b>strictly greater</b> than the seeded value — the app advanced.</li>
 * <li>The cursor is also at most a small tolerance above wall-clock — proves the app didn't get scared by the artificial past cursor and jump to {@code now}.</li>
 * <li>Because the seed is 1h in the past and the DB dump is aged (April 2021), the {@code [seed-overlap, now]} window contains no txns → no docs indexed.</li>
 * </ul>
 */
@Tag("lifecycle")
public class OperatorSeededWatermarkIT extends BaseReliabilityIT
{
    private static final Logger LOG = LoggerFactory.getLogger(OperatorSeededWatermarkIT.class);

    private static final Duration SEED_OFFSET = Duration.ofHours(1);
    private static final Duration CURSOR_WALL_CLOCK_TOLERANCE = Duration.ofMinutes(5);
    private static final long HISTORICAL_DOCS_CEILING = 5;

    private static ReliabilityStack stack;
    private static AlfrescoIndexHelper alfrescoIndex;
    private static WatermarkProbe watermarkProbe;
    private static long seededCursorEpochMs;

    @BeforeAll
    static void startStack() throws IOException
    {
        stack = ReliabilityStack.builder(LOG, "batch-indexing-reliability-lifecycle-compose.yaml").start();
        alfrescoIndex = new AlfrescoIndexHelper(stack.openSearch().client());
        watermarkProbe = new WatermarkProbe(stack.openSearch().client());

        alfrescoIndex.createIfMissing();

        seededCursorEpochMs = Instant.now().minus(SEED_OFFSET).toEpochMilli();
        watermarkProbe.seed(seededCursorEpochMs, "SEEDED");
        LOG.info("[lifecycle] Seeded watermark cursor at {} (Instant.now() - {})",
                seededCursorEpochMs, SEED_OFFSET);
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
    void seededCursorIsHonoredAndAdvancesFromThere() throws IOException
    {
        LOG.info("[lifecycle] Waiting for the app to advance the seeded cursor");
        await().atMost(Duration.ofMinutes(2))
                .pollInterval(Duration.ofSeconds(1))
                .ignoreExceptions()
                .until(() -> watermarkProbe.read()
                        .map(w -> w.lastSuccessfulToTimeEpochMs() > seededCursorEpochMs)
                        .orElse(false));

        WatermarkProbe.Watermark watermark = watermarkProbe.read().orElseThrow();
        long cursor = watermark.lastSuccessfulToTimeEpochMs();
        long now = Instant.now().toEpochMilli();

        LOG.info("[lifecycle] Cursor advanced: seeded={} -> current={} ({}ms behind now)",
                seededCursorEpochMs, cursor, now - cursor);

        assertThat(cursor)
                .as("Cursor must have moved forward from the seed. seeded=%d cursor=%d",
                        seededCursorEpochMs, cursor)
                .isGreaterThan(seededCursorEpochMs);

        assertThat(cursor)
                .as("Cursor must not overshoot wall-clock. If the app jumped straight to now instead "
                        + "of advancing chunk-by-chunk from the seed, the historical window is silently "
                        + "skipped. cursor=%d now=%d tolerance=%s", cursor, now, CURSOR_WALL_CLOCK_TOLERANCE)
                .isLessThanOrEqualTo(now + CURSOR_WALL_CLOCK_TOLERANCE.toMillis());

        long docCount = alfrescoIndex.countDocuments();
        assertThat(docCount)
                .as("Seeded window [seed-overlap, now] excludes April-2021 dump data, so no docs "
                        + "should be indexed. docCount=%d", docCount)
                .isLessThanOrEqualTo(HISTORICAL_DOCS_CEILING);
    }
}
