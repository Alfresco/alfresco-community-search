/*-
 * #%L
 * Alfresco Search Community :: Search Index Model
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
package org.alfresco.metrics;

import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;

public class DefaultReindexingMetrics implements ReindexingMetrics
{
    /**
     * Default number of most-recent job executions whose metrics are retained.
     * <p>
     * Continuous (batch-indexing) mode launches a brand-new Spring Batch job execution — hence a new {@code jobId} tag — on every polling cycle, forever. Micrometer keeps every registered meter for the life of the JVM, so retaining all of them grows the heap without bound. Only the most recent {@code maxRetainedJobs} jobs' meters are kept; older ones are removed from the registry.
     * <p>
     * A value {@code <= 0} disables the cap and retains every job's metrics (unbounded / "infinity"). This can be selected in deployments that run a finite, one-shot reindex and want the full per-job history exposed.
     */
    public static final int DEFAULT_MAX_RETAINED_JOBS = 50;

    private final MeterRegistry registry;
    private final int maxRetainedJobs;
    private volatile long jobExecutionId;

    // Store job timing information
    private final Map<String, AtomicLong> jobStartTimes = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> jobEndTimes = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> jobStatus = new ConcurrentHashMap<>();

    // Cached counters and timers to avoid registry lookups on every recording
    private final Map<String, Counter> readCounters = new ConcurrentHashMap<>();
    private final Map<String, Counter> filteredCounters = new ConcurrentHashMap<>();
    private final Map<String, Counter> retryCounters = new ConcurrentHashMap<>();
    private final Map<String, Counter> writeCounters = new ConcurrentHashMap<>();
    private final Map<String, Timer> chunkWriteTimers = new ConcurrentHashMap<>();
    // Tagged by namespace URI and deliberately not by job: an unresolved namespace is a
    // configuration problem, so the useful number is cumulative per URI. Bounded by the
    // number of namespaces, so it needs no eviction.
    private final Map<String, Counter> unresolvedNamespaceCounters = new ConcurrentHashMap<>();

    // Meters registered per jobId, so that when a job is evicted its meters can be
    // removed from the registry (otherwise Micrometer retains them for the JVM's life).
    private final Map<String, Set<Meter.Id>> meterIdsByJob = new ConcurrentHashMap<>();
    // Job keys in first-seen order (oldest first) driving FIFO eviction.
    private final Queue<String> jobEvictionOrder = new ConcurrentLinkedQueue<>();

    public DefaultReindexingMetrics(MeterRegistry registry)
    {
        this(registry, DEFAULT_MAX_RETAINED_JOBS);
    }

    public DefaultReindexingMetrics(MeterRegistry registry, int maxRetainedJobs)
    {
        this.registry = registry;
        this.maxRetainedJobs = maxRetainedJobs;
    }

    @Override
    public void setJobExecutionId(long jobExecutionId)
    {
        this.jobExecutionId = jobExecutionId;
    }

    @Override
    public void recordJobStart()
    {
        String key = jobKey();
        AtomicLong startTime = jobStartTimes.computeIfAbsent(key, k -> {
            AtomicLong value = new AtomicLong(System.currentTimeMillis());
            track(key, Gauge.builder(MetricsConstants.REINDEX_JOB_START_TIME, value, AtomicLong::doubleValue)
                    .tags(MetricsConstants.JOB_ID_TAG, key)
                    .description("Unix timestamp (milliseconds) when reindex job started")
                    .register(registry));
            return value;
        });
        startTime.set(System.currentTimeMillis());

        // Set status to running (1 = running, 2 = completed, 3 = failed, 4 = stopped)
        AtomicLong status = jobStatus.computeIfAbsent(key, k -> {
            AtomicLong value = new AtomicLong(1);
            track(key, Gauge.builder(MetricsConstants.REINDEX_JOB_STATUS, value, AtomicLong::doubleValue)
                    .tags(MetricsConstants.JOB_ID_TAG, key)
                    .description("Job status: 1=running, 2=completed, 3=failed, 4=stopped")
                    .register(registry));
            return value;
        });
        status.set(1); // Running
    }

    @Override
    public void recordJobComplete()
    {
        recordJobEnd(2); // 2 = completed
    }

    @Override
    public void recordJobFailed()
    {
        recordJobEnd(3); // 3 = failed
    }

    @Override
    public void recordJobStopped()
    {
        recordJobEnd(4); // 4 = stopped
    }

    private void recordJobEnd(long statusCode)
    {
        String key = jobKey();
        long timestamp = System.currentTimeMillis();

        // Record end time
        AtomicLong endTime = jobEndTimes.computeIfAbsent(key, k -> {
            AtomicLong value = new AtomicLong(timestamp);
            track(key, Gauge.builder(MetricsConstants.REINDEX_JOB_END_TIME, value, AtomicLong::doubleValue)
                    .tags(MetricsConstants.JOB_ID_TAG, key)
                    .description("Unix timestamp (milliseconds) when reindex job ended")
                    .register(registry));
            return value;
        });
        endTime.set(timestamp);

        // Update status
        AtomicLong status = jobStatus.get(key);
        if (status != null)
        {
            status.set(statusCode);
        }
    }

    @Override
    public void preRegisterStepMetrics(List<String> enabledWriters)
    {
        String key = jobKey();
        Tags jobTags = jobIdTag();

        track(key, registry.counter(MetricsConstants.REINDEX_READ_METRIC, jobTags));
        track(key, registry.counter(MetricsConstants.REINDEX_FILTERED_METRIC, jobTags));
        track(key, registry.counter(MetricsConstants.REINDEX_RETRIES_METRIC, jobTags));

        for (String module : enabledWriters)
        {
            Tags moduleTags = jobTags.and(MetricsConstants.MODULE_TAG, module);
            track(key, registry.counter(MetricsConstants.REINDEX_WRITE_METRIC,
                    moduleTags.and(MetricsConstants.OUTCOME_TAG, MetricsConstants.OUTCOME_SUCCESS)));
            track(key, registry.counter(MetricsConstants.REINDEX_WRITE_METRIC,
                    moduleTags.and(MetricsConstants.OUTCOME_TAG, MetricsConstants.OUTCOME_FAILURE)));
            track(key, Timer.builder(MetricsConstants.REINDEX_CHUNK_WRITE_DURATION_METRIC)
                    .tags(moduleTags)
                    .publishPercentiles(0.50, 0.95, 0.99)
                    .register(registry));
        }
    }

    @Override
    public void reindexRead()
    {
        String key = jobKey();
        Counter counter = readCounters.computeIfAbsent(key, k -> track(k, registry.counter(MetricsConstants.REINDEX_READ_METRIC, jobIdTag())));
        counter.increment();
    }

    @Override
    public void reindexFiltered()
    {
        String key = jobKey();
        Counter counter = filteredCounters.computeIfAbsent(key, k -> track(k, registry.counter(MetricsConstants.REINDEX_FILTERED_METRIC, jobIdTag())));
        counter.increment();
    }

    @Override
    public void reindexUnresolvedNamespace(String namespaceUri)
    {
        String uri = namespaceUri == null ? "unknown" : namespaceUri;
        unresolvedNamespaceCounters
                .computeIfAbsent(uri, k -> registry.counter(MetricsConstants.UNRESOLVED_NAMESPACE_METRIC, "uri", k))
                .increment();
    }

    @Override
    public void reindexRetry()
    {
        String key = jobKey();
        Counter counter = retryCounters.computeIfAbsent(key, k -> track(k, registry.counter(MetricsConstants.REINDEX_RETRIES_METRIC, jobIdTag())));
        counter.increment();
    }

    @Override
    public void reindexWriteSuccess(String module, long count)
    {
        recordReindexWrite(module, MetricsConstants.OUTCOME_SUCCESS, count);
    }

    @Override
    public void reindexWriteFailure(String module, long count)
    {
        recordReindexWrite(module, MetricsConstants.OUTCOME_FAILURE, count);
    }

    private void recordReindexWrite(String module, String outcome, long count)
    {
        String key = jobKey() + "." + module + "." + outcome;
        Counter counter = writeCounters.computeIfAbsent(key, k -> {
            Tags tags = jobIdTag()
                    .and(MetricsConstants.MODULE_TAG, module)
                    .and(MetricsConstants.OUTCOME_TAG, outcome);
            return track(jobKey(), registry.counter(MetricsConstants.REINDEX_WRITE_METRIC, tags));
        });
        if (count > 0L)
        {
            counter.increment(count);
        }
    }

    @Override
    public void recordChunkWriteDuration(String module, long durationMs)
    {
        String key = jobKey() + "." + module;
        Timer timer = chunkWriteTimers.computeIfAbsent(key, k -> track(jobKey(), Timer.builder(MetricsConstants.REINDEX_CHUNK_WRITE_DURATION_METRIC)
                .tags(jobIdTag().and(MetricsConstants.MODULE_TAG, module))
                .publishPercentiles(0.50, 0.95, 0.99)
                .register(registry)));
        timer.record(Math.max(0L, durationMs), TimeUnit.MILLISECONDS);
    }

    private Tags jobIdTag()
    {
        return Tags.of(MetricsConstants.JOB_ID_TAG, jobKey());
    }

    private String jobKey()
    {
        return String.valueOf(jobExecutionId);
    }

    /**
     * Records {@code meter} as belonging to {@code jobKey} so it can be removed from the registry when that job is later evicted. No-op (and no per-job bookkeeping) when the cap is disabled ({@code maxRetainedJobs <= 0}).
     */
    private <M extends Meter> M track(String jobKey, M meter)
    {
        if (maxRetainedJobs > 0)
        {
            boolean[] firstSeen = {false};
            meterIdsByJob.computeIfAbsent(jobKey, k -> {
                jobEvictionOrder.add(k);
                firstSeen[0] = true;
                return ConcurrentHashMap.newKeySet();
            }).add(meter.getId());
            // Trigger eviction whenever a brand-new job first appears
            if (firstSeen[0])
            {
                evictExcessJobs();
            }
        }
        return meter;
    }

    /**
     * Removes the oldest jobs' meters (and their bookkeeping) from the registry until at most {@code maxRetainedJobs} remain. The job currently being recorded is the newest entry, so it is never evicted.
     */
    private synchronized void evictExcessJobs()
    {
        if (maxRetainedJobs <= 0)
        {
            return;
        }
        while (jobEvictionOrder.size() > maxRetainedJobs)
        {
            String oldest = jobEvictionOrder.poll();
            if (oldest == null)
            {
                break;
            }
            Set<Meter.Id> ids = meterIdsByJob.remove(oldest);
            if (ids != null)
            {
                ids.forEach(registry::remove);
            }
            jobStartTimes.remove(oldest);
            jobEndTimes.remove(oldest);
            jobStatus.remove(oldest);

            // Remove cached meters for this job
            readCounters.remove(oldest);
            filteredCounters.remove(oldest);
            retryCounters.remove(oldest);

            // Remove write counters and timers for all module combinations
            writeCounters.keySet().removeIf(k -> k.startsWith(oldest + "."));
            chunkWriteTimers.keySet().removeIf(k -> k.startsWith(oldest + "."));
        }
    }
}
