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
package org.alfresco.elasticsearch.batchindexing.reliability.capacity;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.output.Slf4jLogConsumer;

import org.alfresco.elasticsearch.batchindexing.reliability.harness.AlfrescoIndexHelper;
import org.alfresco.elasticsearch.batchindexing.reliability.harness.BaseReliabilityIT;
import org.alfresco.elasticsearch.batchindexing.reliability.harness.DistributionEnv;
import org.alfresco.elasticsearch.batchindexing.reliability.harness.ReliabilityStack;
import org.alfresco.elasticsearch.batchindexing.reliability.harness.WatermarkProbe;

/**
 * Capacity soak — 200 000 nodes get seeded into a single time window and the batch-indexer must drain them in one Spring Batch job execution without OOM, timeouts, or container death.
 * <p>
 * Distinct from {@link CapacityReliabilityIT} (10 000-node throughput baseline): this variant scales the workload up 20× to answer the "what if a whole day of writes lands in one window" question raised in the demo. Reuses the capacity compose (metadata-only, {@code maxWindow=100y}, chunk size 1000) so the whole dump-plus-perf-populate dataset flows through one job execution.
 * <p>
 * The primary reliability contract this locks down:
 * <ul>
 * <li>The batch-indexing container survives the whole run (no OOM kill → JVM exit).</li>
 * <li>All (or nearly all) seeded nodes end up indexed within a generous wall-time bound.</li>
 * <li>The watermark advances to a real cursor (not stuck at 1L, which would prove the job failed silently).</li>
 * </ul>
 * <p>
 * <b>Scale note</b>: 200 000 nodes takes tens of minutes even on x86 Linux, and considerably longer on Apple Silicon because {@code perf-populate:slim} is amd64-only and runs under QEMU emulation. The wall-time bound is deliberately loose (60 min) — the real signal is that the container is still alive and the doc count is bounded above zero. Tune {@code -Dreliability.large-window.numberOfNodes=N} down for local iteration; the committed target is 200k.
 */
@Tag("capacity")
@SuppressWarnings("PMD.UnitTestShouldIncludeAssert")
public class LargeSingleWindowIT extends BaseReliabilityIT
{
    private static final Logger LOG = LoggerFactory.getLogger(LargeSingleWindowIT.class);

    private static final String PERF_POPULATE_SERVICE = "perf-populate-1";

    private static final int DEFAULT_NUMBER_OF_NODES = 200_000;
    private static final Duration INDEXING_UPPER_BOUND = Duration.ofMinutes(60);
    private static final Duration SAMPLE_INTERVAL = Duration.ofSeconds(10);
    private static final int PLATEAU_SAMPLES = 3;

    private static ReliabilityStack stack;
    private static AlfrescoIndexHelper alfrescoIndex;
    private static WatermarkProbe watermarkProbe;
    private static Path outputDir;
    private static int numberOfNodes;

    @BeforeAll
    static void startStack() throws IOException
    {
        numberOfNodes = Integer.parseInt(
                System.getProperty("reliability.large-window.numberOfNodes",
                        String.valueOf(DEFAULT_NUMBER_OF_NODES)));
        outputDir = DistributionEnv.outputDir();

        File postgresSeqSync = new File("src/test/resources/docker/zz-bump-sequences.sql");
        assertThat(postgresSeqSync).exists();
        Path perfPopulateConfig = materialiseConfig(numberOfNodes);

        LOG.info("Starting large-single-window stack: numberOfNodes={}", numberOfNodes);

        stack = ReliabilityStack.builder(LOG, "batch-indexing-reliability-capacity-compose.yaml")
                .env("POSTGRES_SEQ_SYNC_PATH", postgresSeqSync.getAbsolutePath())
                .env("PERF_POPULATE_CONFIG_PATH", perfPopulateConfig.toAbsolutePath().toString())
                .esStartupTimeout(Duration.ofMinutes(60))
                .customize(cc -> cc.withLogConsumer(PERF_POPULATE_SERVICE,
                        new Slf4jLogConsumer(LOG).withPrefix("perf-populate")))
                .start();

        alfrescoIndex = new AlfrescoIndexHelper(stack.openSearch().client());
        watermarkProbe = new WatermarkProbe(stack.openSearch().client());

        alfrescoIndex.createIfMissing();
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
    void singleLargeWindowDrainsWithoutOom() throws IOException, InterruptedException
    {
        Path csv = outputDir.resolve("large-single-window-" + numberOfNodes + "-"
                + Instant.now().toEpochMilli() + ".csv");
        Instant t0 = Instant.now();
        Instant deadline = t0.plus(INDEXING_UPPER_BOUND);
        long stableCount = -1;
        int stableSamples = 0;

        try (BufferedWriter writer = Files.newBufferedWriter(csv,
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW))
        {
            writer.write("epoch_ms,iso_timestamp,elapsed_ms,doc_count,batch_indexing_running\n");

            while (Instant.now().isBefore(deadline))
            {
                long count = alfrescoIndex.countDocuments();
                boolean running = stack.serviceControl().isRunning(ReliabilityStack.BATCH_INDEXING_SERVICE);
                Instant now = Instant.now();
                long elapsedMs = Duration.between(t0, now).toMillis();

                writer.write(String.format("%d,%s,%d,%d,%s%n",
                        now.toEpochMilli(), now.toString(), elapsedMs, count, running));
                writer.flush();

                LOG.info("[{}s] count={} running={}", elapsedMs / 1000, count, running);

                if (!running)
                {
                    Integer exitCode = stack.serviceControl().exitCode(ReliabilityStack.BATCH_INDEXING_SERVICE);
                    throw new AssertionError(String.format(
                            "batch-indexing container died mid-drain at count=%d (exitCode=%s). "
                                    + "OOM or an unhandled failure. CSV: %s",
                            count, exitCode, csv));
                }

                if (count == stableCount && count > 0)
                {
                    stableSamples++;
                    if (stableSamples >= PLATEAU_SAMPLES)
                    {
                        LOG.info("Doc count stable at {} for {} samples; treating as drained.",
                                count, PLATEAU_SAMPLES);
                        break;
                    }
                }
                else
                {
                    stableCount = count;
                    stableSamples = 0;
                }

                Thread.sleep(SAMPLE_INTERVAL.toMillis());
            }
        }

        long finalCount = alfrescoIndex.countDocuments();
        Duration elapsed = Duration.between(t0, Instant.now());
        LOG.info("LARGE-SINGLE-WINDOW: {} nodes seeded, {} docs indexed in {}. CSV: {}",
                numberOfNodes, finalCount, elapsed, csv);

        assertThat(stack.serviceControl().isRunning(ReliabilityStack.BATCH_INDEXING_SERVICE))
                .as("batch-indexing container must still be running (no OOM). CSV: %s", csv)
                .isTrue();

        long minAcceptable = numberOfNodes / 2;
        assertThat(finalCount)
                .as("ES doc count (%d) should be at least 50%% of numberOfNodes (%d). CSV: %s",
                        finalCount, numberOfNodes, csv)
                .isGreaterThanOrEqualTo(minAcceptable);

        WatermarkProbe.Watermark watermark = watermarkProbe.read().orElseThrow(
                () -> new AssertionError("Watermark doc must exist after the drain"));
        assertThat(watermark.lastSuccessfulToTimeEpochMs())
                .as("Watermark cursor must have advanced past the sentinel seed (1L)")
                .isGreaterThan(1L);
    }

    private static Path materialiseConfig(int nodes) throws IOException
    {
        try (InputStream is = LargeSingleWindowIT.class.getResourceAsStream(
                "/docker/perf-populate-config.template.json"))
        {
            assertThat(is).as("perf-populate-config.template.json must be on the test classpath").isNotNull();
            String template = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            String rendered = template.replace("${number_of_nodes}", Integer.toString(nodes));
            Path target = outputDir.resolve("perf-populate-config-large-window-" + nodes + ".json");
            Files.writeString(target, rendered, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return target;
        }
    }
}
