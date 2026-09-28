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
 * ACS Postgres outage and recovery.
 * <p>
 * Same shape as {@link ElasticsearchOutageIT} but the fault is on the read side. {@code docker pause} freezes the Postgres container, which stalls any in-flight JDBC calls. The indexer must survive without crashing and resume advancing the cursor once the DB is back — no operator action.
 * <p>
 * Assertions:
 * <ul>
 * <li>A cursor exists before the outage.</li>
 * <li>After unpause, the cursor advances past the pre-outage value within the deadline.</li>
 * </ul>
 * The mid-outage "cursor stayed flat" check is deliberately omitted — this environment has no fresh DB writes, so a stalled reader and a bored reader look the same from the cursor's perspective. If the reader crashes hard, the post-recovery advance check catches it.
 */
@Tag("infra-recovery")
public class PostgresOutageIT extends BaseReliabilityIT
{
    private static final Logger LOG = LoggerFactory.getLogger(PostgresOutageIT.class);

    private static final String POSTGRES_SERVICE = "postgres-1";

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
    void cursorAdvancesAfterPostgresRecovery() throws Exception
    {
        LOG.info("[infra-recovery] Waiting for the first cursor to be written before pausing Postgres");
        // ignoreExceptions to ride out shard RECOVERING on the freshly-created state index.
        await().atMost(Duration.ofMinutes(2))
                .pollInterval(Duration.ofSeconds(1))
                .ignoreExceptions()
                .until(() -> watermarkProbe.read().isPresent());
        long cursorBeforeOutage = watermarkProbe.read().orElseThrow().lastSuccessfulToTimeEpochMs();
        LOG.info("[infra-recovery] Pre-outage cursor: {}", cursorBeforeOutage);

        stack.serviceControl().pause(POSTGRES_SERVICE);
        try
        {
            LOG.info("[infra-recovery] Postgres paused. Sleeping {} to keep the DB unreachable.", OUTAGE_DURATION);
            Thread.sleep(OUTAGE_DURATION.toMillis());
        }
        finally
        {
            stack.serviceControl().unpause(POSTGRES_SERVICE);
        }

        LOG.info("[infra-recovery] Postgres unpaused. Waiting for the cursor to advance past the pre-outage value");
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

        assertThat(stack.serviceControl().isRunning(ReliabilityStack.BATCH_INDEXING_SERVICE))
                .as("The batch-indexing container must survive a %s Postgres outage without crashing", OUTAGE_DURATION)
                .isTrue();
    }
}
