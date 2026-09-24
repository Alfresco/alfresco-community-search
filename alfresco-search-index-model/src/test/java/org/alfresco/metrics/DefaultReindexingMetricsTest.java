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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The naming convention for the tests is: MethodName_StateUnderTest_ExpectedBehavior */
class DefaultReindexingMetricsTest
{
    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp()
    {
        registry = new SimpleMeterRegistry();
    }

    @Test
    void reindexUnresolvedNamespace_whenCalled_tagsTheCounterWithTheUri()
    {
        DefaultReindexingMetrics metrics = new DefaultReindexingMetrics(registry);
        metrics.setJobExecutionId(1L);

        metrics.reindexUnresolvedNamespace("http://acme.example.org/model/custom/1.0");
        metrics.reindexUnresolvedNamespace("http://acme.example.org/model/custom/1.0");
        metrics.reindexUnresolvedNamespace("http://other.example.org/model/1.0");

        assertThat(counterFor("http://acme.example.org/model/custom/1.0").count()).isEqualTo(2.0);
        assertThat(counterFor("http://other.example.org/model/1.0").count()).isEqualTo(1.0);
    }

    @Test
    void reindexUnresolvedNamespace_acrossJobs_keepsOneCumulativeCounter()
    {
        DefaultReindexingMetrics metrics = new DefaultReindexingMetrics(registry);

        metrics.setJobExecutionId(1L);
        metrics.reindexUnresolvedNamespace("http://acme.example.org/model/custom/1.0");
        metrics.setJobExecutionId(2L);
        metrics.reindexUnresolvedNamespace("http://acme.example.org/model/custom/1.0");

        // Deliberately not tagged by job: the useful number is cumulative per namespace.
        assertThat(registry.find(MetricsConstants.UNRESOLVED_NAMESPACE_METRIC).counters()).hasSize(1);
        assertThat(counterFor("http://acme.example.org/model/custom/1.0").count()).isEqualTo(2.0);
    }

    private Counter counterFor(String uri)
    {
        return registry.find(MetricsConstants.UNRESOLVED_NAMESPACE_METRIC).tag("uri", uri).counter();
    }

    @Test
    void reindexRead_whenCalled_incrementsJobTaggedCounter()
    {
        DefaultReindexingMetrics metrics = new DefaultReindexingMetrics(registry);
        metrics.setJobExecutionId(7L);

        metrics.reindexRead();
        metrics.reindexRead();

        assertThat(readCounter("7").count()).isEqualTo(2.0);
    }

    @Test
    void reindexFiltered_whenCalled_incrementsFilteredCounter()
    {
        DefaultReindexingMetrics metrics = new DefaultReindexingMetrics(registry);
        metrics.setJobExecutionId(1L);

        metrics.reindexFiltered();

        Counter counter = registry.find(MetricsConstants.REINDEX_FILTERED_METRIC)
                .tag(MetricsConstants.JOB_ID_TAG, "1").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void reindexRetry_whenCalled_incrementsRetriesCounter()
    {
        DefaultReindexingMetrics metrics = new DefaultReindexingMetrics(registry);
        metrics.setJobExecutionId(1L);

        metrics.reindexRetry();

        Counter counter = registry.find(MetricsConstants.REINDEX_RETRIES_METRIC)
                .tag(MetricsConstants.JOB_ID_TAG, "1").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void reindexWriteSuccess_whenCalled_recordsSuccessOutcomeCounter()
    {
        DefaultReindexingMetrics metrics = new DefaultReindexingMetrics(registry);
        metrics.setJobExecutionId(1L);

        metrics.reindexWriteSuccess(MetricsConstants.METADATA, 5);

        assertThat(writeCounter("1", MetricsConstants.METADATA, MetricsConstants.OUTCOME_SUCCESS).count())
                .isEqualTo(5.0);
    }

    @Test
    void reindexWriteFailure_whenCalled_recordsFailureOutcomeCounter()
    {
        DefaultReindexingMetrics metrics = new DefaultReindexingMetrics(registry);
        metrics.setJobExecutionId(1L);

        metrics.reindexWriteFailure(MetricsConstants.METADATA, 2);

        assertThat(writeCounter("1", MetricsConstants.METADATA, MetricsConstants.OUTCOME_FAILURE).count())
                .isEqualTo(2.0);
    }

    @Test
    void reindexWriteSuccess_withZeroCount_registersCounterButDoesNotIncrement()
    {
        DefaultReindexingMetrics metrics = new DefaultReindexingMetrics(registry);
        metrics.setJobExecutionId(9L);

        metrics.reindexWriteSuccess(MetricsConstants.PATH, 0);

        Counter counter = writeCounter("9", MetricsConstants.PATH, MetricsConstants.OUTCOME_SUCCESS);
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isZero();
    }

    @Test
    void preRegisterStepMetrics_whenCalled_registersZeroValuedMetersForEnabledWriters()
    {
        DefaultReindexingMetrics metrics = new DefaultReindexingMetrics(registry);
        metrics.setJobExecutionId(1L);

        metrics.preRegisterStepMetrics(List.of(MetricsConstants.METADATA, MetricsConstants.PATH));

        // Job-level counters pre-registered at zero.
        assertThat(readCounter("1")).isNotNull();
        assertThat(readCounter("1").count()).isZero();
        assertThat(registry.find(MetricsConstants.REINDEX_FILTERED_METRIC).tag(MetricsConstants.JOB_ID_TAG, "1").counter()).isNotNull();
        assertThat(registry.find(MetricsConstants.REINDEX_RETRIES_METRIC).tag(MetricsConstants.JOB_ID_TAG, "1").counter()).isNotNull();

        // Per-writer success/failure counters and the chunk-write timer.
        for (String module : List.of(MetricsConstants.METADATA, MetricsConstants.PATH))
        {
            assertThat(writeCounter("1", module, MetricsConstants.OUTCOME_SUCCESS)).as(module).isNotNull();
            assertThat(writeCounter("1", module, MetricsConstants.OUTCOME_FAILURE)).as(module).isNotNull();
            assertThat(registry.find(MetricsConstants.REINDEX_CHUNK_WRITE_DURATION_METRIC)
                    .tags(MetricsConstants.JOB_ID_TAG, "1", MetricsConstants.MODULE_TAG, module).timer()).as(module).isNotNull();
        }
    }

    @Test
    void preRegisterStepMetrics_thenReindexRead_incrementsSamePreRegisteredCounter()
    {
        DefaultReindexingMetrics metrics = new DefaultReindexingMetrics(registry);
        metrics.setJobExecutionId(1L);
        metrics.preRegisterStepMetrics(List.of(MetricsConstants.METADATA));

        metrics.reindexRead();

        // Pre-registration and recording must share one series, not create a duplicate.
        assertThat(registry.find(MetricsConstants.REINDEX_READ_METRIC).counters()).hasSize(1);
        assertThat(readCounter("1").count()).isEqualTo(1.0);
    }

    @Test
    void recordJobStart_whenCalled_setsRunningStatusAndStartTimestamp()
    {
        DefaultReindexingMetrics metrics = new DefaultReindexingMetrics(registry);
        metrics.setJobExecutionId(3L);

        metrics.recordJobStart();

        assertThat(jobGauge(MetricsConstants.REINDEX_JOB_STATUS, "3").value()).isEqualTo(1.0);
        assertThat(jobGauge(MetricsConstants.REINDEX_JOB_START_TIME, "3").value()).isGreaterThan(0.0);
    }

    @Test
    void recordJobComplete_whenCalled_setsCompletedStatusAndEndTimestamp()
    {
        DefaultReindexingMetrics metrics = new DefaultReindexingMetrics(registry);
        metrics.setJobExecutionId(3L);
        metrics.recordJobStart();

        metrics.recordJobComplete();

        assertThat(jobGauge(MetricsConstants.REINDEX_JOB_STATUS, "3").value()).isEqualTo(2.0);
        assertThat(jobGauge(MetricsConstants.REINDEX_JOB_END_TIME, "3").value()).isGreaterThan(0.0);
    }

    @Test
    void recordJobFailed_whenCalled_setsFailedStatus()
    {
        DefaultReindexingMetrics metrics = new DefaultReindexingMetrics(registry);
        metrics.setJobExecutionId(3L);
        metrics.recordJobStart();

        metrics.recordJobFailed();

        assertThat(jobGauge(MetricsConstants.REINDEX_JOB_STATUS, "3").value()).isEqualTo(3.0);
    }

    @Test
    void recordChunkWriteDuration_whenCalled_recordsTimerForModule()
    {
        DefaultReindexingMetrics metrics = new DefaultReindexingMetrics(registry);
        metrics.setJobExecutionId(1L);

        metrics.recordChunkWriteDuration(MetricsConstants.METADATA, 150);

        Timer timer = registry.find(MetricsConstants.REINDEX_CHUNK_WRITE_DURATION_METRIC)
                .tags(MetricsConstants.JOB_ID_TAG, "1", MetricsConstants.MODULE_TAG, MetricsConstants.METADATA)
                .timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1L);
        assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isGreaterThanOrEqualTo(150.0);
    }

    @Test
    void eviction_whenJobCountExceedsCap_removesOldestJobsMeters()
    {
        DefaultReindexingMetrics metrics = new DefaultReindexingMetrics(registry, 3);

        for (long jobId = 1; jobId <= 5; jobId++)
        {
            metrics.setJobExecutionId(jobId);
            metrics.reindexRead();
        }

        // Two oldest jobs evicted, three most recent retained.
        assertThat(readCounter("1")).isNull();
        assertThat(readCounter("2")).isNull();
        assertThat(readCounter("3")).isNotNull();
        assertThat(readCounter("4")).isNotNull();
        assertThat(readCounter("5")).isNotNull();
    }

    @Test
    void eviction_whenCapIsOne_neverRemovesCurrentJob()
    {
        DefaultReindexingMetrics metrics = new DefaultReindexingMetrics(registry, 1);

        metrics.setJobExecutionId(1L);
        metrics.reindexRead();
        metrics.setJobExecutionId(2L);
        metrics.reindexRead();

        assertThat(readCounter("1")).isNull();
        assertThat(readCounter("2")).isNotNull();
    }

    @Test
    void eviction_whenManyJobsRecorded_retainsExactlyMaxRetainedJobs()
    {
        DefaultReindexingMetrics metrics = new DefaultReindexingMetrics(registry, 2);

        for (long jobId = 1; jobId <= 6; jobId++)
        {
            metrics.setJobExecutionId(jobId);
            metrics.reindexRead();
        }

        assertThat(registry.find(MetricsConstants.REINDEX_READ_METRIC).counters()).hasSize(2);
    }

    @Test
    void eviction_whenJobEvicted_removesAllOfItsMeterTypes()
    {
        DefaultReindexingMetrics metrics = new DefaultReindexingMetrics(registry, 1);

        metrics.setJobExecutionId(1L);
        metrics.recordJobStart(); // registers start-time + status gauges
        metrics.reindexRead(); // registers read counter
        metrics.setJobExecutionId(2L);
        metrics.reindexRead(); // new job -> evicts job 1

        assertThat(jobGauge(MetricsConstants.REINDEX_JOB_START_TIME, "1")).isNull();
        assertThat(jobGauge(MetricsConstants.REINDEX_JOB_STATUS, "1")).isNull();
        assertThat(readCounter("1")).isNull();
        assertThat(readCounter("2")).isNotNull();
    }

    @Test
    void eviction_whenCapIsZero_retainsAllJobs()
    {
        DefaultReindexingMetrics metrics = new DefaultReindexingMetrics(registry, 0);

        for (long jobId = 1; jobId <= 10; jobId++)
        {
            metrics.setJobExecutionId(jobId);
            metrics.reindexRead();
        }

        assertThat(registry.find(MetricsConstants.REINDEX_READ_METRIC).counters()).hasSize(10);
        assertThat(readCounter("1")).isNotNull();
    }

    @Test
    void eviction_whenCapIsNegative_retainsAllJobs()
    {
        DefaultReindexingMetrics metrics = new DefaultReindexingMetrics(registry, -1);

        for (long jobId = 1; jobId <= 10; jobId++)
        {
            metrics.setJobExecutionId(jobId);
            metrics.reindexRead();
        }

        assertThat(registry.find(MetricsConstants.REINDEX_READ_METRIC).counters()).hasSize(10);
        assertThat(readCounter("1")).isNotNull();
    }

    private Counter readCounter(String jobId)
    {
        return registry.find(MetricsConstants.REINDEX_READ_METRIC)
                .tag(MetricsConstants.JOB_ID_TAG, jobId).counter();
    }

    private Counter writeCounter(String jobId, String module, String outcome)
    {
        return registry.find(MetricsConstants.REINDEX_WRITE_METRIC)
                .tags(MetricsConstants.JOB_ID_TAG, jobId,
                        MetricsConstants.MODULE_TAG, module,
                        MetricsConstants.OUTCOME_TAG, outcome)
                .counter();
    }

    private Gauge jobGauge(String name, String jobId)
    {
        return registry.find(name).tag(MetricsConstants.JOB_ID_TAG, jobId).gauge();
    }
}
