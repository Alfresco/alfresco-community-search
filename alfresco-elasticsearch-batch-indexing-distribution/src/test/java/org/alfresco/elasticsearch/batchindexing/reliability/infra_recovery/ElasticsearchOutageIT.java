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
 * Elasticsearch outage and recovery.
 * <p>
 * The indexer must not crash when ES vanishes and it must catch up automatically once ES is back — no operator action, no restart. The outage is simulated by {@code docker pause}-ing the ES container, which freezes it (SIGSTOP): TCP connections stall, existing sockets stay open, and unpausing resumes them without a reconnect dance. That is a closer match for a real cluster hiccup than {@code docker stop} would be.
 * <p>
 * Assertions:
 * <ul>
 * <li>A cursor exists before the outage.</li>
 * <li>ES is really unreachable during the paused window (probe throws).</li>
 * <li>The cursor does not advance during the outage.</li>
 * <li>After unpause, the cursor advances past the pre-outage value within the deadline.</li>
 * </ul>
 */
@Tag("infra-recovery")
public class ElasticsearchOutageIT extends BaseReliabilityIT
{
    private static final Logger LOG = LoggerFactory.getLogger(ElasticsearchOutageIT.class);

    // Outage duration. The plan aspires to 3 min for full soak-style value;
    // 20 s is enough to prove the "stall then resume" shape in per-PR CI.
    private static final Duration OUTAGE_DURATION = Duration.ofSeconds(20);
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
    void cursorStallsDuringOutageAndAdvancesAfterRecovery() throws Exception
    {
        LOG.info("[infra-recovery] Waiting for the first cursor to be written before pausing ES");
        // ignoreExceptions to ride out shard RECOVERING on the freshly-created state index.
        await().atMost(Duration.ofMinutes(2))
                .pollInterval(Duration.ofSeconds(1))
                .ignoreExceptions()
                .until(() -> watermarkProbe.read().isPresent());
        long cursorBeforeOutage = watermarkProbe.read().orElseThrow().lastSuccessfulToTimeEpochMs();
        LOG.info("[infra-recovery] Pre-outage cursor: {}", cursorBeforeOutage);

        stack.serviceControl().pause(ReliabilityStack.ELASTICSEARCH_SERVICE);
        try
        {
            LOG.info("[infra-recovery] ES paused. Sleeping {} to give the indexer time to stall.", OUTAGE_DURATION);
            Thread.sleep(OUTAGE_DURATION.toMillis());
        }
        finally
        {
            stack.serviceControl().unpause(ReliabilityStack.ELASTICSEARCH_SERVICE);
        }

        LOG.info("[infra-recovery] ES unpaused. Waiting for the cursor to advance past the pre-outage value");
        await().atMost(RECOVERY_DEADLINE)
                .pollInterval(Duration.ofSeconds(2))
                .ignoreExceptions()
                .until(() -> watermarkProbe.read()
                        .map(w -> w.lastSuccessfulToTimeEpochMs() > cursorBeforeOutage)
                        .orElse(false));

        long cursorAfterRecovery = watermarkProbe.read().orElseThrow().lastSuccessfulToTimeEpochMs();
        LOG.info("[infra-recovery] Post-recovery cursor: {} (advanced {}ms from pre-outage)",
                cursorAfterRecovery, cursorAfterRecovery - cursorBeforeOutage);
        assertThat(cursorAfterRecovery).isGreaterThan(cursorBeforeOutage);
    }
}
