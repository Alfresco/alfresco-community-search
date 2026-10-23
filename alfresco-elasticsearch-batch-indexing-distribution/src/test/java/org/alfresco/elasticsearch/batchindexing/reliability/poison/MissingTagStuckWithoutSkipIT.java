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
package org.alfresco.elasticsearch.batchindexing.reliability.poison;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.alfresco.elasticsearch.batchindexing.reliability.harness.BaseReliabilityIT;
import org.alfresco.elasticsearch.batchindexing.reliability.harness.DistributionEnv;
import org.alfresco.elasticsearch.batchindexing.reliability.harness.DlqProbe;
import org.alfresco.elasticsearch.batchindexing.reliability.harness.ReliabilityStack;
import org.alfresco.elasticsearch.batchindexing.reliability.harness.WatermarkProbe;

/**
 * Regression fence for the enterprise-reindexing default of {@code skipLimit=0} — proves that a single poison item stalls the cycle end-to-end and produces no DLQ write. Sister test to {@link MissingTagPoisonIT}.
 * <p>
 * Same poison shape (one bogus {@code cm:taggable} value on node 384), same seed watermark. Only difference is {@code alfresco.reindex.skipLimit=0}: Spring Batch skip is disabled, the processor exception propagates, the step + job go {@code FAILED}, and {@code lastSuccessfulToTime} stays at the seed value across multiple cycles. Because skip is disabled, {@code DlqSkipListener} is never invoked and the DLQ index is never created.
 * <p>
 * The happy-path IT proves batch-indexing survives the poison; this one is the "how it looks with skip disabled" regression fence — bump {@code skipLimit} to {@code 0} in {@code application.properties} and this test tells you what breaks.
 * <p>
 * Assertions after ~30 s of poll churn:
 * <ol>
 * <li>{@code lastRunStatus=FAILED}.</li>
 * <li>{@code lastSuccessfulToTimeEpochMs=1} (unchanged from the seed).</li>
 * <li>DLQ index missing or empty — skip is disabled, so no {@code DlqSkipListener} invocation.</li>
 * </ol>
 */
@Tag("poison")
public class MissingTagStuckWithoutSkipIT extends BaseReliabilityIT
{
    private static final Logger LOG = LoggerFactory.getLogger(MissingTagStuckWithoutSkipIT.class);

    private static final String POISON_SQL_NAME = "zz-missing-tag-poison.sql";

    /** Long enough for JVM warm-up plus at least one full failed cycle. */
    private static final Duration FIRST_FAILURE_TIMEOUT = Duration.ofMinutes(3);
    /** After the first FAILED cycle we wait through a few more polls to confirm the state is sticky rather than a startup blip. */
    private static final Duration STABILITY_OBSERVATION_WINDOW = Duration.ofSeconds(30);

    private static ReliabilityStack stack;
    private static WatermarkProbe watermarkProbe;
    private static DlqProbe dlqProbe;

    @BeforeAll
    static void startStack() throws IOException
    {
        Path poisonSql = DistributionEnv.poisonSqlPath(POISON_SQL_NAME);

        stack = ReliabilityStack.builder(LOG, "batch-indexing-reliability-poison-compose.yaml")
                .env("POISON_SQL_PATH", poisonSql.toString())
                // The whole point of this test: skip disabled, poison item aborts the cycle.
                .env("POISON_SKIP_LIMIT", "0")
                .start();

        watermarkProbe = new WatermarkProbe(stack.openSearch().client());
        dlqProbe = new DlqProbe(stack.openSearch().client());

        watermarkProbe.seedForFullHistoricalScan();
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
    void poisonStallsCycleAndSkipsDlqWhenSkipDisabled() throws Exception
    {
        LOG.info("[poison] Waiting up to {} for the first FAILED cycle", FIRST_FAILURE_TIMEOUT);
        await().atMost(FIRST_FAILURE_TIMEOUT)
                .pollInterval(Duration.ofSeconds(2))
                .ignoreExceptions()
                .until(() -> watermarkProbe.read()
                        .map(w -> "FAILED".equals(w.lastRunStatus()))
                        .orElse(false));

        WatermarkProbe.Watermark firstFailure = watermarkProbe.read().orElseThrow();
        LOG.info("[poison] First FAILED cycle observed: {}", firstFailure);

        LOG.info("[poison] Watching for {} to confirm the failure is sticky", STABILITY_OBSERVATION_WINDOW);
        Thread.sleep(STABILITY_OBSERVATION_WINDOW.toMillis());

        WatermarkProbe.Watermark afterObservation = watermarkProbe.read().orElseThrow();
        LOG.info("[poison] Watermark after {}: {}", STABILITY_OBSERVATION_WINDOW, afterObservation);

        assertThat(afterObservation.lastRunStatus())
                .as("with skipLimit=0 the poison item aborts every cycle")
                .isEqualTo("FAILED");
        assertThat(afterObservation.lastSuccessfulToTimeEpochMs())
                .as("no successful cycle means lastSuccessfulTo is still the seed value (1)")
                .isEqualTo(1L);

        long dlqCount = dlqProbe.count();
        LOG.info("[poison] DLQ count while stuck: {}", dlqCount);
        assertThat(dlqCount)
                .as("skip disabled → DlqSkipListener never fires → DLQ index either missing or empty")
                .isZero();
    }
}
