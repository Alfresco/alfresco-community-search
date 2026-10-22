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
 * Elasticsearch responses squeezed to a small bandwidth budget.
 * <p>
 * A bandwidth toxic on the {@code elasticsearch} proxy caps downstream throughput at {@code BANDWIDTH_KBPS} for the duration of the slow window. Individual ES calls take longer; cycle latency goes up. The contract is that the indexer keeps making progress (backoff may kick in but the cursor eventually advances) and does not crash.
 * <p>
 * This is the first test to use the infra-recovery compose file, which routes both Postgres and Elasticsearch through Toxiproxy. The proxies are declared in {@code toxiproxy-config.json}; the test only mutates toxics via the admin API.
 * <p>
 * Assertions:
 * <ul>
 * <li>A cursor exists before the slow window.</li>
 * <li>After the toxic is cleared, the cursor advances past the pre-toxic value.</li>
 * <li>The batch-indexing container is still running.</li>
 * </ul>
 */
@Tag("infra-recovery")
public class SlowElasticsearchIT extends BaseReliabilityIT
{
    private static final Logger LOG = LoggerFactory.getLogger(SlowElasticsearchIT.class);

    private static final String TOXIPROXY_SERVICE = "toxiproxy-1";
    private static final int TOXIPROXY_ADMIN_PORT = 8474;
    private static final int TOXIPROXY_ES_PORT = 9200;

    private static final String ES_PROXY = "elasticsearch";
    private static final long BANDWIDTH_KBPS = 4;
    private static final Duration SLOW_WINDOW = Duration.ofSeconds(20);
    private static final Duration RECOVERY_DEADLINE = Duration.ofMinutes(2);

    private static ReliabilityStack stack;
    private static WatermarkProbe watermarkProbe;
    private static ToxiproxyControl toxiproxy;

    @BeforeAll
    static void startStack() throws IOException
    {
        Path toxiproxyConfig = DistributionEnv.toxiproxyConfigPath();

        // Toxiproxy image is distroless (no /bin/sh) so Wait.forListeningPort's
        // internal exec probe can't run inside the container. The compose-level
        // healthcheck (toxiproxy-cli list) is what actually gates readiness;
        // here we only need Testcontainers to publish the port.
        stack = ReliabilityStack.builder(LOG, "batch-indexing-reliability-infra-recovery-compose.yaml")
                .env("TOXIPROXY_CONFIG_PATH", toxiproxyConfig.toString())
                .customize(cc -> cc
                        .withExposedService(TOXIPROXY_SERVICE, TOXIPROXY_ADMIN_PORT)
                        .withExposedService(TOXIPROXY_SERVICE, TOXIPROXY_ES_PORT))
                .start();

        int toxiproxyAdminPort = stack.compose().getServicePort(TOXIPROXY_SERVICE, TOXIPROXY_ADMIN_PORT);
        watermarkProbe = new WatermarkProbe(stack.openSearch().client());
        toxiproxy = new ToxiproxyControl("localhost", toxiproxyAdminPort);
        // Proxies were created inside the container by the -config JSON at startup;
        // adopt them so the wrapper can attach toxics via the admin API.
        toxiproxy.useExisting(ES_PROXY);
    }

    @AfterAll
    static void tearDown() throws IOException
    {
        // Best-effort restore of a clean proxy state so a following test in the
        // same JVM doesn't inherit a live toxic.
        try
        {
            if (toxiproxy != null)
            {
                toxiproxy.clearToxics(ES_PROXY);
            }
        }
        catch (Exception ignore)
        {
            // toxiproxy is going away with the compose anyway
        }
        if (stack != null)
        {
            stack.close();
        }
    }

    @Test
    void indexerRecoversAfterEsBandwidthSqueeze() throws Exception
    {
        LOG.info("[infra-recovery] Waiting for the first cursor before shaping ES bandwidth");
        // ignoreExceptions to ride out shard RECOVERING on the freshly-created state index.
        await().atMost(Duration.ofMinutes(2))
                .pollInterval(Duration.ofSeconds(1))
                .ignoreExceptions()
                .until(() -> watermarkProbe.read().isPresent());
        long cursorBeforeToxic = watermarkProbe.read().orElseThrow().lastSuccessfulToTimeEpochMs();
        LOG.info("[infra-recovery] Pre-toxic cursor: {}", cursorBeforeToxic);

        LOG.info("[infra-recovery] Adding bandwidth toxic {}KB/s on ES proxy for {}", BANDWIDTH_KBPS, SLOW_WINDOW);
        toxiproxy.addBandwidth(ES_PROXY, BANDWIDTH_KBPS);
        try
        {
            Thread.sleep(SLOW_WINDOW.toMillis());
        }
        finally
        {
            toxiproxy.clearToxics(ES_PROXY);
            LOG.info("[infra-recovery] Cleared toxics on ES proxy");
        }

        LOG.info("[infra-recovery] Waiting for the cursor to advance past the pre-toxic value");
        await().atMost(RECOVERY_DEADLINE)
                .pollInterval(Duration.ofSeconds(2))
                .ignoreExceptions()
                .until(() -> watermarkProbe.read()
                        .map(w -> w.lastSuccessfulToTimeEpochMs() > cursorBeforeToxic)
                        .orElse(false));

        long cursorAfterRecovery = watermarkProbe.read().orElseThrow().lastSuccessfulToTimeEpochMs();
        LOG.info("[infra-recovery] Post-recovery cursor: {} (advanced {}ms from pre-toxic)",
                cursorAfterRecovery, cursorAfterRecovery - cursorBeforeToxic);
        assertThat(cursorAfterRecovery).isGreaterThan(cursorBeforeToxic);

        assertThat(stack.serviceControl().isRunning(ReliabilityStack.BATCH_INDEXING_SERVICE))
                .as("The batch-indexing container must survive a %s bandwidth squeeze without crashing", SLOW_WINDOW)
                .isTrue();
    }
}
