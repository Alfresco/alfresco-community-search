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
 * Gap-guard fast-forward.
 * <p>
 * When the watermark is much older than the configured {@code maxGapAge}, the indexer must not spend hours scanning windows that no operator wants re-scanned. The service logs a WARN and jumps the cursor forward to {@code now - maxGapAge} rather than {@code lastSuccessfulTo - overlap}. This test exercises that path by seeding a cursor from long ago and verifying the app snaps to the near past on the first cycle.
 */
@Tag("lifecycle")
public class GapSkipMaxAgeIT extends BaseReliabilityIT
{
    private static final Logger LOG = LoggerFactory.getLogger(GapSkipMaxAgeIT.class);

    // Anchor for the seeded stale cursor. Long enough ago that no reasonable
    // maxGapAge would let the scanner reach here.
    private static final Duration STALE_SEED_AGE = Duration.ofDays(30);
    private static final String MAX_GAP_AGE_CONF = "1h";
    private static final Duration MAX_GAP_AGE = Duration.ofHours(1);
    // Fast-forward should place the cursor within [now - maxGapAge - slack,
    // now + slack]. Slack absorbs cycle latency and clock drift.
    private static final Duration CURSOR_SLACK = Duration.ofMinutes(5);
    private static final long HISTORICAL_DOCS_CEILING = 5;

    private static ReliabilityStack stack;
    private static AlfrescoIndexHelper alfrescoIndex;
    private static WatermarkProbe watermarkProbe;
    private static long seededCursorEpochMs;

    @BeforeAll
    static void startStack() throws IOException
    {
        stack = ReliabilityStack.builder(LOG, "batch-indexing-reliability-lifecycle-compose.yaml")
                .env("LIFECYCLE_MAX_GAP_AGE", MAX_GAP_AGE_CONF)
                .start();
        alfrescoIndex = new AlfrescoIndexHelper(stack.openSearch().client());
        watermarkProbe = new WatermarkProbe(stack.openSearch().client());

        alfrescoIndex.createIfMissing();

        seededCursorEpochMs = Instant.now().minus(STALE_SEED_AGE).toEpochMilli();
        watermarkProbe.seed(seededCursorEpochMs, "SEEDED");
        LOG.info("[lifecycle] Seeded stale watermark cursor at {} (Instant.now() - {}); "
                + "maxGapAge={} — expecting fast-forward to ~now-{}",
                seededCursorEpochMs, STALE_SEED_AGE, MAX_GAP_AGE_CONF, MAX_GAP_AGE);
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
    void staleCursorFastForwardsToMaxGapAgeBand() throws IOException
    {
        LOG.info("[lifecycle] Waiting for the app to fast-forward the stale cursor past the maxGapAge floor");
        long fastForwardFloor = Instant.now().minus(MAX_GAP_AGE).toEpochMilli();

        await().atMost(Duration.ofMinutes(2))
                .pollInterval(Duration.ofSeconds(1))
                .ignoreExceptions()
                .until(() -> watermarkProbe.read()
                        .map(w -> w.lastSuccessfulToTimeEpochMs() >= fastForwardFloor)
                        .orElse(false));

        WatermarkProbe.Watermark watermark = watermarkProbe.read().orElseThrow();
        long cursor = watermark.lastSuccessfulToTimeEpochMs();
        long now = Instant.now().toEpochMilli();
        long expectedFloor = now - MAX_GAP_AGE.toMillis() - CURSOR_SLACK.toMillis();
        long expectedCeiling = now + CURSOR_SLACK.toMillis();

        LOG.info("[lifecycle] Fast-forward complete: seeded={} -> cursor={} (now={}, expected within [{}, {}])",
                seededCursorEpochMs, cursor, now, expectedFloor, expectedCeiling);

        assertThat(cursor)
                .as("Cursor must land in [now - maxGapAge - slack, now + slack] after fast-forward. "
                        + "cursor=%d now=%d maxGapAge=%s slack=%s",
                        cursor, now, MAX_GAP_AGE, CURSOR_SLACK)
                .isBetween(expectedFloor, expectedCeiling);

        long docCount = alfrescoIndex.countDocuments();
        assertThat(docCount)
                .as("The seeded stale cursor's original window covers April 2021 dump data, "
                        + "which the fast-forward must skip. If docs appear the fast-forward isn't "
                        + "working. docCount=%d", docCount)
                .isLessThanOrEqualTo(HISTORICAL_DOCS_CEILING);
    }
}
