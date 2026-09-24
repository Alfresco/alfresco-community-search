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
import org.alfresco.elasticsearch.batchindexing.reliability.harness.ToxiproxyControl;
import org.alfresco.elasticsearch.batchindexing.reliability.harness.WatermarkProbe;

/**
 * Latency + jitter added to both dependencies at once.
 * <p>
 * Cycles run against slow Postgres <em>and</em> slow Elasticsearch. Cycle latency should go up but the app must keep making progress and must not crash. If the retry/backoff behaviour is wrong we'd expect either a stuck cursor or a poisoned in-flight cycle.
 */
@Tag("infra-recovery")
public class NetworkLatencyIT extends BaseReliabilityIT
{
    private static final Logger LOG = LoggerFactory.getLogger(NetworkLatencyIT.class);

    private static final String TOXIPROXY_SERVICE = "toxiproxy-1";
    private static final int TOXIPROXY_ADMIN_PORT = 8474;

    private static final String ES_PROXY = "elasticsearch";
    private static final String PG_PROXY = "postgres";
    private static final Duration BASE_LATENCY = Duration.ofMillis(500);
    private static final Duration JITTER = Duration.ofMillis(200);
    private static final Duration SLOW_WINDOW = Duration.ofSeconds(20);
    private static final Duration RECOVERY_DEADLINE = Duration.ofMinutes(2);

    private static ReliabilityStack stack;
    private static WatermarkProbe watermarkProbe;
    private static ToxiproxyControl toxiproxy;

    @BeforeAll
    static void startStack() throws IOException
    {
        Path toxiproxyConfig = DistributionEnv.toxiproxyConfigPath();

        stack = ReliabilityStack.builder(LOG, "batch-indexing-reliability-infra-recovery-compose.yaml")
                .env("TOXIPROXY_CONFIG_PATH", toxiproxyConfig.toString())
                .customize(cc -> cc.withExposedService(TOXIPROXY_SERVICE, TOXIPROXY_ADMIN_PORT))
                .start();

        int toxiproxyAdminPort = stack.compose().getServicePort(TOXIPROXY_SERVICE, TOXIPROXY_ADMIN_PORT);
        watermarkProbe = new WatermarkProbe(stack.openSearch().client());
        toxiproxy = new ToxiproxyControl("localhost", toxiproxyAdminPort);
        toxiproxy.useExisting(ES_PROXY);
        toxiproxy.useExisting(PG_PROXY);
    }

    @AfterAll
    static void tearDown() throws IOException
    {
        try
        {
            if (toxiproxy != null)
            {
                toxiproxy.clearToxics(ES_PROXY);
                toxiproxy.clearToxics(PG_PROXY);
            }
        }
        catch (Exception ignore)
        {
            // proxies vanish with the compose
        }
        if (stack != null)
        {
            stack.close();
        }
    }

    @Test
    void indexerRecoversAfterLatencyOnBothDeps() throws Exception
    {
        LOG.info("[infra-recovery] Waiting for the first cursor before injecting latency");
        // ignoreExceptions to ride out shard RECOVERING on the freshly-created state index.
        await().atMost(Duration.ofMinutes(2))
                .pollInterval(Duration.ofSeconds(1))
                .ignoreExceptions()
                .until(() -> watermarkProbe.read().isPresent());
        long cursorBeforeLatency = watermarkProbe.read().orElseThrow().lastSuccessfulToTimeEpochMs();
        LOG.info("[infra-recovery] Pre-latency cursor: {}", cursorBeforeLatency);

        LOG.info("[infra-recovery] Adding {}ms +/-{}ms latency on ES and Postgres for {}",
                BASE_LATENCY.toMillis(), JITTER.toMillis(), SLOW_WINDOW);
        toxiproxy.addLatency(ES_PROXY, BASE_LATENCY, JITTER);
        toxiproxy.addLatency(PG_PROXY, BASE_LATENCY, JITTER);
        try
        {
            Thread.sleep(SLOW_WINDOW.toMillis());
        }
        finally
        {
            toxiproxy.clearToxics(ES_PROXY);
            toxiproxy.clearToxics(PG_PROXY);
            LOG.info("[infra-recovery] Cleared latency toxics");
        }

        LOG.info("[infra-recovery] Waiting for the cursor to advance past the pre-latency value");
        await().atMost(RECOVERY_DEADLINE)
                .pollInterval(Duration.ofSeconds(2))
                .ignoreExceptions()
                .until(() -> watermarkProbe.read()
                        .map(w -> w.lastSuccessfulToTimeEpochMs() > cursorBeforeLatency)
                        .orElse(false));

        long cursorAfterRecovery = watermarkProbe.read().orElseThrow().lastSuccessfulToTimeEpochMs();
        LOG.info("[infra-recovery] Post-recovery cursor: {} (advanced {}ms from pre-latency)",
                cursorAfterRecovery, cursorAfterRecovery - cursorBeforeLatency);
        assertThat(cursorAfterRecovery).isGreaterThan(cursorBeforeLatency);

        assertThat(stack.serviceControl().isRunning(ReliabilityStack.BATCH_INDEXING_SERVICE))
                .as("The batch-indexing container must survive %s of added dep latency without crashing", SLOW_WINDOW)
                .isTrue();
    }
}
