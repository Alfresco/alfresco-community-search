/*-
 * #%L
 * Alfresco Search Community :: Batch Indexing Distribution
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
import java.nio.file.Path;
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
import org.alfresco.elasticsearch.batchindexing.reliability.harness.DistributionEnv;
import org.alfresco.elasticsearch.batchindexing.reliability.harness.ReliabilityStack;
import org.alfresco.elasticsearch.batchindexing.reliability.harness.WatermarkProbe;

/**
 * Alfresco index missing and the DB has no historical Alfresco rows. Nothing to bootstrap from, nothing to scan. The scheduler should degrade to the fresh-install first-run behaviour, pick up at wall-clock, and stay running without a "missing state" or "missing index" panic.
 * <p>
 * Distinct from {@link ColdStartFirstRunIT}, which starts against the same fresh state doc but a DB that <b>does</b> hold the ~70-node historical dump. Here we truncate {@code alf_node} / {@code alf_transaction} after the dump loads so the reader has zero rows to return regardless of window size.
 * <p>
 * Assertions:
 * <ul>
 * <li>The batch-indexing container is still running after the first cycle window (no crash on empty DB / missing index).</li>
 * <li>A watermark document appears with {@code lastRunStatus="COMPLETED"} — the empty cycle finished cleanly, not FAILED.</li>
 * <li>{@code lastSuccessfulToTimeEpochMs} is anchored near test wall-clock (i.e. the safe cold-start default, not zero or an ancient seed).</li>
 * <li>The Alfresco index has zero documents (or doesn't exist yet) — the indexer never invented content out of an empty repo.</li>
 * </ul>
 */
@Tag("lifecycle")
public class BothIndicesEmptyFreshBootstrapIT extends BaseReliabilityIT
{
    private static final Logger LOG = LoggerFactory.getLogger(BothIndicesEmptyFreshBootstrapIT.class);

    private static final Duration CURSOR_WALL_CLOCK_TOLERANCE = Duration.ofMinutes(2);

    private static ReliabilityStack stack;
    private static AlfrescoIndexHelper alfrescoIndex;
    private static WatermarkProbe watermarkProbe;
    private static long testStartEpochMs;

    @BeforeAll
    static void startStack()
    {
        testStartEpochMs = Instant.now().toEpochMilli();

        Path truncateSql = DistributionEnv.dockerResourcePath("zz-truncate-alfresco-data.sql");

        stack = ReliabilityStack.builder(LOG, "batch-indexing-reliability-lifecycle-empty-compose.yaml")
                .env("TRUNCATE_SQL_PATH", truncateSql.toString())
                .start();
        alfrescoIndex = new AlfrescoIndexHelper(stack.openSearch().client());
        watermarkProbe = new WatermarkProbe(stack.openSearch().client());

        // No createIfMissing(): "both indices empty" means the Alfresco index
        // must not exist at boot. No watermark seed either.
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
    void freshInstallBootstrapsWithoutCrashingOnEmptyState() throws IOException
    {
        LOG.info("[lifecycle] Waiting for first cycle against empty repo to write the watermark");
        await().atMost(Duration.ofMinutes(2))
                .pollInterval(Duration.ofSeconds(1))
                .ignoreExceptions()
                .until(() -> watermarkProbe.read().isPresent());

        WatermarkProbe.Watermark watermark = watermarkProbe.read().orElseThrow();
        long cursor = watermark.lastSuccessfulToTimeEpochMs();
        long now = Instant.now().toEpochMilli();

        LOG.info("[lifecycle] Watermark after first empty cycle: cursor={} ({}ms after test start; {}ms behind now) status={}",
                cursor, cursor - testStartEpochMs, now - cursor, watermark.lastRunStatus());

        assertThat(watermark.lastRunStatus())
                .as("Empty repo must land as COMPLETED, not FAILED. status=%s", watermark.lastRunStatus())
                .isEqualTo("COMPLETED");

        assertThat(cursor)
                .as("Cursor must be anchored to test wall-clock (cold-start default), "
                        + "not seeded from a past epoch. cursor=%d testStart=%d now=%d",
                        cursor, testStartEpochMs, now)
                .isBetween(testStartEpochMs - CURSOR_WALL_CLOCK_TOLERANCE.toMillis(),
                        now + CURSOR_WALL_CLOCK_TOLERANCE.toMillis());

        assertThat(alfrescoIndex.countDocuments())
                .as("Alfresco index must have zero documents against an empty repository.")
                .isZero();

        assertThat(stack.serviceControl().isRunning(ReliabilityStack.BATCH_INDEXING_SERVICE))
                .as("batch-indexing container must still be running after the empty cycle "
                        + "(no crash on missing state / empty repo).")
                .isTrue();
    }
}
