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
import static org.awaitility.Awaitility.await;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.wait.strategy.Wait;

import org.alfresco.elasticsearch.batchindexing.reliability.capacity.JolokiaProbe.GcStats;
import org.alfresco.elasticsearch.batchindexing.reliability.capacity.JolokiaProbe.HeapSnapshot;
import org.alfresco.elasticsearch.batchindexing.reliability.harness.AlfrescoIndexHelper;
import org.alfresco.elasticsearch.batchindexing.reliability.harness.BaseReliabilityIT;
import org.alfresco.elasticsearch.batchindexing.reliability.harness.DistributionEnv;
import org.alfresco.elasticsearch.batchindexing.reliability.harness.ReliabilityStack;
import org.alfresco.elasticsearch.batchindexing.reliability.harness.WatermarkProbe;

/**
 * Steady-load memory reliability test. Detects leaks by shape, not by magnitude.
 * <p>
 * Boots the packaged batch-indexing image against Postgres seeded with a small Alfresco DB dump and Elasticsearch. Once the initial indexing pass completes, the indexer keeps polling every 5s (continuous mode) with nothing new to do. Each poll cycle creates a Spring Batch JOB_INSTANCE in the in-memory HSQLDB, which is the workload we want to observe.
 * <p>
 * The test collects two kinds of readings over a 20-minute soak:
 * <ul>
 * <li><b>Raw samples</b> every 30s — heap, non-heap, GC counters. Written to a per-run CSV under {@code target/reliability/}.</li>
 * <li><b>Post-GC anchors</b> every 60s — a forced Full GC + 5s settle followed by a heap/non-heap read.</li>
 * </ul>
 * The pass/fail decision compares the mean of the first ten anchors against the mean of the last ten. Failing when the second half exceeds the first by more than 10% of the JVM's Xmx catches a rising retained set (an actual leak) while ignoring:
 * <ul>
 * <li>Baseline shifts from Spring bumps or new subsystems — both halves shift equally so the delta stays at zero.</li>
 * <li>G1 young-GC sawtooth — anchors are always post-GC, so raw sample variance is scrubbed.</li>
 * <li>Transient allocation bursts from new error-handling paths — visible in raw samples but gone by anchor time.</li>
 * </ul>
 * The 10% figure is a fraction of Xmx, not an absolute MB value, so raising or lowering {@code mem_limit} in the compose file needs no recalibration here.
 * <p>
 * Gated by {@code RELIABILITY_TESTS=true} (inherited from {@link BaseReliabilityIT}) and the {@code reliability-tests} Maven profile.
 */
@Tag("capacity")
@SuppressWarnings("PMD.UnitTestShouldIncludeAssert")
public class MemoryReliabilityIT extends BaseReliabilityIT
{
    private static final Logger LOG = LoggerFactory.getLogger(MemoryReliabilityIT.class);

    private static final int JOLOKIA_PORT = 8778;

    // Twenty forced-GC anchors, one per minute. 20-minute soak by default.
    // Overridable via -Dreliability.memory.anchorCount=<n>.
    private static final int DEFAULT_ANCHOR_COUNT = 20;
    private static final Duration ANCHOR_INTERVAL = Duration.ofSeconds(60);
    private static final Duration RAW_SAMPLE_INTERVAL = Duration.ofSeconds(30);
    private static final Duration GC_SETTLE = Duration.ofSeconds(5);

    // Leak = second-half anchor mean drifted from first-half by more than this fraction of jvmHeapMax
    private static final double LEAK_DRIFT_FRACTION_OF_XMX = 0.10;

    // Non-fatal warning: peak (raw, between GCs) sat above this fraction of jvmHeapMax at some point
    private static final double PEAK_WARN_FRACTION_OF_XMX = 0.95;

    // Fail fast test on jolokia read errors
    private static final int MAX_CONSECUTIVE_PROBE_FAILURES = 3;

    private static ReliabilityStack stack;
    private static AlfrescoIndexHelper alfrescoIndex;
    private static JolokiaProbe jolokiaProbe;
    private static Path outputDir;

    @BeforeAll
    static void startStack() throws IOException, InterruptedException
    {
        outputDir = DistributionEnv.outputDir();
        Path jolokiaAgent = DistributionEnv.jolokiaAgentPath();

        stack = ReliabilityStack.builder(LOG, "batch-indexing-reliability-memory-compose.yaml")
                .env("JOLOKIA_AGENT_PATH", jolokiaAgent.toAbsolutePath().toString())
                .customize(cc -> cc.withExposedService(ReliabilityStack.BATCH_INDEXING_SERVICE, JOLOKIA_PORT,
                        Wait.forHttp("/jolokia/version")
                                .forStatusCode(200)
                                .forPort(JOLOKIA_PORT)
                                .withStartupTimeout(Duration.ofMinutes(3))))
                .start();

        alfrescoIndex = new AlfrescoIndexHelper(stack.openSearch().client());

        alfrescoIndex.createIfMissing();
        new WatermarkProbe(stack.openSearch().client()).seedForFullHistoricalScan();

        int jolokiaHostPort = stack.compose().getServicePort(ReliabilityStack.BATCH_INDEXING_SERVICE, JOLOKIA_PORT);
        jolokiaProbe = new JolokiaProbe("localhost", jolokiaHostPort);

        LOG.info("Stack up. Waiting for initial indexing pass to settle before sampling heap.");
        await().atMost(Duration.ofMinutes(3))
                .pollInterval(Duration.ofSeconds(5))
                .until(() -> alfrescoIndex.countDocuments() > 900);

        // The initial full historical scan leaves transient allocations on heap
        // that take a couple of young GCs to clear. Force one GC before the
        // sampling loop starts so anchor 0 sees the true steady-state baseline.
        jolokiaProbe.triggerGc();
        Thread.sleep(GC_SETTLE.toMillis());
        LOG.info("Initial indexing pass complete and heap quiesced. Starting soak.");
    }

    @AfterAll
    static void tearDown() throws IOException
    {
        if (jolokiaProbe != null)
        {
            jolokiaProbe.close();
        }
        if (stack != null)
        {
            stack.close();
        }
    }

    @Test
    void heapPostGcBaselineDoesNotDriftUnderSteadyLoad() throws IOException, InterruptedException
    {
        int anchorCount = Integer.getInteger("reliability.memory.anchorCount", DEFAULT_ANCHOR_COUNT);
        Path csv = outputDir.resolve("memory-" + Instant.now().toEpochMilli() + ".csv");

        Timeline timeline = SampleCollector.of(jolokiaProbe, csv, LOG).run(anchorCount);
        HeapTrend heapTrend = HeapTrend.analyze(timeline.heapAnchorPoints());
        HeapTrend nonHeapTrend = HeapTrend.analyze(timeline.nonHeapAnchorPoints());

        long jvmHeapMaxMb = timeline.jvmHeapMaxMb();
        long peakHeapMb = timeline.peakHeapMb();

        LOG.info("Memory soak done. samples={} anchors={} skipped={} jvmHeapMax={}MB peak={}MB\n"
                + "  heap-trend    : {}\n"
                + "  nonheap-trend : {}\n"
                + "  csv           : {}",
                timeline.totalSamples(), timeline.anchorCount(), timeline.skippedSamples(),
                jvmHeapMaxMb, peakHeapMb, heapTrend, nonHeapTrend, csv);

        warnIfPeakNearCeiling(peakHeapMb, jvmHeapMaxMb);

        assertNoBaselineDrift("heap", heapTrend, jvmHeapMaxMb, csv);
        assertNoBaselineDrift("non-heap", nonHeapTrend, jvmHeapMaxMb, csv);
    }

    private static void warnIfPeakNearCeiling(long peakHeapMb, long jvmHeapMaxMb)
    {
        long peakWarnMb = (long) (jvmHeapMaxMb * PEAK_WARN_FRACTION_OF_XMX);
        if (peakHeapMb > peakWarnMb)
        {
            LOG.warn("[peak-heap-warn] Observed peak raw heap {}MB is above {}% of jvmHeapMax ({}MB). "
                    + "Service is close to the container's memory ceiling.",
                    peakHeapMb, (int) (PEAK_WARN_FRACTION_OF_XMX * 100), jvmHeapMaxMb);
        }
    }

    private static void assertNoBaselineDrift(String label, HeapTrend trend, long jvmHeapMaxMb, Path csv)
    {
        long driftLimitMb = (long) (jvmHeapMaxMb * LEAK_DRIFT_FRACTION_OF_XMX);
        long driftMb = trend.secondHalfMeanMb() - trend.firstHalfMeanMb();
        assertThat(driftMb)
                .as("Post-GC %s baseline drifted between the first and second halves of the soak by "
                        + "more than %d%% of jvmHeapMax (%dMB). Trend: %s. CSV: %s",
                        label, (int) (LEAK_DRIFT_FRACTION_OF_XMX * 100), driftLimitMb, trend, csv)
                .isLessThanOrEqualTo(driftLimitMb);
    }

    /** One CSV row. Raw or anchor. */
    private record Sample(Instant time, HeapSnapshot heap, HeapSnapshot nonHeap,
            long gcCount, long gcTimeMs, boolean isAnchor)
    {}

    /** Full record of one soak run: every raw and anchor sample, plus how many probe reads were skipped. */
    private record Timeline(List<Sample> samples, int skippedSamples)
    {
        int totalSamples()
        {
            return samples.size();
        }

        int anchorCount()
        {
            return (int) samples.stream().filter(Sample::isAnchor).count();
        }

        long jvmHeapMaxMb()
        {
            return samples.stream().mapToLong(s -> s.heap().max() / (1024L * 1024L)).max().orElse(-1);
        }

        long peakHeapMb()
        {
            return samples.stream().mapToLong(s -> s.heap().usedMb()).max().orElse(0);
        }

        List<AnchorPoint> heapAnchorPoints()
        {
            return samples.stream().filter(Sample::isAnchor)
                    .map(s -> new AnchorPoint(s.time(), s.heap().usedMb())).toList();
        }

        List<AnchorPoint> nonHeapAnchorPoints()
        {
            return samples.stream().filter(Sample::isAnchor)
                    .map(s -> new AnchorPoint(s.time(), s.nonHeap().used() / (1024L * 1024L))).toList();
        }
    }

    /** One post-GC anchor reading: wall-clock time and the MB value at that time. */
    private record AnchorPoint(Instant time, long mb)
    {}

    /**
     * Runs the sample-and-anchor loop, writes CSV, returns the {@link Timeline}. Every iteration is one raw sample; every second iteration is preceded by a forced GC + settle and the reading is marked as an anchor.
     */
    private static final class SampleCollector
    {
        private static final String CSV_HEADER = "epoch_ms,iso_timestamp,is_anchor,heap_used_bytes,heap_committed_bytes,"
                + "heap_max_bytes,nonheap_used_bytes,gc_total_count,gc_total_time_ms\n";

        private final JolokiaProbe probe;
        private final Path csvPath;
        private final Logger log;

        private SampleCollector(JolokiaProbe probe, Path csvPath, Logger log)
        {
            this.probe = probe;
            this.csvPath = csvPath;
            this.log = log;
        }

        static SampleCollector of(JolokiaProbe probe, Path csvPath, Logger log)
        {
            return new SampleCollector(probe, csvPath, log);
        }

        Timeline run(int targetAnchors) throws IOException, InterruptedException
        {
            try (BufferedWriter writer = Files.newBufferedWriter(csvPath,
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW))
            {
                writer.write(CSV_HEADER);

                List<Sample> samples = new ArrayList<>();
                int anchorsCollected = 0;
                int consecutiveFailures = 0;
                int skipped = 0;
                int iteration = 0;

                // Anchor cadence: one anchor per ANCHOR_INTERVAL. Between anchors
                // we take RAW_SAMPLE_INTERVAL-spaced raw readings for CSV richness.
                int rawSamplesPerAnchor = (int) (ANCHOR_INTERVAL.toMillis() / RAW_SAMPLE_INTERVAL.toMillis());

                while (anchorsCollected < targetAnchors)
                {
                    boolean isAnchor = iteration > 0 && iteration % rawSamplesPerAnchor == 0;

                    if (isAnchor)
                    {
                        probe.triggerGc();
                        Thread.sleep(GC_SETTLE.toMillis());
                    }

                    Sample sample = tryRead(isAnchor);
                    if (sample == null)
                    {
                        consecutiveFailures++;
                        skipped++;
                        log.warn("Jolokia probe failed at iteration {} (consecutive failures={}). Skipping.",
                                iteration, consecutiveFailures);
                        if (consecutiveFailures > MAX_CONSECUTIVE_PROBE_FAILURES)
                        {
                            throw new IOException("Jolokia probe failed " + consecutiveFailures
                                    + " reads in a row (limit " + MAX_CONSECUTIVE_PROBE_FAILURES
                                    + "). Aborting rather than passing a soak with no signal.");
                        }
                    }
                    else
                    {
                        consecutiveFailures = 0;
                        samples.add(sample);
                        writeRow(writer, sample);
                        if (isAnchor)
                        {
                            anchorsCollected++;
                            log.info("[anchor {}/{}] heap={}MB nonheap={}MB (post-GC)",
                                    anchorsCollected, targetAnchors,
                                    sample.heap().usedMb(),
                                    sample.nonHeap().used() / (1024 * 1024));
                        }
                    }

                    iteration++;
                    if (anchorsCollected < targetAnchors)
                    {
                        Thread.sleep(RAW_SAMPLE_INTERVAL.toMillis());
                    }
                }

                if (samples.stream().noneMatch(Sample::isAnchor))
                {
                    throw new IOException("Soak finished with no anchor samples collected; "
                            + skipped + " read(s) were skipped due to probe failures.");
                }
                return new Timeline(samples, skipped);
            }
        }

        private Sample tryRead(boolean isAnchor)
        {
            try
            {
                HeapSnapshot heap = probe.readHeap();
                HeapSnapshot nonHeap = probe.readNonHeap();
                Map<String, GcStats> gc = probe.readGcStats();
                long gcCount = gc.values().stream().mapToLong(GcStats::count).sum();
                long gcTimeMs = gc.values().stream().mapToLong(GcStats::timeMs).sum();
                return new Sample(Instant.now(), heap, nonHeap, gcCount, gcTimeMs, isAnchor);
            }
            catch (IOException e)
            {
                log.warn("Jolokia read failed: {}", e.toString());
                return null;
            }
        }

        private static void writeRow(BufferedWriter writer, Sample s) throws IOException
        {
            writer.write(String.format("%d,%s,%s,%d,%d,%d,%d,%d,%d%n",
                    s.time().toEpochMilli(),
                    s.time().toString(),
                    s.isAnchor(),
                    s.heap().used(),
                    s.heap().committed(),
                    s.heap().max(),
                    s.nonHeap().used(),
                    s.gcCount(),
                    s.gcTimeMs()));
            writer.flush();
        }
    }

    /**
     * Retained-set analysis of one series of post-GC anchor readings. Computes half-vs-half mean (the assertion signal) plus slope and R² of a linear fit for diagnostic logging.
     */
    private record HeapTrend(int sampleCount, long firstHalfMeanMb, long secondHalfMeanMb,
            double slopeMbPerMin, double rSquared)
    {
        static HeapTrend analyze(List<AnchorPoint> anchors)
        {
            int n = anchors.size();
            if (n < 2)
            {
                return new HeapTrend(n, 0, 0, 0.0, 0.0);
            }

            int half = n / 2;
            double firstMean = meanMb(anchors.subList(0, half));
            double secondMean = meanMb(anchors.subList(n - half, n));

            Instant t0 = anchors.get(0).time();
            double meanX = anchors.stream()
                    .mapToDouble(a -> minutesBetween(t0, a.time()))
                    .average().orElse(0.0);
            double meanY = anchors.stream().mapToLong(AnchorPoint::mb).average().orElse(0.0);
            double num = 0.0;
            double den = 0.0;
            double ssTot = 0.0;
            for (AnchorPoint a : anchors)
            {
                double x = minutesBetween(t0, a.time());
                double y = a.mb();
                num += (x - meanX) * (y - meanY);
                den += (x - meanX) * (x - meanX);
                ssTot += (y - meanY) * (y - meanY);
            }
            double slope = den == 0.0 ? 0.0 : num / den;
            double intercept = meanY - slope * meanX;
            double ssRes = 0.0;
            for (AnchorPoint a : anchors)
            {
                double predicted = intercept + slope * minutesBetween(t0, a.time());
                double residual = a.mb() - predicted;
                ssRes += residual * residual;
            }
            double rSq = ssTot == 0.0 ? 1.0 : 1.0 - ssRes / ssTot;

            return new HeapTrend(n, Math.round(firstMean), Math.round(secondMean), slope, rSq);
        }

        private static double minutesBetween(Instant a, Instant b)
        {
            return (b.toEpochMilli() - a.toEpochMilli()) / 60_000.0;
        }

        private static double meanMb(List<AnchorPoint> anchors)
        {
            return anchors.stream().mapToLong(AnchorPoint::mb).average().orElse(0.0);
        }

        @Override
        public String toString()
        {
            return String.format(
                    "firstHalfMean=%dMB secondHalfMean=%dMB delta=%dMB slope=%.2fMB/min R\u00b2=%.3f (anchors=%d)",
                    firstHalfMeanMb, secondHalfMeanMb, secondHalfMeanMb - firstHalfMeanMb,
                    slopeMbPerMin, rSquared, sampleCount);
        }
    }
}
