/*-
 * #%L
 * Alfresco Search Community :: Reindexing Core
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
package org.alfresco.reindexing.writers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.alfresco.metrics.MetricsConstants;

/**
 * Retains, per writer, the running {@code count/total/min/max} plus a bounded random sample of committed-chunk durations for the life of a step execution and, when the step finishes, logs a per-writer summary ({@code count/total/min/max/avg/median/p95/p99}). This mirrors the percentiles already published to Micrometer ({@code alfresco.reindex.chunk.write.duration}) but in a single, human-readable line per writer.
 * <p>
 * {@code count/total/min/max/avg} are exact and maintained incrementally; the percentiles are estimated from a fixed-size reservoir sample (Vitter's Algorithm R) so heap usage stays bounded regardless of how many chunks a large reindex commits (they are exact while the number of samples stays at or below {@link #MAX_RESERVOIR_SAMPLES}). Samples are fed once per committed chunk from {@link org.alfresco.reindexing.listeners.WriteMetricsFlushListener} (so retries are not double-counted) and are thread-safe because a multithreaded step records from several chunk threads concurrently. Controlled by {@code alfresco.reindex.writerTimingSummaryLogEnabled}.
 */
public class WriterTimingSummary
{
    private static final Logger LOGGER = LoggerFactory.getLogger(WriterTimingSummary.class);

    /** Upper bound on retained samples per writer; caps heap use on very large reindexes while keeping percentiles exact for typical runs. */
    static final int MAX_RESERVOIR_SAMPLES = 50_000;

    // Preserve a stable, readable ordering and map the internal module key to the writer class name.
    private static final Map<String, String> MODULE_LABELS = new LinkedHashMap<>();
    static
    {
        MODULE_LABELS.put(MetricsConstants.METADATA, "ElasticsearchRepoEventItemWriter");
        MODULE_LABELS.put(MetricsConstants.PATH, "ElasticsearchPathWriter");
        MODULE_LABELS.put(MetricsConstants.CONTENT, "ContentTransformEventWriter");
        MODULE_LABELS.put(MetricsConstants.MERGED, "ElasticsearchMergedReindexWriter");
    }

    private final boolean enabled;
    private final Map<String, ModuleStats> statsByModule = new ConcurrentHashMap<>();

    public WriterTimingSummary(boolean enabled)
    {
        this.enabled = enabled;
    }

    public void record(String module, long durationMs)
    {
        if (enabled)
        {
            statsByModule.computeIfAbsent(module, k -> new ModuleStats()).record(durationMs);
        }
    }

    /**
     * Logs one summary line per writer that collected samples and clears the retained samples so the next step execution starts fresh.
     *
     * @param context
     *            a short label (e.g. step name / job execution id) identifying which run these numbers belong to
     */
    public void logSummaryAndReset(String context)
    {
        for (String line : buildSummaryAndReset(context))
        {
            LOGGER.info("{}", line);
        }
    }

    /**
     * Computes one summary line per writer that collected samples and clears the retained samples. Returns an empty list when disabled or when nothing was recorded. Package-visible for testing.
     */
    List<String> buildSummaryAndReset(String context)
    {
        if (!enabled || statsByModule.isEmpty())
        {
            return List.of();
        }

        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, String> labelled : MODULE_LABELS.entrySet())
        {
            addLine(lines, context, labelled.getKey(), labelled.getValue());
        }
        // Include any module without a predefined label (defensive; keeps unexpected keys visible).
        for (String module : List.copyOf(statsByModule.keySet()))
        {
            addLine(lines, context, module, module);
        }
        statsByModule.clear();
        return lines;
    }

    private void addLine(List<String> lines, String context, String module, String label)
    {
        ModuleStats stats = statsByModule.remove(module);
        if (stats == null)
        {
            return;
        }

        ModuleStats.Snapshot snapshot = stats.snapshot();
        if (snapshot.count() == 0)
        {
            return;
        }

        long[] sample = snapshot.sample();
        Arrays.sort(sample);
        double avg = (double) snapshot.total() / snapshot.count();

        lines.add(String.format(Locale.ROOT,
                "[%s] Write timing statistics (ms) for %s: count=%d, total=%d, min=%d, max=%d, "
                        + "avg=%.1f, median=%d, p95=%d, p99=%d",
                label, context, snapshot.count(), snapshot.total(), snapshot.min(), snapshot.max(), avg,
                percentile(sample, 50), percentile(sample, 95), percentile(sample, 99)));
    }

    /** Nearest-rank percentile on an already-sorted array. */
    private static long percentile(long[] sorted, int percentile)
    {
        int n = sorted.length;
        if (n == 0)
        {
            return 0;
        }
        int rank = (int) Math.ceil(percentile / 100.0 * n);
        int index = Math.min(Math.max(rank, 1), n) - 1;
        return sorted[index];
    }

    /**
     * Per-writer accumulator: exact {@code count/total/min/max} plus a bounded reservoir sample for percentile estimation. All access is guarded by the instance monitor because several chunk threads may record concurrently.
     */
    private static final class ModuleStats
    {
        private long count;
        private long total;
        private long min = Long.MAX_VALUE;
        private long max = Long.MIN_VALUE;
        private final long[] reservoir = new long[MAX_RESERVOIR_SAMPLES];
        private int reservoirSize;

        synchronized void record(long value)
        {
            count++;
            total += value;
            if (value < min)
            {
                min = value;
            }
            if (value > max)
            {
                max = value;
            }
            if (reservoirSize < reservoir.length)
            {
                reservoir[reservoirSize++] = value;
            }
            else
            {
                // Algorithm R: the n-th sample replaces a uniformly random slot with probability capacity/n.
                long slot = ThreadLocalRandom.current().nextLong(count);
                if (slot < reservoir.length)
                {
                    reservoir[(int) slot] = value;
                }
            }
        }

        synchronized Snapshot snapshot()
        {
            return new Snapshot(count, total, min, max, Arrays.copyOf(reservoir, reservoirSize));
        }

        private record Snapshot(long count, long total, long min, long max, long[] sample)
        {}
    }
}
