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
import org.alfresco.elasticsearch.batchindexing.reliability.harness.ReliabilityStack;
import org.alfresco.elasticsearch.batchindexing.reliability.harness.WatermarkProbe;

/**
 * Container restart after a step went {@code FAILED}.
 * <p>
 * Complements {@link RestartMidCycleIT} (kill during a healthy cycle) and {@code MissingTagStuckWithoutSkipIT} (repeated FAILED cycles without a restart). The distinct contract here: a hard kill and restart of the batch-indexing container between failed cycles must not advance the persisted anchor. Same window is re-attempted on the next cycle.
 * <p>
 * Setup reuses the missing-tag poison from {@code MissingTagPoisonIT} with {@code skipLimit=0} so the poison aborts every cycle. Watermark is seeded to epoch 1 for a full historical sweep — the poison node lives in the 2021 dump.
 * <p>
 * Assertions:
 * <ol>
 * <li>A FAILED cycle is observed before the kill.</li>
 * <li>The container really dies.</li>
 * <li>After restart, the app runs at least one more cycle and it is also {@code FAILED}.</li>
 * <li>{@code lastSuccessfulToTimeEpochMs} is still the seed value (1) — a restart during a failed cycle must not shift the anchor.</li>
 * </ol>
 */
@Tag("lifecycle")
public class RestartAfterFailedCycleIT extends BaseReliabilityIT
{
    private static final Logger LOG = LoggerFactory.getLogger(RestartAfterFailedCycleIT.class);

    private static final String POISON_SQL_NAME = "zz-missing-tag-poison.sql";

    /** JVM warm-up + one full-history sweep. */
    private static final Duration FIRST_FAILURE_TIMEOUT = Duration.ofMinutes(3);
    /** Cold restart + first post-restart cycle. */
    private static final Duration POST_RESTART_TIMEOUT = Duration.ofMinutes(3);

    private static ReliabilityStack stack;
    private static WatermarkProbe watermarkProbe;

    @BeforeAll
    static void startStack() throws IOException
    {
        Path poisonSql = DistributionEnv.poisonSqlPath(POISON_SQL_NAME);

        stack = ReliabilityStack.builder(LOG, "batch-indexing-reliability-poison-compose.yaml")
                .env("POISON_SQL_PATH", poisonSql.toString())
                // skip disabled → poison aborts the step → cycle FAILED.
                .env("POISON_SKIP_LIMIT", "0")
                .start();
        watermarkProbe = new WatermarkProbe(stack.openSearch().client());

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
    void restartDuringFailedCyclesDoesNotAdvanceAnchor() throws IOException
    {
        LOG.info("[lifecycle] Waiting up to {} for the first FAILED cycle", FIRST_FAILURE_TIMEOUT);
        await().atMost(FIRST_FAILURE_TIMEOUT)
                .pollInterval(Duration.ofSeconds(2))
                .ignoreExceptions()
                .until(RestartAfterFailedCycleIT::cycleFailed);

        WatermarkProbe.Watermark preKill = watermarkProbe.read().orElseThrow();
        LOG.info("[lifecycle] Pre-kill watermark: {}", preKill);
        assertThat(preKill.lastSuccessfulToTimeEpochMs())
                .as("no cycle ever succeeded, so the anchor should still be at the seed value")
                .isEqualTo(1L);
        String preKillUpdatedAt = preKill.updatedAt();

        LOG.info("[lifecycle] SIGKILL batch-indexing");
        stack.serviceControl().kill(ReliabilityStack.BATCH_INDEXING_SERVICE);
        await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofSeconds(1))
                .until(() -> !stack.serviceControl().isRunning(ReliabilityStack.BATCH_INDEXING_SERVICE));

        LOG.info("[lifecycle] Restarting batch-indexing");
        stack.serviceControl().start(ReliabilityStack.BATCH_INDEXING_SERVICE);
        await().atMost(Duration.ofMinutes(1))
                .pollInterval(Duration.ofSeconds(1))
                .until(() -> stack.serviceControl().isRunning(ReliabilityStack.BATCH_INDEXING_SERVICE));

        LOG.info("[lifecycle] Waiting up to {} for a post-restart FAILED cycle (updatedAt moves past {})",
                POST_RESTART_TIMEOUT, preKillUpdatedAt);
        await().atMost(POST_RESTART_TIMEOUT)
                .pollInterval(Duration.ofSeconds(2))
                .ignoreExceptions()
                .until(() -> watermarkProbe.read()
                        .map(w -> "FAILED".equals(w.lastRunStatus())
                                && !java.util.Objects.equals(w.updatedAt(), preKillUpdatedAt))
                        .orElse(false));

        WatermarkProbe.Watermark postRestart = watermarkProbe.read().orElseThrow();
        LOG.info("[lifecycle] Post-restart watermark: {}", postRestart);

        assertThat(postRestart.lastRunStatus())
                .as("second cycle after restart hits the same poison and fails again")
                .isEqualTo("FAILED");
        assertThat(postRestart.lastSuccessfulToTimeEpochMs())
                .as("kill + restart during failed cycles must not advance the anchor")
                .isEqualTo(1L);
    }

    private static boolean cycleFailed() throws IOException
    {
        return watermarkProbe.read()
                .map(w -> "FAILED".equals(w.lastRunStatus()))
                .orElse(false);
    }
}
