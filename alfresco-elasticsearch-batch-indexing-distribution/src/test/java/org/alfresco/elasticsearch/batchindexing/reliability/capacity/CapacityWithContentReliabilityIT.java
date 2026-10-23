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
package org.alfresco.elasticsearch.batchindexing.reliability.capacity;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedWriter;
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
 * Counterpart to {@link CapacityReliabilityIT} that exercises the content path end-to-end: perf-populate writes real binaries (txt / pdf / docx mix) into a shared content store, ACS reads them, ACS calls transform-core-aio, and batch-indexing fetches the extracted text via ACS's {@code /alfresco/service/api/solr/textContent} endpoint.
 * <p>
 * The stack is significantly larger than the metadata-only capacity IT — it adds ACS Community 26.1.0, ActiveMQ, and a shared {@code alf_data} volume. The dump preload is intentionally dropped because ACS will bootstrap its own fresh schema (the dump would force a 23.x → 26.x migration that we do not want to time as part of the indexing throughput number).
 * <p>
 * Default N = 1 000 (override with {@code -Dreliability.capacity.numberOfNodes=N}). Lower than the metadata- only IT because the round-trip per content node is the order of magnitude slower than direct Postgres reads.
 * <p>
 * Gated by {@code RELIABILITY_TESTS=true} (inherited from {@link BaseReliabilityIT}) and the {@code reliability-tests} Maven profile. Same image precondition as the metadata IT: build {@code alfresco-elasticsearch-batch-indexing:<version>} locally first.
 */
@Tag("capacity")
@SuppressWarnings("PMD.UnitTestShouldIncludeAssert")
public class CapacityWithContentReliabilityIT extends BaseReliabilityIT
{
    private static final Logger LOG = LoggerFactory.getLogger(CapacityWithContentReliabilityIT.class);

    private static final String ALFRESCO_SERVICE = "alfresco-1";
    private static final String TRANSFORM_SERVICE = "transform-core-aio-1";
    private static final String PERF_POPULATE_SERVICE = "perf-populate-1";

    /** Wall-time upper bound on the indexing phase. Content path is slower than metadata-only. */
    private static final Duration INDEXING_UPPER_BOUND = Duration.ofMinutes(60);
    /** Stop the sample loop once doc count has not grown for this many consecutive samples. */
    private static final int PLATEAU_SAMPLES = 3;
    private static final Duration SAMPLE_INTERVAL = Duration.ofSeconds(10);

    private static ReliabilityStack stack;
    private static AlfrescoIndexHelper alfrescoIndex;
    private static Path outputDir;
    private static int numberOfNodes;

    @BeforeAll
    static void startStack() throws IOException
    {
        numberOfNodes = Integer.parseInt(
                System.getProperty("reliability.capacity.numberOfNodes", "1000"));
        outputDir = DistributionEnv.outputDir();

        Path perfPopulateConfig = materialiseConfig(numberOfNodes);

        LOG.info("Starting capacity-with-content stack: numberOfNodes={}, perf-populate config={}",
                numberOfNodes, perfPopulateConfig);

        stack = ReliabilityStack.builder(LOG, "batch-indexing-reliability-capacity-content-compose.yaml")
                .env("PERF_POPULATE_CONFIG_PATH", perfPopulateConfig.toAbsolutePath().toString())
                .withoutAlfrescoDumpSql()
                .esStartupTimeout(Duration.ofMinutes(30))
                .customize(cc -> {
                    cc.withLogConsumer(ALFRESCO_SERVICE, new Slf4jLogConsumer(LOG).withPrefix("alfresco"));
                    cc.withLogConsumer(TRANSFORM_SERVICE, new Slf4jLogConsumer(LOG).withPrefix("transform"));
                    cc.withLogConsumer(PERF_POPULATE_SERVICE, new Slf4jLogConsumer(LOG).withPrefix("perf-populate"));
                })
                .start();

        alfrescoIndex = new AlfrescoIndexHelper(stack.openSearch().client());

        alfrescoIndex.createIfMissing();
        new WatermarkProbe(stack.openSearch().client()).seedForFullHistoricalScan();
        LOG.info("Stack ready. {} content nodes will be populated by perf-populate after ACS bootstrap; "
                + "batch-indexing then starts a full historical scan via ACS textContent.", numberOfNodes);
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
    void contentIndexingThroughputBaseline() throws IOException, InterruptedException
    {
        Path csv = outputDir.resolve(
                "capacity-content-" + numberOfNodes + "-" + Instant.now().toEpochMilli() + ".csv");
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

            LOG.info("CAPACITY CONTENT BASELINE: {} perf-populate nodes (with content), {} docs in ES at drain, "
                    + "wall {} (avg {}/s, peak {}-s window {}/s). CSV: {}",
                    numberOfNodes, finalCount, elapsed, avgDocsPerSec,
                    SAMPLE_INTERVAL.toSeconds(), peakDocsPerSec, csv);

            // Loose 50 % floor — same shape as the metadata IT. Catches "nothing indexed".
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
        try (InputStream is = CapacityWithContentReliabilityIT.class.getResourceAsStream(
                "/docker/perf-populate-content-config.template.json"))
        {
            assertThat(is)
                    .as("perf-populate-content-config.template.json must be on the test classpath")
                    .isNotNull();
            String template = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            String rendered = template.replace("${number_of_nodes}", Integer.toString(nodes));
            Path target = outputDir.resolve("perf-populate-content-config-es-" + nodes + ".json");
            Files.writeString(target, rendered, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return target;
        }
    }
}
