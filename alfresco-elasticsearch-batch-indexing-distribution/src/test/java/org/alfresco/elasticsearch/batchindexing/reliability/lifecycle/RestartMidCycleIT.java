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

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.alfresco.elasticsearch.batchindexing.reliability.harness.BaseReliabilityIT;
import org.alfresco.elasticsearch.batchindexing.reliability.harness.ReliabilityStack;
import org.alfresco.elasticsearch.batchindexing.reliability.harness.WatermarkProbe;

/**
 * Hard restart while the indexer is actively cycling.
 * <p>
 * Simulates an OOM kill or a pod eviction: the batch-indexing container is {@code kill}-ed (SIGKILL, no graceful shutdown), then restarted. The app must pick up cleanly from the persisted watermark and keep advancing.
 * <p>
 * Assertions:
 * <ul>
 * <li>A cursor exists before the kill.</li>
 * <li>The container really dies (state moves from running to not-running).</li>
 * <li>After restart, the cursor advances past the pre-kill value within the deadline.</li>
 * </ul>
 */
@Tag("lifecycle")
public class RestartMidCycleIT extends BaseReliabilityIT
{
    private static final Logger LOG = LoggerFactory.getLogger(RestartMidCycleIT.class);

    private static ReliabilityStack stack;
    private static WatermarkProbe watermarkProbe;

    @BeforeAll
    static void startStack() throws IOException
    {
        stack = ReliabilityStack.builder(LOG, "batch-indexing-reliability-lifecycle-compose.yaml").start();
        watermarkProbe = new WatermarkProbe(stack.openSearch().client());
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
    void hardKillAndRestartResumesFromWatermark() throws IOException
    {
        LOG.info("[lifecycle] Waiting for the first cursor to be written");
        await().atMost(Duration.ofMinutes(2))
                .pollInterval(Duration.ofSeconds(1))
                .ignoreExceptions()
                .until(() -> watermarkProbe.read().isPresent());
        long cursorBeforeKill = watermarkProbe.read().orElseThrow().lastSuccessfulToTimeEpochMs();
        LOG.info("[lifecycle] Pre-kill cursor: {}", cursorBeforeKill);

        stack.serviceControl().kill(ReliabilityStack.BATCH_INDEXING_SERVICE);
        await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofSeconds(1))
                .until(() -> !stack.serviceControl().isRunning(ReliabilityStack.BATCH_INDEXING_SERVICE));
        assertThat(stack.serviceControl().isRunning(ReliabilityStack.BATCH_INDEXING_SERVICE))
                .as("Container must have died after SIGKILL")
                .isFalse();

        stack.serviceControl().start(ReliabilityStack.BATCH_INDEXING_SERVICE);
        await().atMost(Duration.ofMinutes(1))
                .pollInterval(Duration.ofSeconds(1))
                .until(() -> stack.serviceControl().isRunning(ReliabilityStack.BATCH_INDEXING_SERVICE));

        LOG.info("[lifecycle] Waiting for the cursor to advance past the pre-kill value");
        await().atMost(Duration.ofMinutes(2))
                .pollInterval(Duration.ofSeconds(2))
                .until(() -> watermarkProbe.read()
                        .map(w -> w.lastSuccessfulToTimeEpochMs() > cursorBeforeKill)
                        .orElse(false));

        long cursorAfterRestart = watermarkProbe.read().orElseThrow().lastSuccessfulToTimeEpochMs();
        LOG.info("[lifecycle] Post-restart cursor: {} (advanced {}ms from pre-kill)",
                cursorAfterRestart, cursorAfterRestart - cursorBeforeKill);
        assertThat(cursorAfterRestart).isGreaterThan(cursorBeforeKill);
    }
}
