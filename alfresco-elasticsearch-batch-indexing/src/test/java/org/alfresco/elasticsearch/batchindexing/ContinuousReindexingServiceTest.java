/*-
 * #%L
 * Alfresco Search Community :: Batch Indexing
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
package org.alfresco.elasticsearch.batchindexing;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.step.StepExecution;

import org.alfresco.elasticsearch.batchindexing.config.ContinuousReindexingProperties;
import org.alfresco.elasticsearch.batchindexing.model.WatermarkDocument;
import org.alfresco.reindexing.processors.UnresolvedNamespaceException;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings({"PMD.TestClassWithoutTestCases", "PMD.SignatureDeclareThrowsException"})
class ContinuousReindexingServiceTest
{
    private static final long TIME_TOLERANCE_MS = 5_000;
    public static final Duration PROP_POLLING_INTERVAL = Duration.ofSeconds(30);
    public static final Duration PROP_MAX_POLLING_INTERVAL = Duration.ofMinutes(5);
    public static final Duration PROP_CATCH_UP_POLLING_INTERVAL = Duration.ofSeconds(1);
    public static final Duration PROP_OVERLAP = Duration.ofMinutes(10);
    public static final Duration PROP_MAX_WINDOW = Duration.ofMinutes(30);
    public static final Duration PROP_MAX_GAP_AGE = Duration.ofHours(1);

    @Mock
    private org.springframework.batch.core.launch.JobLauncher jobLauncher;

    @Mock
    private Job reindexByDateJob;

    @Mock
    private ElasticsearchWatermarkStore watermarkStore;

    @Mock
    private Runnable terminationAction;

    @Captor
    private ArgumentCaptor<JobParameters> jobParametersCaptor;

    @Captor
    private ArgumentCaptor<WatermarkDocument> watermarkCaptor;

    private ContinuousReindexingProperties properties;

    private ContinuousReindexingService service;

    @BeforeEach
    void setUp()
    {
        properties = new ContinuousReindexingProperties();
        properties.setPollingInterval(PROP_POLLING_INTERVAL);
        properties.setMaxPollingInterval(PROP_MAX_POLLING_INTERVAL);
        properties.setCatchUpPollingInterval(PROP_CATCH_UP_POLLING_INTERVAL);
        properties.setOverlap(PROP_OVERLAP);
        properties.setMaxWindow(PROP_MAX_WINDOW);
        properties.setMaxGapAge(PROP_MAX_GAP_AGE);
        properties.setAutoStart(true);

        service = new ContinuousReindexingService(jobLauncher, reindexByDateJob, watermarkStore, properties, terminationAction,
                new org.alfresco.elasticsearch.batchindexing.observability.ContinuousReindexingMonitor(
                        properties,
                        List.of(mock(org.alfresco.elasticsearch.batchindexing.cascade.CascadeSweepService.class))));
    }

    @AfterEach
    void tearDown()
    {
        service.stop();
        service.destroy();
    }

    // ------------------------------------------------------------------
    // Scenario 1: First run — no watermark exists
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Scenario 1: First run (no watermark)")
    class FirstRun
    {
        @Test
        @DisplayName("fromTime = now - overlap, toTime = now, watermark advanced")
        void firstRunWindowUsesNowMinusOverlap() throws Exception
        {
            long now = LocalDateTime.now(ZoneOffset.UTC).toInstant(ZoneOffset.UTC).toEpochMilli();
            when(watermarkStore.read()).thenReturn(Optional.empty());
            when(jobLauncher.run(eq(reindexByDateJob), any())).thenReturn(successfulExecution(10));

            service.runCycle();

            verify(jobLauncher).run(eq(reindexByDateJob), jobParametersCaptor.capture());
            assertThat(capturedFromTime()).isCloseTo(now - PROP_OVERLAP.toMillis(), within(TIME_TOLERANCE_MS));
            assertThat(capturedToTime()).isCloseTo(now, within(TIME_TOLERANCE_MS));

            verify(watermarkStore).write(watermarkCaptor.capture());
            WatermarkDocument written = watermarkCaptor.getValue();
            assertThat(written.getLastSuccessfulToTimeEpochMs()).isCloseTo(now, within(TIME_TOLERANCE_MS));
        }

        @Test
        @DisplayName("returns false (not catching up) because window fits in maxWindow")
        void firstRunDoesNotCatchUp() throws Exception
        {
            when(watermarkStore.read()).thenReturn(Optional.empty());
            when(jobLauncher.run(eq(reindexByDateJob), any())).thenReturn(successfulExecution(10));

            boolean catchingUp = service.runCycle();

            assertThat(catchingUp).isFalse();
        }

        @Test
        @DisplayName("first run failure — watermark has zero successful anchors")
        void firstRunFailureWatermarkHasZeroSuccessfulAnchors() throws Exception
        {
            when(watermarkStore.read()).thenReturn(Optional.empty());
            when(jobLauncher.run(eq(reindexByDateJob), any())).thenReturn(failedExecution("boom"));

            try
            {
                service.runCycle();
            }
            catch (IllegalStateException ignored)
            {}

            verify(watermarkStore).write(watermarkCaptor.capture());
            WatermarkDocument written = watermarkCaptor.getValue();
            assertThat(written.getLastSuccessfulFromTimeEpochMs()).isZero();
            assertThat(written.getLastSuccessfulToTimeEpochMs()).isZero();
            assertThat(written.getLastRunStatus()).isEqualTo("FAILED");
        }
    }

    // ------------------------------------------------------------------
    // Scenario 2: Normal run after a successful run
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Scenario 2: Normal run after successful run")
    class NormalRun
    {
        @Test
        @DisplayName("fromTime = lastSuccessfulToTime - overlap, toTime = now")
        void normalRunOverlapsPreviousWindow() throws Exception
        {
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            long nowMs = now.toInstant(ZoneOffset.UTC).toEpochMilli();
            LocalDateTime lastSuccessfulTo = now.minus(PROP_MAX_POLLING_INTERVAL);
            long lastSuccessfulToMs = lastSuccessfulTo.toInstant(ZoneOffset.UTC).toEpochMilli();

            when(watermarkStore.read()).thenReturn(Optional.of(watermarkAt(lastSuccessfulTo)));
            when(jobLauncher.run(eq(reindexByDateJob), any())).thenReturn(successfulExecution(10));

            service.runCycle();

            verify(jobLauncher).run(eq(reindexByDateJob), jobParametersCaptor.capture());
            long expectedFromMs = lastSuccessfulToMs - PROP_OVERLAP.toMillis();
            assertThat(capturedFromTime()).isCloseTo(expectedFromMs, within(TIME_TOLERANCE_MS));
            assertThat(capturedToTime()).isCloseTo(nowMs, within(TIME_TOLERANCE_MS));
        }

        @Test
        @DisplayName("watermark anchor advanced to toTime on success")
        void normalRunAdvancesWatermark() throws Exception
        {
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            long nowMs = now.toInstant(ZoneOffset.UTC).toEpochMilli();

            when(watermarkStore.read()).thenReturn(Optional.of(watermarkAt(now.minus(PROP_POLLING_INTERVAL))));
            when(jobLauncher.run(eq(reindexByDateJob), any())).thenReturn(successfulExecution(10));

            service.runCycle();

            verify(watermarkStore).write(watermarkCaptor.capture());
            WatermarkDocument written = watermarkCaptor.getValue();
            assertThat(written.getLastSuccessfulToTimeEpochMs()).isCloseTo(nowMs, within(TIME_TOLERANCE_MS));
            assertThat(written.getLastRunStatus()).isEqualTo("COMPLETED");
        }

        @Test
        @DisplayName("returns false (not catching up) because toTime = now")
        void normalRunDoesNotCatchUp() throws Exception
        {
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            when(watermarkStore.read()).thenReturn(Optional.of(watermarkAt(now.minus(PROP_POLLING_INTERVAL))));
            when(jobLauncher.run(eq(reindexByDateJob), any())).thenReturn(successfulExecution(10));

            boolean catchingUp = service.runCycle();

            assertThat(catchingUp).isFalse();
        }

        @Test
        @DisplayName("watermark document contains all run metadata fields")
        void watermarkDocumentContainsAllRunMetadata() throws Exception
        {
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            long nowMs = now.toInstant(ZoneOffset.UTC).toEpochMilli();
            when(watermarkStore.read()).thenReturn(Optional.of(watermarkAt(now.minus(PROP_POLLING_INTERVAL))));
            when(jobLauncher.run(eq(reindexByDateJob), any())).thenReturn(successfulExecution(50, 45, 3, 2));

            service.runCycle();

            verify(jobLauncher).run(eq(reindexByDateJob), jobParametersCaptor.capture());
            verify(watermarkStore).write(watermarkCaptor.capture());
            WatermarkDocument written = watermarkCaptor.getValue();

            assertThat(written.getLastRunFromTimeEpochMs()).isCloseTo(capturedFromTime(), within(TIME_TOLERANCE_MS));
            assertThat(written.getLastRunToTimeEpochMs()).isCloseTo(capturedToTime(), within(TIME_TOLERANCE_MS));
            assertThat(written.getLastRunStatus()).isEqualTo("COMPLETED");
            assertThat(written.getLastRunReadCount()).isEqualTo(50);
            assertThat(written.getLastRunWriteCount()).isEqualTo(45);
            assertThat(written.getLastRunSkipCount()).isEqualTo(3);
            assertThat(written.getLastRunFilterCount()).isEqualTo(2);
            assertThat(written.getLastSuccessfulFromTimeEpochMs()).isCloseTo(capturedFromTime(), within(TIME_TOLERANCE_MS));
            assertThat(written.getLastSuccessfulToTimeEpochMs()).isCloseTo(nowMs, within(TIME_TOLERANCE_MS));
        }

        @Test
        @DisplayName("run.id job parameter is set and positive")
        void runIdParameterIsSet() throws Exception
        {
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            when(watermarkStore.read()).thenReturn(Optional.of(watermarkAt(now.minus(PROP_POLLING_INTERVAL))));
            when(jobLauncher.run(eq(reindexByDateJob), any())).thenReturn(successfulExecution(10));

            service.runCycle();

            verify(jobLauncher).run(eq(reindexByDateJob), jobParametersCaptor.capture());
            Long runId = jobParametersCaptor.getValue().getLong("run.id");
            assertThat(runId).isNotNull().isPositive();
        }
    }

    // ------------------------------------------------------------------
    // Scenario 3: Gap recovery (catching up in chunks)
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Scenario 3: Gap recovery (chunked)")
    class GapRecovery
    {
        @Test
        @DisplayName("window is capped at maxWindow when gap exceeds it")
        void gapRecoveryCapsWindow() throws Exception
        {
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            LocalDateTime lastSuccessfulTo = now.minusMinutes(45);

            when(watermarkStore.read()).thenReturn(Optional.of(watermarkAt(lastSuccessfulTo)));
            when(jobLauncher.run(eq(reindexByDateJob), any())).thenReturn(successfulExecution(10));

            service.runCycle();

            verify(jobLauncher).run(eq(reindexByDateJob), jobParametersCaptor.capture());
            long windowMs = capturedToTime() - capturedFromTime();
            assertThat(windowMs).isCloseTo(properties.getMaxWindow().toMillis(), within(TIME_TOLERANCE_MS));
        }

        @Test
        @DisplayName("returns true (catching up) because toTime < now")
        void gapRecoveryReturnsCatchingUp() throws Exception
        {
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            LocalDateTime lastSuccessfulTo = now.minusMinutes(45);

            when(watermarkStore.read()).thenReturn(Optional.of(watermarkAt(lastSuccessfulTo)));
            when(jobLauncher.run(eq(reindexByDateJob), any())).thenReturn(successfulExecution(10));

            boolean catchingUp = service.runCycle();

            assertThat(catchingUp).isTrue();
        }

        @Test
        @DisplayName("fromTime starts from lastSuccessfulToTime - overlap")
        void gapRecoveryFromTimeIncludesOverlap() throws Exception
        {
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            LocalDateTime lastSuccessfulTo = now.minusMinutes(45);
            long expectedFromMs = lastSuccessfulTo.minus(properties.getOverlap())
                    .toInstant(ZoneOffset.UTC).toEpochMilli();

            when(watermarkStore.read()).thenReturn(Optional.of(watermarkAt(lastSuccessfulTo)));
            when(jobLauncher.run(eq(reindexByDateJob), any())).thenReturn(successfulExecution(10));

            service.runCycle();

            verify(jobLauncher).run(eq(reindexByDateJob), jobParametersCaptor.capture());
            assertThat(capturedFromTime()).isCloseTo(expectedFromMs, within(TIME_TOLERANCE_MS));
        }

        @Test
        @DisplayName("gap recovery windows are contiguous until caught up (no new gaps introduced)")
        @Timeout(5)
        void gapRecoveryEnds() throws Exception
        {
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

            assertThat(properties)
                    .describedAs("sanity check for test configuration, ensuring that fake gap will trigger 3 catch-up runs")
                    .satisfies(
                            p -> assertThat(p.getOverlap()).isEqualTo(Duration.ofMinutes(10)),
                            p -> assertThat(p.getMaxWindow()).isEqualTo(Duration.ofMinutes(30)),
                            p -> assertThat(p.getCatchUpPollingInterval()).isEqualTo(Duration.ofSeconds(1)),
                            p -> assertThat(p.getMaxGapAge()).isEqualTo(Duration.ofHours(1)));

            // let's simulate a gap of 45 minutes
            // because of overlap effective window time is `30 min (max window time) - 10 min (overlap) = 20 min`
            // if you divide 45 min gap by 20 min, you will get 3 runs (20 min + 20 min + 5 min)
            LocalDateTime firstWatermarkToTime = now.minusMinutes(45);

            // after first run we will advance watermark by 20 minutes
            LocalDateTime secondWatermarkToTime = firstWatermarkToTime.minus(properties.getOverlap())
                    .plus(properties.getMaxWindow());

            // after second run we will advance again by 20 minutes
            LocalDateTime thirdWatermarkToTime = secondWatermarkToTime.minus(properties.getOverlap())
                    .plus(properties.getMaxWindow());

            when(watermarkStore.read())
                    .thenReturn(Optional.of(watermarkAt(firstWatermarkToTime)))
                    .thenReturn(Optional.of(watermarkAt(secondWatermarkToTime)))
                    .thenReturn(Optional.of(watermarkAt(thirdWatermarkToTime)));

            when(jobLauncher.run(eq(reindexByDateJob), any())).thenReturn(successfulExecution(10));

            // let the service run enough time to process all runs
            service.start();
            Thread.sleep(3_000L);

            verify(jobLauncher, times(3)).run(eq(reindexByDateJob), jobParametersCaptor.capture());
            List<JobParameters> allCapturedJobParameters = jobParametersCaptor.getAllValues();

            assertThat(allCapturedJobParameters).hasSize(3);

            long expectedFirstFromMs = firstWatermarkToTime.minus(properties.getOverlap())
                    .toInstant(ZoneOffset.UTC).toEpochMilli();
            assertThat(allCapturedJobParameters).element(0)
                    .extracting(jobParameters -> jobParameters.getLong("fromTime"), as(InstanceOfAssertFactories.LONG))
                    .isCloseTo(expectedFirstFromMs, within(TIME_TOLERANCE_MS));

            long expectedSecondFromMs = expectedFirstFromMs + properties.getMaxWindow().toMillis() - PROP_OVERLAP.toMillis();
            assertThat(allCapturedJobParameters).element(1)
                    .describedAs("second run should pick up from the end of the first run's window")
                    .extracting(jobParameters -> jobParameters.getLong("fromTime"), as(InstanceOfAssertFactories.LONG))
                    .isCloseTo(expectedSecondFromMs, within(TIME_TOLERANCE_MS));

            long expectedThirdFromMs = expectedSecondFromMs + properties.getMaxWindow().toMillis() - PROP_OVERLAP.toMillis();
            assertThat(allCapturedJobParameters).element(2)
                    .describedAs("last run should pick up from the end of the second run's window")
                    .extracting(jobParameters -> jobParameters.getLong("fromTime"), as(InstanceOfAssertFactories.LONG))
                    .isCloseTo(expectedThirdFromMs, within(TIME_TOLERANCE_MS));
            assertThat(allCapturedJobParameters).element(2)
                    .describedAs("last run toTime should be closely to `now`")
                    .extracting(jobParameters -> jobParameters.getLong("toTime"), as(InstanceOfAssertFactories.LONG))
                    .isCloseTo(now.toInstant(ZoneOffset.UTC).toEpochMilli(), within(TIME_TOLERANCE_MS));
        }
    }

    // ------------------------------------------------------------------
    // Scenario 4: Gap skip (maxGapAge fast-forward)
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Scenario 4: Gap skip (maxGapAge)")
    class GapSkip
    {
        @Test
        @DisplayName("fromTime is fast-forwarded to now - maxGapAge when gap is too old")
        void gapSkipFastForwards() throws Exception
        {
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            long expectedFromMs = now.minus(properties.getMaxGapAge())
                    .toInstant(ZoneOffset.UTC).toEpochMilli();
            LocalDateTime lastSuccessfulTo = now.minusHours(3);

            when(watermarkStore.read()).thenReturn(Optional.of(watermarkAt(lastSuccessfulTo)));
            when(jobLauncher.run(eq(reindexByDateJob), any())).thenReturn(successfulExecution(10));

            service.runCycle();

            verify(jobLauncher).run(eq(reindexByDateJob), jobParametersCaptor.capture());
            assertThat(capturedFromTime()).isCloseTo(expectedFromMs, within(TIME_TOLERANCE_MS));
        }

        @Test
        @DisplayName("data before maxGapAge boundary is silently dropped")
        void gapSkipDoesNotReindexOldData() throws Exception
        {
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            LocalDateTime lastSuccessfulTo = now.minusHours(3);
            long originalFromMs = lastSuccessfulTo.minus(properties.getOverlap())
                    .toInstant(ZoneOffset.UTC).toEpochMilli();

            when(watermarkStore.read()).thenReturn(Optional.of(watermarkAt(lastSuccessfulTo)));
            when(jobLauncher.run(eq(reindexByDateJob), any())).thenReturn(successfulExecution(10));

            service.runCycle();

            verify(jobLauncher).run(eq(reindexByDateJob), jobParametersCaptor.capture());
            assertThat(capturedFromTime()).isGreaterThan(originalFromMs);
        }

        @Test
        @DisplayName("maxGapAge=0 disables the guard — no fast-forward even for ancient watermarks")
        void gapSkipDisabledWhenZero() throws Exception
        {
            properties.setMaxGapAge(Duration.ZERO);

            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            LocalDateTime lastSuccessfulTo = now.minusHours(5);
            long expectedFromMs = lastSuccessfulTo.minus(properties.getOverlap())
                    .toInstant(ZoneOffset.UTC).toEpochMilli();

            when(watermarkStore.read()).thenReturn(Optional.of(watermarkAt(lastSuccessfulTo)));
            when(jobLauncher.run(eq(reindexByDateJob), any())).thenReturn(successfulExecution(10));

            service.runCycle();

            verify(jobLauncher).run(eq(reindexByDateJob), jobParametersCaptor.capture());
            assertThat(capturedFromTime()).isCloseTo(expectedFromMs, within(TIME_TOLERANCE_MS));
        }
    }

    // ------------------------------------------------------------------
    // Scenario 5: Job FAILED for any reason (skip limit exceeded, step exception, etc.)
    //
    // Whenever the Spring Batch JobExecution ends in status FAILED, the service must
    // throw and leave lastSuccessfulTo untouched.
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Scenario 5: Job FAILED for any reason")
    class JobFailed
    {
        @Test
        @DisplayName("FAILED job status — watermark NOT advanced, exception thrown")
        void failedJobThrows() throws Exception
        {
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            when(watermarkStore.read()).thenReturn(Optional.of(watermarkAt(now.minus(PROP_MAX_WINDOW))));
            when(jobLauncher.run(eq(reindexByDateJob), any())).thenReturn(failedExecution("skip limit exceeded"));

            assertThatThrownBy(service::runCycle)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("FAILED");
        }

        @Test
        @DisplayName("multiple failure exceptions are concatenated with semicolons")
        void multipleFailureExceptionsConcatenated() throws Exception
        {
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            when(watermarkStore.read()).thenReturn(Optional.of(watermarkAt(now.minus(PROP_POLLING_INTERVAL))));

            JobExecution execution = new JobExecution(1L, new JobInstance(1L, "reindexByDate"), new JobParameters());
            execution.setStatus(BatchStatus.FAILED);
            execution.addFailureException(new RuntimeException("first error"));
            execution.addFailureException(new RuntimeException("second error"));
            StepExecution step = new StepExecution("reindexByDateStep", execution);
            execution.addStepExecutions(List.of(step));

            when(jobLauncher.run(eq(reindexByDateJob), any())).thenReturn(execution);

            assertThatThrownBy(service::runCycle)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("first error")
                    .hasMessageContaining("second error")
                    .hasMessageContaining("; ");
        }

        @Test
        @DisplayName("watermark document still written with last run metadata but successful anchors unchanged")
        void failedJobPreservesLastSuccessfulAnchors() throws Exception
        {
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            LocalDateTime previousSuccessfulTo = now.minus(PROP_MAX_WINDOW);
            long previousSuccessfulToMs = previousSuccessfulTo.toInstant(ZoneOffset.UTC).toEpochMilli();

            when(watermarkStore.read()).thenReturn(Optional.of(watermarkAt(previousSuccessfulTo)));
            when(jobLauncher.run(eq(reindexByDateJob), any())).thenReturn(failedExecution("skip limit exceeded"));

            try
            {
                service.runCycle();
            }
            catch (IllegalStateException ignored)
            {}

            verify(watermarkStore).write(watermarkCaptor.capture());
            WatermarkDocument written = watermarkCaptor.getValue();
            assertThat(written.getLastSuccessfulToTimeEpochMs()).isEqualTo(previousSuccessfulToMs);
            assertThat(written.getLastRunStatus()).isEqualTo("FAILED");
        }
    }

    // ------------------------------------------------------------------
    // Scenario 7: Elasticsearch unavailable
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Scenario 7: Elasticsearch unavailable")
    class ElasticsearchUnavailable
    {
        @Test
        @DisplayName("watermarkStore.read() throws — exception propagates (handled by runCycleSafe)")
        void watermarkReadFailurePropagates() throws Exception
        {
            when(watermarkStore.read()).thenThrow(
                    new WatermarkAccessException("ES cluster down", new RuntimeException()));

            assertThatThrownBy(service::runCycle)
                    .isInstanceOf(WatermarkAccessException.class)
                    .hasMessageContaining("ES cluster down");

            verify(jobLauncher, never()).run(any(), any());
        }
    }

    // ------------------------------------------------------------------
    // Scenario 8: JVM restart
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Scenario 8: JVM restart")
    class JvmRestart
    {
        @Test
        @DisplayName("watermark persisted — picks up from last successful anchor")
        void restartResumesFromWatermark() throws Exception
        {
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            LocalDateTime lastSuccessfulTo = now.minusMinutes(2);
            long lastSuccessfulToMs = lastSuccessfulTo.toInstant(ZoneOffset.UTC).toEpochMilli();
            long expectedFromMs = lastSuccessfulToMs - properties.getOverlap().toMillis();

            when(watermarkStore.read()).thenReturn(Optional.of(watermarkAt(lastSuccessfulTo)));
            when(jobLauncher.run(eq(reindexByDateJob), any())).thenReturn(successfulExecution(5));

            service.runCycle();

            verify(jobLauncher).run(eq(reindexByDateJob), jobParametersCaptor.capture());
            assertThat(capturedFromTime()).isCloseTo(expectedFromMs, within(TIME_TOLERANCE_MS));
            assertThat(capturedToTime()).isCloseTo(now.toInstant(ZoneOffset.UTC).toEpochMilli(),
                    within(TIME_TOLERANCE_MS));
        }
    }

    // ------------------------------------------------------------------
    // Watermark edge cases
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Watermark edge cases")
    class WatermarkEdgeCases
    {
        @Test
        @DisplayName("watermarkStore.write() throws WatermarkConflictException — service stops and terminationAction fires")
        void watermarkConflictTerminates() throws Exception
        {
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            when(watermarkStore.read()).thenReturn(Optional.of(watermarkAt(now.minus(PROP_MAX_WINDOW))));
            when(jobLauncher.run(eq(reindexByDateJob), any())).thenReturn(successfulExecution(10));
            doThrow(new WatermarkConflictException("version conflict", new RuntimeException()))
                    .when(watermarkStore).write(any());

            service.runCycleSafe();

            assertThat(service.isRunning()).isFalse();
            verify(terminationAction).run();
        }

        @Test
        @DisplayName("cycle fails on an unresolved namespace — service stops and terminationAction fires")
        void unresolvedNamespaceTerminates() throws Exception
        {
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            when(watermarkStore.read()).thenReturn(Optional.of(watermarkAt(now.minus(PROP_MAX_WINDOW))));
            when(jobLauncher.run(eq(reindexByDateJob), any()))
                    .thenReturn(failedExecution(new UnresolvedNamespaceException(7L, "http://acme", "invoice", true)));

            service.runCycleSafe();

            assertThat(service.isRunning()).isFalse();
            verify(terminationAction).run();
        }

        @Test
        @DisplayName("terminating cycle does not reschedule or count as a failure")
        void terminatingCycleDoesNotReschedule() throws Exception
        {
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            when(watermarkStore.read()).thenReturn(Optional.of(watermarkAt(now.minus(PROP_MAX_WINDOW))));
            when(jobLauncher.run(eq(reindexByDateJob), any()))
                    .thenReturn(failedExecution(
                            new UnresolvedNamespaceException(7L, "http://acme", "invoice", true)));

            service.runCycleSafe();

            // A rescheduled cycle would launch the job again while the JVM is shutting down.
            verify(jobLauncher, times(1)).run(eq(reindexByDateJob), any());
            assertThat(service.computeBackoff(1)).isEqualTo(PROP_POLLING_INTERVAL.toMillis() * 2);
        }

        @Test
        @DisplayName("cycle fails on a skipped unresolved namespace — backs off instead of terminating")
        void skippedUnresolvedNamespaceDoesNotTerminate() throws Exception
        {
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            when(watermarkStore.read()).thenReturn(Optional.of(watermarkAt(now.minus(PROP_MAX_WINDOW))));
            when(jobLauncher.run(eq(reindexByDateJob), any()))
                    .thenReturn(failedExecution(
                            new UnresolvedNamespaceException(7L, "http://acme", "invoice", false)));

            service.runCycleSafe();

            verify(terminationAction, never()).run();
        }

        @Test
        @DisplayName("cycle fails for another reason — terminationAction does not fire")
        void otherFailureDoesNotTerminate() throws Exception
        {
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            when(watermarkStore.read()).thenReturn(Optional.of(watermarkAt(now.minus(PROP_MAX_WINDOW))));
            when(jobLauncher.run(eq(reindexByDateJob), any()))
                    .thenReturn(failedExecution(new IllegalStateException("something else")));

            service.runCycleSafe();

            verify(terminationAction, never()).run();
        }

        @Test
        @DisplayName("watermark exists with lastSuccessfulToTimeEpochMs=0 — treated as first run")
        void watermarkWithZeroAnchorTreatedAsFirstRun() throws Exception
        {
            long now = LocalDateTime.now(ZoneOffset.UTC).toInstant(ZoneOffset.UTC).toEpochMilli();

            WatermarkDocument zeroAnchor = new WatermarkDocument();
            zeroAnchor.setLastSuccessfulToTimeEpochMs(0);
            when(watermarkStore.read()).thenReturn(Optional.of(zeroAnchor));
            when(jobLauncher.run(eq(reindexByDateJob), any())).thenReturn(successfulExecution(10));

            service.runCycle();

            verify(jobLauncher).run(eq(reindexByDateJob), jobParametersCaptor.capture());
            assertThat(capturedFromTime()).isCloseTo(now - PROP_OVERLAP.toMillis(), within(TIME_TOLERANCE_MS));
            assertThat(capturedToTime()).isCloseTo(now, within(TIME_TOLERANCE_MS));
        }
    }

    // ------------------------------------------------------------------
    // Missing step execution
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Missing step execution")
    class MissingStepExecution
    {
        @Test
        @DisplayName("step not named reindexByDateStep — all counts default to zero, treated as successful")
        void missingStepExecutionDefaultsCountsToZero() throws Exception
        {
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            long nowMs = now.toInstant(ZoneOffset.UTC).toEpochMilli();
            when(watermarkStore.read()).thenReturn(Optional.of(watermarkAt(now.minus(PROP_POLLING_INTERVAL))));

            JobExecution execution = new JobExecution(1L, new JobInstance(1L, "reindexByDate"), new JobParameters());
            execution.setStatus(BatchStatus.COMPLETED);
            StepExecution step = new StepExecution("someOtherStep", execution);
            execution.addStepExecutions(List.of(step));

            when(jobLauncher.run(eq(reindexByDateJob), any())).thenReturn(execution);

            boolean catchingUp = service.runCycle();

            assertThat(catchingUp).isFalse();
            verify(watermarkStore).write(watermarkCaptor.capture());
            WatermarkDocument written = watermarkCaptor.getValue();
            assertThat(written.getLastSuccessfulToTimeEpochMs()).isCloseTo(nowMs, within(TIME_TOLERANCE_MS));
            assertThat(written.getLastRunReadCount()).isZero();
            assertThat(written.getLastRunWriteCount()).isZero();
            assertThat(written.getLastRunSkipCount()).isZero();
            assertThat(written.getLastRunFilterCount()).isZero();
        }
    }

    // ------------------------------------------------------------------
    // Backoff and scheduling
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Backoff computation")
    class Backoff
    {
        @Test
        @DisplayName("single failure — backoff = pollingInterval * 2")
        void singleFailureBackoff()
        {
            long backoff = service.computeBackoff(1);

            assertThat(backoff).isEqualTo(properties.getPollingInterval().toMillis() * 2);
        }

        @Test
        @DisplayName("backoff capped at maxPollingInterval")
        void backoffCappedAtMax()
        {
            long backoff = service.computeBackoff(20);

            assertThat(backoff).isEqualTo(properties.getMaxPollingInterval().toMillis());
        }

        @ParameterizedTest(name = "failures={0} -> expected={1}")
        @CsvSource({
                "0, 30000",
                "1, 60000",
                "2, 120000",
                "3, 240000",
                "4, 300000"
        })
        @DisplayName("backoff grows exponentially and is capped at maxPollingInterval")
        void backoffGrowsExponentially(int failures, long expected)
        {
            long backoff = service.computeBackoff(failures);
            assertThat(backoff).isEqualTo(expected);
        }
    }

    // ------------------------------------------------------------------
    // Window edge cases
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Window computation edge cases")
    class WindowEdgeCases
    {
        @Test
        @DisplayName("fromTime >= toTime — idle, returns false, no job launched")
        void noNewWindowIsIdle() throws Exception
        {
            properties.setOverlap(Duration.ZERO);

            LocalDateTime futureWatermark = LocalDateTime.now(ZoneOffset.UTC).plus(PROP_MAX_GAP_AGE);
            when(watermarkStore.read()).thenReturn(Optional.of(watermarkAt(futureWatermark)));

            boolean catchingUp = service.runCycle();

            assertThat(catchingUp).isFalse();
            verify(jobLauncher, never()).run(any(), any());
        }

        @Test
        @DisplayName("overlap greater than pollingInterval — still produces valid window")
        void largeOverlapStillWorks() throws Exception
        {
            properties.setOverlap(Duration.ofHours(1));
            properties.setMaxGapAge(Duration.ZERO);

            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            when(watermarkStore.read()).thenReturn(Optional.of(watermarkAt(now.minus(PROP_MAX_WINDOW))));
            when(jobLauncher.run(eq(reindexByDateJob), any())).thenReturn(successfulExecution(10));

            boolean catchingUp = service.runCycle();

            verify(jobLauncher).run(eq(reindexByDateJob), jobParametersCaptor.capture());
            assertThat(capturedFromTime()).isLessThan(capturedToTime());
            assertThat(catchingUp).isTrue();
        }
    }

    // ------------------------------------------------------------------
    // SmartLifecycle
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("SmartLifecycle")
    class Lifecycle
    {
        @Test
        @DisplayName("start() sets isRunning to true")
        void startSetsRunningToTrue()
        {
            service.start();
            assertThat(service.isRunning()).isTrue();
        }

        @Test
        @DisplayName("stop() after start() sets isRunning to false")
        void stopSetsRunningToFalse()
        {
            service.start();
            service.stop();
            assertThat(service.isRunning()).isFalse();
        }

        @Test
        @DisplayName("start() is idempotent — calling twice does not throw")
        void startIsIdempotent()
        {
            service.start();
            service.start();
            assertThat(service.isRunning()).isTrue();
        }

        @Test
        @DisplayName("stop() is idempotent — calling without start does not throw")
        void stopIsIdempotent()
        {
            service.stop();
            assertThat(service.isRunning()).isFalse();
        }

        @Test
        @DisplayName("stop(Runnable) invokes the callback after stopping")
        void stopWithCallbackInvokesCallback()
        {
            service.start();
            Runnable callback = org.mockito.Mockito.mock(Runnable.class);
            service.stop(callback);
            assertThat(service.isRunning()).isFalse();
            verify(callback).run();
        }

        @Test
        @DisplayName("start() after stop() resumes scheduling (executor is still alive)")
        void startAfterStopResumes() throws Exception
        {
            when(watermarkStore.read()).thenReturn(Optional.empty());
            when(jobLauncher.run(eq(reindexByDateJob), any())).thenReturn(successfulExecution(1));

            service.start();
            assertThat(service.isRunning()).isTrue();

            Thread.sleep(500);
            service.stop();
            assertThat(service.isRunning()).isFalse();

            service.start();
            assertThat(service.isRunning()).isTrue();

            Thread.sleep(500);
            verify(jobLauncher, atLeast(2)).run(eq(reindexByDateJob), any());
        }

        @Test
        @DisplayName("stop() during a running cycle prevents reschedule on failure")
        @Timeout(5)
        void stopDuringCyclePreventsReschedule() throws Exception
        {
            properties.setPollingInterval(Duration.ofMillis(50));

            CountDownLatch cycleStarted = new CountDownLatch(1);
            CountDownLatch stopCalled = new CountDownLatch(1);

            when(watermarkStore.read()).thenAnswer(invocation -> {
                cycleStarted.countDown();
                stopCalled.await(5, TimeUnit.SECONDS);
                throw new WatermarkAccessException("ES down", new RuntimeException());
            });

            service.start();
            cycleStarted.await(5, TimeUnit.SECONDS);

            service.stop();
            stopCalled.countDown();

            // error handling should not reschedule a stopped service so we expect only initial read attempt and no retries
            Thread.sleep(500);
            verify(watermarkStore, times(1)).read();
        }

        @Test
        @DisplayName("isAutoStartup() is used to determine if the service should start automatically")
        void autoStartupReflectsProperty()
        {
            assertThat(service.isAutoStartup()).isTrue();
            properties.setAutoStart(false);

            ContinuousReindexingService manualService = new ContinuousReindexingService(
                    jobLauncher, reindexByDateJob, watermarkStore, properties, terminationAction,
                    new org.alfresco.elasticsearch.batchindexing.observability.ContinuousReindexingMonitor(
                            properties,
                            List.of(mock(org.alfresco.elasticsearch.batchindexing.cascade.CascadeSweepService.class))));
            assertThat(manualService.isAutoStartup()).isFalse();
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Creates a watermark document with only the successful-to-time anchor set.
     */
    private WatermarkDocument watermarkAt(LocalDateTime successfulTo)
    {
        WatermarkDocument doc = new WatermarkDocument();
        doc.setLastSuccessfulToTimeEpochMs(successfulTo.toInstant(ZoneOffset.UTC).toEpochMilli());
        doc.setLastSuccessfulFromTimeEpochMs(
                successfulTo.minus(PROP_MAX_GAP_AGE).toInstant(ZoneOffset.UTC).toEpochMilli());
        return doc;
    }

    /**
     * Builds a mock {@link JobExecution} that completed successfully with the given item counts.
     */
    private JobExecution successfulExecution(long readCount, long writeCount, long skipCount, long filterCount)
    {
        JobExecution execution = new JobExecution(1L, new JobInstance(1L, "reindexByDate"), new JobParameters());
        execution.setStatus(BatchStatus.COMPLETED);

        StepExecution step = new StepExecution("reindexByDateStep", execution);
        step.setReadCount(readCount);
        step.setWriteCount(writeCount);
        step.setProcessSkipCount(skipCount);
        step.setFilterCount(filterCount);

        execution.addStepExecutions(List.of(step));
        return execution;
    }

    /**
     * Builds a mock {@link JobExecution} that completed successfully with all items written.
     */
    private JobExecution failedExecution(Throwable failure)
    {
        JobExecution execution = new JobExecution(1L, new JobInstance(1L, "reindexByDate"), new JobParameters());
        execution.setStatus(BatchStatus.FAILED);
        execution.addFailureException(failure);
        execution.addStepExecutions(List.of(new StepExecution("reindexByDateStep", execution)));
        return execution;
    }

    private JobExecution successfulExecution(long itemCount)
    {
        return successfulExecution(itemCount, itemCount, 0, 0);
    }

    /**
     * Builds a mock {@link JobExecution} with a FAILED status.
     */
    private JobExecution failedExecution(String failureMessage)
    {
        JobExecution execution = new JobExecution(1L, new JobInstance(1L, "reindexByDate"), new JobParameters());
        execution.setStatus(BatchStatus.FAILED);
        execution.addFailureException(new RuntimeException(failureMessage));

        StepExecution step = new StepExecution("reindexByDateStep", execution);
        execution.addStepExecutions(List.of(step));
        return execution;
    }

    /**
     * Extracts the fromTime (epoch ms) from captured {@link JobParameters}.
     */
    private Long capturedFromTime()
    {
        return jobParametersCaptor.getValue().getLong("fromTime");
    }

    /**
     * Extracts the toTime (epoch ms) from captured {@link JobParameters}.
     */
    private Long capturedToTime()
    {
        return jobParametersCaptor.getValue().getLong("toTime");
    }
}
