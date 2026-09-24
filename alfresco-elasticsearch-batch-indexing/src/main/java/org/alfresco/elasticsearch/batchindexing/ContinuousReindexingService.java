/*-
 * #%L
 * Alfresco Elasticsearch Connector :: Batch Indexing Module
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

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.context.SmartLifecycle;

import org.alfresco.elasticsearch.batchindexing.config.ContinuousReindexingProperties;
import org.alfresco.elasticsearch.batchindexing.model.WatermarkDocument;
import org.alfresco.elasticsearch.batchindexing.observability.ContinuousReindexingMonitor;
import org.alfresco.reindexing.config.ElasticsearchReindexByDateJobConfig;
import org.alfresco.reindexing.processors.UnresolvedNamespaceException;

/**
 * Continuously polls the Alfresco DB for new/changed nodes and launches {@code reindexByDate} Spring Batch jobs one at a time. Consecutive time windows intentionally overlap (controlled by the {@code overlap} property) to catch late DB writes, but job executions never run concurrently.
 * <p>
 * Uses {@link java.util.concurrent.ScheduledExecutorService#scheduleWithFixedDelay} so the next tick only starts <em>after</em> the previous cycle completes plus the configured polling delay.
 * <p>
 * Consecutive failures trigger exponential backoff up to {@code maxPollingInterval} to avoid hammering a down ES/DB cluster.
 */
public class ContinuousReindexingService implements SmartLifecycle
{
    /**
     * Used for a stale watermark and for {@code onUnresolvedNamespace=stop}, both of which must not carry on.
     * <p>
     * Exits on its own thread: these callers run on the scheduler thread, and {@code destroy()} waits for that thread to finish, so exiting inline would stall shutdown for the full await.
     */
    public static final Runnable TERMINATE_JVM = () -> {
        Thread exit = new Thread(() -> System.exit(1), "continuous-reindex-terminate");
        exit.setDaemon(false);
        exit.start();
    };
    private static final Logger LOGGER = LoggerFactory.getLogger(ContinuousReindexingService.class);

    private final JobLauncher jobLauncher;
    private final Job reindexByDateJob;
    private final ElasticsearchWatermarkStore watermarkStore;
    private final ContinuousReindexingProperties properties;
    private final boolean autoStartup;
    private final Runnable terminationAction;
    private final ContinuousReindexingMonitor monitor;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "continuous-reindex");
        t.setDaemon(false);
        return t;
    });

    private final AtomicInteger consecutiveFailureCount = new AtomicInteger(0);
    private final AtomicBoolean running = new AtomicBoolean();
    private ScheduledFuture<?> scheduledFuture;

    public ContinuousReindexingService(
            JobLauncher jobLauncher,
            Job reindexByDateJob,
            ElasticsearchWatermarkStore watermarkStore,
            ContinuousReindexingProperties properties,
            Runnable terminationAction,
            ContinuousReindexingMonitor monitor)
    {
        this.jobLauncher = jobLauncher;
        this.reindexByDateJob = reindexByDateJob;
        this.watermarkStore = watermarkStore;
        this.properties = properties;
        this.autoStartup = properties.isAutoStart();
        this.terminationAction = terminationAction;
        this.monitor = Objects.requireNonNull(monitor, "monitor");
    }

    @Override
    public void start()
    {
        if (!running.compareAndSet(false, true))
        {
            LOGGER.debug("Continuous reindexing service is already running — ignoring start() call");
            return;
        }

        LOGGER.info("Starting continuous reindexing service (pollingInterval={}, maxPollingInterval={}, "
                + "catchUpPollingInterval={}, overlap={}, maxWindow={}, maxGapAge={})",
                properties.getPollingInterval(), properties.getMaxPollingInterval(),
                properties.getCatchUpPollingInterval(), properties.getOverlap(),
                properties.getMaxWindow(), properties.getMaxGapAge());

        scheduledFuture = scheduler.scheduleWithFixedDelay(
                this::runCycleSafe,
                0,
                properties.getPollingInterval().toMillis(),
                TimeUnit.MILLISECONDS);
    }

    void runCycleSafe()
    {
        monitor.notifyCycleStart();
        try
        {
            boolean catchingUp;
            do
            {
                catchingUp = runCycle();
                if (consecutiveFailureCount.getAndSet(0) > 0)
                {
                    LOGGER.info("Continuous reindexing recovered after previous failures");
                }
                if (catchingUp)
                {
                    if (LOGGER.isDebugEnabled())
                    {
                        LOGGER.debug("Catch-up chunk done, next chunk in {}",
                                properties.getCatchUpPollingInterval());
                    }
                    Thread.sleep(properties.getCatchUpPollingInterval().toMillis());
                }
            } while (catchingUp);
        }
        catch (WatermarkConflictException conflict)
        {
            LOGGER.error("FATAL: Watermark version conflict detected — another reindexing instance "
                    + "has updated the watermark since our last read. This instance is stale and will "
                    + "terminate to prevent data corruption. Details: {}", conflict.getMessage());
            stop();
            terminationAction.run();
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            handleCycleFailure(e);
        }
        catch (Exception e)
        {
            handleCycleFailure(e);
        }
        finally
        {
            monitor.notifyCycleEnd();
        }
    }

    @SuppressWarnings("PMD.SignatureDeclareThrowsException")
    boolean runCycle() throws Exception
    {
        WatermarkDocument existingWatermark = watermarkStore.read().orElse(null);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        LocalDateTime fromTime = computeWindowStart(existingWatermark, now);
        LocalDateTime maxEnd = fromTime.plus(properties.getMaxWindow());
        LocalDateTime toTime = maxEnd.isBefore(now) ? maxEnd : now;

        if (!fromTime.isBefore(toTime))
        {
            LOGGER.debug("No new time window to scan (from={}, to={}). Idle.", fromTime, toTime);
            return false;
        }

        long fromEpochMs = fromTime.toInstant(ZoneOffset.UTC).toEpochMilli();
        long toEpochMs = toTime.toInstant(ZoneOffset.UTC).toEpochMilli();
        boolean catchingUp = maxEnd.isBefore(now);

        LOGGER.info("Starting continuous reindexByDate cycle: from={} ({}), to={} ({}){}", fromTime, fromEpochMs,
                toTime, toEpochMs, catchingUp ? " [chunked gap recovery — more chunks pending]" : "");

        monitor.notifyJobStart();
        try
        {
            JobExecution jobExecution = jobLauncher.run(reindexByDateJob,
                    new JobParametersBuilder()
                            .addLong("fromTime", fromEpochMs)
                            .addLong("toTime", toEpochMs)
                            .addLong("run.id", System.currentTimeMillis())
                            .toJobParameters());

            BatchStatus status = jobExecution.getStatus();
            LOGGER.info("Reindexing cycle completed with status: {}", status);

            long[] metrics = collectStepMetrics(jobExecution);
            LOGGER.info("Job metrics: readCount={}, writeCount={}, skipCount={}, filterCount={}",
                    metrics[0], metrics[1], metrics[2], metrics[3]);

            updateWatermark(existingWatermark, status, fromEpochMs, toEpochMs, metrics);

            if (status != BatchStatus.COMPLETED)
            {
                if (terminateIfNamespaceUnresolved(jobExecution))
                {
                    // Terminating: no failure to report and nothing to reschedule.
                    return false;
                }
                throwCycleFailure(jobExecution, status);
            }

            monitor.notifyJobSuccess();
        }
        finally
        {
            monitor.notifyJobEnd();
        }

        return catchingUp;
    }

    private LocalDateTime computeWindowStart(WatermarkDocument existingWatermark, LocalDateTime now)
    {
        LocalDateTime watermark;
        if (existingWatermark != null && existingWatermark.getLastSuccessfulToTimeEpochMs() > 0)
        {
            watermark = LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(existingWatermark.getLastSuccessfulToTimeEpochMs()), ZoneOffset.UTC);
        }
        else
        {
            watermark = now;
        }

        LocalDateTime fromTime = watermark.minus(properties.getOverlap());

        Duration maxGapAge = properties.getMaxGapAge();
        if (!maxGapAge.isZero())
        {
            LocalDateTime oldestAllowed = now.minus(maxGapAge);
            if (fromTime.isBefore(oldestAllowed))
            {
                LOGGER.warn("fromTime {} is older than maxGapAge={} (oldest allowed: {}). "
                        + "Fast-forwarding — data before {} will NOT be reindexed.",
                        fromTime, maxGapAge, oldestAllowed, oldestAllowed);
                fromTime = oldestAllowed;
            }
        }

        return fromTime;
    }

    private long[] collectStepMetrics(JobExecution jobExecution)
    {
        StepExecution indexingStep = jobExecution.getStepExecutions().stream()
                .filter(s -> ElasticsearchReindexByDateJobConfig.REINDEX_BY_DATE_STEP_NAME.equals(s.getStepName()))
                .findFirst()
                .orElse(null);
        if (indexingStep == null)
        {
            LOGGER.warn("StepExecution for '{}' not found in job execution — job may have failed before reaching "
                    + "the indexing step.",
                    ElasticsearchReindexByDateJobConfig.REINDEX_BY_DATE_STEP_NAME);
            return new long[]{0, 0, 0, 0};
        }
        return new long[]{
                indexingStep.getReadCount(),
                indexingStep.getWriteCount(),
                indexingStep.getSkipCount(),
                indexingStep.getFilterCount()
        };
    }

    @SuppressWarnings("PMD.UseVarargs")
    private void updateWatermark(WatermarkDocument existingWatermark, BatchStatus status,
            long fromEpochMs, long toEpochMs, long[] metrics)
    {
        WatermarkDocument newWatermark = new WatermarkDocument();
        newWatermark.setLastRunFromTimeEpochMs(fromEpochMs);
        newWatermark.setLastRunToTimeEpochMs(toEpochMs);
        newWatermark.setLastRunStatus(status.toString());
        newWatermark.setLastRunReadCount(metrics[0]);
        newWatermark.setLastRunWriteCount(metrics[1]);
        newWatermark.setLastRunSkipCount(metrics[2]);
        newWatermark.setLastRunFilterCount(metrics[3]);

        if (existingWatermark != null)
        {
            newWatermark.setLastSuccessfulFromTimeEpochMs(existingWatermark.getLastSuccessfulFromTimeEpochMs());
            newWatermark.setLastSuccessfulToTimeEpochMs(existingWatermark.getLastSuccessfulToTimeEpochMs());
        }

        if (status == BatchStatus.COMPLETED)
        {
            newWatermark.setLastSuccessfulFromTimeEpochMs(fromEpochMs);
            newWatermark.setLastSuccessfulToTimeEpochMs(toEpochMs);
            LOGGER.info("Watermark anchor advanced to {}",
                    LocalDateTime.ofInstant(Instant.ofEpochMilli(toEpochMs), ZoneOffset.UTC));
        }

        watermarkStore.write(newWatermark);
    }

    /**
     * With {@code alfresco.reindex.onUnresolvedNamespace=stop} the operator has asked not to keep indexing while the prefix map is out of sync, so end the application rather than retry the window.
     */
    private boolean terminateIfNamespaceUnresolved(JobExecution jobExecution)
    {
        boolean unresolved = jobExecution.getAllFailureExceptions().stream()
                .anyMatch(ContinuousReindexingService::causedByTerminalUnresolvedNamespace);
        if (!unresolved)
        {
            return false;
        }

        LOGGER.error("FATAL: a node's type namespace has no prefix mapping and "
                + "alfresco.reindex.onUnresolvedNamespace=stop. Add the namespace to the prefix map "
                + "and restart. Affected nodes are recorded in the dead-letter index.");
        stop();
        terminationAction.run();
        return true;
    }

    private static boolean causedByTerminalUnresolvedNamespace(Throwable throwable)
    {
        for (Throwable cause = throwable; cause != null; cause = cause.getCause())
        {
            // Only stop asks for termination; skip lets the cycle fail and back off as usual.
            if (cause instanceof UnresolvedNamespaceException unresolved && unresolved.isTerminal())
            {
                return true;
            }
        }
        return false;
    }

    private static void throwCycleFailure(JobExecution jobExecution, BatchStatus status)
    {
        String failureMessages = jobExecution.getAllFailureExceptions().stream()
                .map(Throwable::getMessage)
                .reduce((a, b) -> a + "; " + b)
                .orElse("no details");
        throw new IllegalStateException(String.format(
                "Reindexing job finished with status %s. Watermark anchor NOT advanced — "
                        + "same window will be retried with backoff. Failures: %s",
                status, failureMessages));
    }

    private void handleCycleFailure(Exception e)
    {
        int failures = consecutiveFailureCount.incrementAndGet();
        long backoff = computeBackoff(failures);
        LOGGER.warn("Continuous reindexing cycle failed (consecutiveFailures={}). "
                + "Next retry in {}ms. Error: {}", failures, backoff, e.getMessage(), e);
        rescheduleWithDelay(backoff);
    }

    long computeBackoff(int failures)
    {
        long base = properties.getPollingInterval().toMillis();
        long backoff = base * (1L << Math.min(failures, 20));
        return Math.min(backoff, properties.getMaxPollingInterval().toMillis());
    }

    private void rescheduleWithDelay(long delayMs)
    {
        if (!running.get())
        {
            LOGGER.debug("Service is stopped — skipping reschedule");
            return;
        }
        if (scheduledFuture != null)
        {
            scheduledFuture.cancel(false);
        }
        scheduledFuture = scheduler.scheduleWithFixedDelay(
                this::runCycleSafe,
                delayMs,
                properties.getPollingInterval().toMillis(),
                TimeUnit.MILLISECONDS);
    }

    @Override
    public void stop()
    {
        if (!running.compareAndSet(true, false))
        {
            LOGGER.debug("Continuous reindexing service is not running — ignoring stop() call");
            return;
        }

        LOGGER.info("Stopping continuous reindexing service...");
        if (scheduledFuture != null)
        {
            scheduledFuture.cancel(false);
        }
        LOGGER.info("Continuous reindexing service stopped");
    }

    @Override
    public void stop(Runnable callback)
    {
        stop();
        callback.run();
    }

    /**
     * Shuts down the executor permanently. Called by Spring on context close via {@code destroyMethod}.
     */
    public void destroy()
    {
        scheduler.shutdown();
        try
        {
            if (!scheduler.awaitTermination(120, TimeUnit.SECONDS))
            {
                LOGGER.warn("Continuous reindexing executor did not terminate within 120s, forcing shutdown");
                scheduler.shutdownNow();
            }
        }
        catch (InterruptedException e)
        {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning()
    {
        return running.get();
    }

    @Override
    public boolean isAutoStartup()
    {
        return autoStartup;
    }

    @Override
    public int getPhase()
    {
        return SmartLifecycle.DEFAULT_PHASE;
    }
}
