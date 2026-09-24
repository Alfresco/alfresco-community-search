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
 * Capacity baseline test.
 * <p>
 * Pre-populates Postgres with a configurable number of nodes using the {@code alfresco-perf-populate:slim} tool (bypasses ACS, writes directly into the ACS schema), then starts the packaged batch-indexing image and measures how long it takes for those nodes to land in Elasticsearch. The compose file gates batch-indexing on perf-populate exiting successfully, so populate and reindex don't overlap.
 * <p>
 * Per-sample CSV under {@code target/reliability/capacity-<epoch>.csv} captures elapsed time and indexed doc count, from which avg + peak throughput are derived. Assertions are loose: doc count reaches the expected total and the run finishes within a generous wall-time bound. The CSV is the deliverable — the numbers feed the eventual sizing/regression discussion.
 * <p>
 * Default workload: 10 000 nodes (overridable via {@code -Dreliability.capacity.numberOfNodes=N}). 100 000 has been validated; scale beyond that with care. Apple Silicon caveat: the perf-populate image is amd64-only and runs under QEMU emulation, which makes the populate phase the long pole. It does not affect the batch-indexing throughput measurement.
 * <p>
 * Gated by {@code RELIABILITY_TESTS=true} (inherited from {@link BaseReliabilityIT}) and the {@code reliability-tests} Maven profile. Same image precondition as Scenario 5/7: build {@code alfresco-elasticsearch-batch-indexing:<version>} locally first.
 */
@Tag("capacity")
@SuppressWarnings("PMD.UnitTestShouldIncludeAssert")
public class CapacityReliabilityIT extends BaseReliabilityIT
{
    private static final Logger LOG = LoggerFactory.getLogger(CapacityReliabilityIT.class);

    private static final String PERF_POPULATE_SERVICE = "perf-populate-1";

    /** Wall-time upper bound on the indexing phase only. Loose; perf-populate is excluded. */
    private static final Duration INDEXING_UPPER_BOUND = Duration.ofMinutes(30);
    /** Stop the sample loop once doc count has not grown for this many consecutive samples. */
    private static final int PLATEAU_SAMPLES = 3;
    private static final Duration SAMPLE_INTERVAL = Duration.ofSeconds(5);

    private static ReliabilityStack stack;
    private static AlfrescoIndexHelper alfrescoIndex;
    private static Path outputDir;
    private static int numberOfNodes;

    @BeforeAll
    static void startStack() throws IOException
    {
        numberOfNodes = Integer.parseInt(
                System.getProperty("reliability.capacity.numberOfNodes", "10000"));
        outputDir = DistributionEnv.outputDir();

        File postgresSeqSync = new File("src/test/resources/docker/zz-bump-sequences.sql");
        assertThat(postgresSeqSync).exists();
        Path perfPopulateConfig = materialiseConfig(numberOfNodes);

        LOG.info("Starting capacity stack: numberOfNodes={}, perf-populate config={}",
                numberOfNodes, perfPopulateConfig);

        stack = ReliabilityStack.builder(LOG, "batch-indexing-reliability-capacity-compose.yaml")
                .env("POSTGRES_SEQ_SYNC_PATH", postgresSeqSync.getAbsolutePath())
                .env("PERF_POPULATE_CONFIG_PATH", perfPopulateConfig.toAbsolutePath().toString())
                .esStartupTimeout(Duration.ofMinutes(30))
                .customize(cc -> cc.withLogConsumer(PERF_POPULATE_SERVICE,
                        new Slf4jLogConsumer(LOG).withPrefix("perf-populate")))
                .start();

        alfrescoIndex = new AlfrescoIndexHelper(stack.openSearch().client());

        alfrescoIndex.createIfMissing();
        new WatermarkProbe(stack.openSearch().client()).seedForFullHistoricalScan();
        LOG.info("Stack ready. {} new nodes populated; batch-indexing will start a full historical scan "
                + "(dump baseline ~1100 + perf-populate {}) on its next poll.", numberOfNodes, numberOfNodes);
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
    void indexingThroughputBaseline() throws IOException, InterruptedException
    {
        Path csv = outputDir.resolve("capacity-" + numberOfNodes + "-" + Instant.now().toEpochMilli() + ".csv");
        Instant t0 = Instant.now();
        Instant deadline = t0.plus(INDEXING_UPPER_BOUND);
        long previousCount = 0;
        long peakDocsPerSec = 0;
        long stableCount = -1;
        int stableSamples = 0;

        try (BufferedWriter writer = Files.newBufferedWriter(csv,
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW))
        {
            writer.write("epoch_ms,iso_timestamp,elapsed_ms,doc_count,docs_per_sec_window\n");

            while (Instant.now().isBefore(deadline))
            {
                long count = alfrescoIndex.countDocuments();
                Instant now = Instant.now();
                long elapsedMs = Duration.between(t0, now).toMillis();
                long windowDelta = count - previousCount;
                long docsPerSec = windowDelta / Math.max(1, SAMPLE_INTERVAL.toSeconds());
                peakDocsPerSec = Math.max(peakDocsPerSec, docsPerSec);
                previousCount = count;

                writer.write(String.format("%d,%s,%d,%d,%d%n",
                        now.toEpochMilli(), now.toString(), elapsedMs, count, docsPerSec));
                writer.flush();

                LOG.info("[{}s] count={} windowRate={}/s peak={}/s",
                        elapsedMs / 1000, count, docsPerSec, peakDocsPerSec);

                // Plateau detector. Count has stopped growing → batch-indexing drained the queue.
                // Only ~90% of perf-populate's rows become searchable docs (containers, system rows,
                // etc. are filtered), so we can't gate on count >= numberOfNodes. Requiring count > 0
                // is enough: count is monotonically non-decreasing in this test, so any non-zero
                // means batch-indexing has run at least one cycle.
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

            Duration elapsed = Duration.between(t0, Instant.now());
            long finalCount = alfrescoIndex.countDocuments();
            long avgDocsPerSec = elapsed.toSeconds() == 0 ? finalCount : finalCount / elapsed.toSeconds();

            LOG.info("CAPACITY BASELINE: {} nodes populated by perf-populate, {} docs in ES at drain, "
                    + "wall {} (avg {}/s, peak 5s-window {}/s). CSV: {}",
                    numberOfNodes, finalCount, elapsed, avgDocsPerSec, peakDocsPerSec, csv);

            // Loose floor: ~90% of perf-populate's rows become searchable docs in this dataset
            // (containers, system rows, and a few node types are filtered by the reindex job).
            // 50% is a sanity bound — catches "nothing indexed" without overfitting to the ratio.
            long minAcceptable = numberOfNodes / 2;
            assertThat(finalCount)
                    .as("ES doc count (%d) should be at least 50%% of numberOfNodes (%d). CSV: %s",
                            finalCount, numberOfNodes, csv)
                    .isGreaterThanOrEqualTo(minAcceptable);
            assertThat(elapsed)
                    .as("drain time exceeded the loose upper bound. CSV: %s", csv)
                    .isLessThan(INDEXING_UPPER_BOUND);
        }
    }

    private static Path materialiseConfig(int nodes) throws IOException
    {
        try (InputStream is = CapacityReliabilityIT.class.getResourceAsStream(
                "/docker/perf-populate-config.template.json"))
        {
            assertThat(is).as("perf-populate-config.template.json must be on the test classpath").isNotNull();
            String template = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            String rendered = template.replace("${number_of_nodes}", Integer.toString(nodes));
            Path target = outputDir.resolve("perf-populate-config-" + nodes + ".json");
            Files.writeString(target, rendered, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return target;
        }
    }
}
