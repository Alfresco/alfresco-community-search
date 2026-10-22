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
 * Step-level {@code skipLimit} cap.
 * <p>
 * With {@code skipLimit=N} Spring Batch tolerates N skipped items per step; the N+1th skip trips {@code SkipLimitExceededException} and the step goes {@code FAILED}. The plan scenario is "200 poisons with skipLimit=100"; the mechanism doesn't care about the absolute numbers, so we take the smallest form that exercises it — {@code skipLimit=1} plus two poison nodes.
 * <p>
 * Poison shape reuses the missing-tag pattern from {@link MissingTagPoisonIT}: bad {@code cm:taggable} rows on nodes 384 and 385 that {@code AddTagsPostProcessor} can't resolve. Each unresolved tag = one skipped item = one bump of Spring Batch's skip counter. With {@code skipLimit=1}, the second bad item takes the step down.
 * <p>
 * Assertions:
 * <ol>
 * <li>{@code lastRunStatus=FAILED}.</li>
 * <li>{@code lastSuccessfulToTimeEpochMs} pinned at the seed value (1) — no cycle ever completed.</li>
 * <li>The state is sticky over a short observation window (rules out a startup transient).</li>
 * <li>DLQ contains at most one entry — Spring Batch may or may not invoke the skip listener before deciding the N+1th skip is a step-fail; both outcomes are correct.</li>
 * </ol>
 * <p>
 * Complements {@link MissingTagStuckWithoutSkipIT} ({@code skipLimit=0}, no listener ever fires) and {@link MissingTagPoisonIT} ({@code skipLimit=100}, all poisons absorbed).
 */
@Tag("poison")
public class SkipLimitExceededIT extends BaseReliabilityIT
{
    private static final Logger LOG = LoggerFactory.getLogger(SkipLimitExceededIT.class);

    private static final String POISON_SQL_NAME = "zz-missing-tag-multi-poison.sql";

    /** Long enough for JVM warm-up + one full-history sweep. */
    private static final Duration FIRST_FAILURE_TIMEOUT = Duration.ofMinutes(3);
    /** Two more polls' worth so we're confident the failure is sticky, not a startup blip. */
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
                // The whole point: one skip allowed, second skip aborts the step.
                .env("POISON_SKIP_LIMIT", "1")
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
    void secondSkipTripsStepFailedAndWatermarkStalls() throws Exception
    {
        LOG.info("[poison] Waiting up to {} for the first FAILED cycle (skipLimit=1, 2 poison rows)", FIRST_FAILURE_TIMEOUT);
        await().atMost(FIRST_FAILURE_TIMEOUT)
                .pollInterval(Duration.ofSeconds(2))
                .ignoreExceptions()
                .until(() -> watermarkProbe.read()
                        .map(w -> "FAILED".equals(w.lastRunStatus()))
                        .orElse(false));

        WatermarkProbe.Watermark firstFailure = watermarkProbe.read().orElseThrow();
        LOG.info("[poison] First FAILED cycle observed: {}", firstFailure);

        LOG.info("[poison] Observing for {} to confirm the FAILED state is sticky", STABILITY_OBSERVATION_WINDOW);
        Thread.sleep(STABILITY_OBSERVATION_WINDOW.toMillis());

        WatermarkProbe.Watermark afterObservation = watermarkProbe.read().orElseThrow();
        LOG.info("[poison] Watermark after {}: {}", STABILITY_OBSERVATION_WINDOW, afterObservation);

        assertThat(afterObservation.lastRunStatus())
                .as("second skip breaches skipLimit=1 → SkipLimitExceededException → step FAILED")
                .isEqualTo("FAILED");
        assertThat(afterObservation.lastSuccessfulToTimeEpochMs())
                .as("failed step never advances the watermark; still at the seed value")
                .isEqualTo(1L);
        // Closes plan gap #6 observability side. Spring Batch may count the cap-tripping skip or
        // fail the step before incrementing, so 1 or 2 are both correct — we just need > 0.
        assertThat(afterObservation.lastRunSkipCount())
                .as("at least one skip fired before the cap tripped; must surface in the watermark doc")
                .isGreaterThanOrEqualTo(1L);

        long dlqCount = dlqProbe.count();
        LOG.info("[poison] DLQ count after cap trip: {}", dlqCount);
        // Spring Batch may invoke the skip listener before or after checking the cap on the N+1th skip; both outcomes
        // are contract-compliant. What matters is that the cap trips.
        assertThat(dlqCount)
                .as("at most one DLQ entry — the second skip either bypasses the listener or is the one that trips the cap")
                .isLessThanOrEqualTo(1L);
    }
}
