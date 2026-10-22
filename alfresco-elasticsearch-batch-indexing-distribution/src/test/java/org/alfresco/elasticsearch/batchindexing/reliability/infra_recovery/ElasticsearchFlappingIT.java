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
package org.alfresco.elasticsearch.batchindexing.reliability.infra_recovery;

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
 * Elasticsearch flapping — repeated short outages instead of one long one.
 * <p>
 * Cycles {@code docker pause} / {@code unpause} on the ES service N times with a short outage and short recovery window each time. The indexer must never crash and must have made forward progress by the end.
 * <p>
 * The plan text calls for a Toxiproxy {@code timeout} toxic cycled every 30s over 5 min. This implementation uses {@code docker pause} instead — the acceptance criteria are the same ("no crash, cursor advances despite repeated faults") and pause/unpause needs no new infra. When the Toxiproxy harness lands (infra-recovery follow-up), switch this to a timeout-cycled variant to also exercise the half-open-socket path.
 * <p>
 * Assertions:
 * <ul>
 * <li>Cursor advances past the pre-flap value once the flap cycle ends and ES stabilises.</li>
 * <li>The batch-indexing container is still running.</li>
 * </ul>
 */
@Tag("infra-recovery")
public class ElasticsearchFlappingIT extends BaseReliabilityIT
{
    private static final Logger LOG = LoggerFactory.getLogger(ElasticsearchFlappingIT.class);

    // 4 cycles × (5s down + 5s up) = 40s of flap. Short outages are more
    // interesting than one long one because the shard has to recover repeatedly.
    private static final int FLAP_CYCLES = 4;
    private static final Duration DOWN_DURATION = Duration.ofSeconds(5);
    private static final Duration UP_DURATION = Duration.ofSeconds(5);
    private static final Duration RECOVERY_DEADLINE = Duration.ofMinutes(2);

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
    void cursorSurvivesRepeatedShortOutages() throws Exception
    {
        LOG.info("[infra-recovery] Waiting for the first cursor before starting the flap");
        // ignoreExceptions to ride out shard RECOVERING on the freshly-created state index.
        await().atMost(Duration.ofMinutes(2))
                .pollInterval(Duration.ofSeconds(1))
                .ignoreExceptions()
                .until(() -> watermarkProbe.read().isPresent());
        long cursorBeforeFlap = watermarkProbe.read().orElseThrow().lastSuccessfulToTimeEpochMs();
        LOG.info("[infra-recovery] Pre-flap cursor: {}", cursorBeforeFlap);

        for (int i = 1; i <= FLAP_CYCLES; i++)
        {
            LOG.info("[infra-recovery] Flap cycle {}/{} — pausing ES for {}", i, FLAP_CYCLES, DOWN_DURATION);
            stack.serviceControl().pause(ReliabilityStack.ELASTICSEARCH_SERVICE);
            Thread.sleep(DOWN_DURATION.toMillis());
            LOG.info("[infra-recovery] Flap cycle {}/{} — unpausing ES, letting it settle for {}", i, FLAP_CYCLES, UP_DURATION);
            stack.serviceControl().unpause(ReliabilityStack.ELASTICSEARCH_SERVICE);
            Thread.sleep(UP_DURATION.toMillis());
        }

        LOG.info("[infra-recovery] Flap cycle done. Waiting for cursor to advance past pre-flap value");
        await().atMost(RECOVERY_DEADLINE)
                .pollInterval(Duration.ofSeconds(2))
                .ignoreExceptions()
                .until(() -> watermarkProbe.read()
                        .map(w -> w.lastSuccessfulToTimeEpochMs() > cursorBeforeFlap)
                        .orElse(false));

        long cursorAfterFlap = watermarkProbe.read().orElseThrow().lastSuccessfulToTimeEpochMs();
        LOG.info("[infra-recovery] Post-flap cursor: {} (advanced {}ms from pre-flap)",
                cursorAfterFlap, cursorAfterFlap - cursorBeforeFlap);
        assertThat(cursorAfterFlap).isGreaterThan(cursorBeforeFlap);

        assertThat(stack.serviceControl().isRunning(ReliabilityStack.BATCH_INDEXING_SERVICE))
                .as("The batch-indexing container must survive %d ES flap cycles without crashing", FLAP_CYCLES)
                .isTrue();
    }
}
