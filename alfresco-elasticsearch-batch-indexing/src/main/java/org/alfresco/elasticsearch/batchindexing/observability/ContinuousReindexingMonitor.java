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
package org.alfresco.elasticsearch.batchindexing.observability;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

import org.alfresco.elasticsearch.batchindexing.config.ContinuousReindexingProperties;

/**
 * Observability hub for the continuous reindexing scheduler. The scheduling service calls {@code notify*} methods at cycle and job boundaries; the monitor records heartbeats for its own {@link HealthIndicator} view and fans the event out to registered {@link ReindexCycleListener}s (e.g. {@code CascadeSweepService}).
 * <p>
 * Reports DOWN when no sign of life has been seen within {@code alfresco.reindex.continuous.stuckThreshold}. Signs of life are cycle start/end, job start/success/end, and each committed chunk (fed in via {@link #recordActivity()} from {@code ProgressHeartbeatListener}).
 * <p>
 * {@link #health()} is a non-blocking read of two {@link AtomicLong}s, so a stuck scheduler thread does not block this indicator from answering.
 */
public class ContinuousReindexingMonitor implements HealthIndicator
{
    private static final Logger LOGGER = LoggerFactory.getLogger(ContinuousReindexingMonitor.class);

    private final ContinuousReindexingProperties properties;
    private final LongSupplier nowMillisSupplier;
    private final List<ReindexCycleListener> listeners;

    private final AtomicLong lastActivityAtEpochMillis = new AtomicLong();
    private final AtomicLong lastCycleFinishedAtEpochMillis = new AtomicLong();

    public ContinuousReindexingMonitor(ContinuousReindexingProperties properties,
            List<ReindexCycleListener> listeners)
    {
        this(properties, listeners, System::currentTimeMillis);
    }

    ContinuousReindexingMonitor(ContinuousReindexingProperties properties,
            List<ReindexCycleListener> listeners,
            LongSupplier nowMillisSupplier)
    {
        this.properties = properties;
        this.listeners = List.copyOf(Objects.requireNonNull(listeners, "listeners"));
        this.nowMillisSupplier = nowMillisSupplier;
    }

    public void notifyCycleStart()
    {
        lastActivityAtEpochMillis.set(nowMillisSupplier.getAsLong());
        invokeListeners("onCycleStart", ReindexCycleListener::onCycleStart);
    }

    public void notifyCycleEnd()
    {
        long now = nowMillisSupplier.getAsLong();
        lastActivityAtEpochMillis.set(now);
        lastCycleFinishedAtEpochMillis.set(now);
        invokeListeners("onCycleEnd", ReindexCycleListener::onCycleEnd);
    }

    public void notifyJobStart()
    {
        lastActivityAtEpochMillis.set(nowMillisSupplier.getAsLong());
        invokeListeners("onJobStart", ReindexCycleListener::onJobStart);
    }

    public void notifyJobSuccess()
    {
        lastActivityAtEpochMillis.set(nowMillisSupplier.getAsLong());
        invokeListeners("onJobSuccess", ReindexCycleListener::onJobSuccess);
    }

    public void notifyJobEnd()
    {
        lastActivityAtEpochMillis.set(nowMillisSupplier.getAsLong());
        invokeListeners("onJobEnd", ReindexCycleListener::onJobEnd);
    }

    /**
     * Records a sign of life during a running job. Called after every committed chunk by {@code ProgressHeartbeatListener}.
     */
    public void recordActivity()
    {
        lastActivityAtEpochMillis.set(nowMillisSupplier.getAsLong());
    }

    /**
     * Epoch milliseconds of the last sign of life from the scheduler (cycle start/end, job start/success/end, or committed chunk). Zero if never observed. Exposed for Micrometer gauge registration.
     */
    public long getLastActivityAtEpochMillis()
    {
        return lastActivityAtEpochMillis.get();
    }

    /**
     * Epoch milliseconds when the last cycle finished. Zero if never observed. Exposed for Micrometer gauge registration.
     */
    public long getLastCycleFinishedAtEpochMillis()
    {
        return lastCycleFinishedAtEpochMillis.get();
    }

    @Override
    public Health health()
    {
        long lastActivityAt = lastActivityAtEpochMillis.get();
        Duration threshold = properties.getStuckThreshold();

        if (lastActivityAt == 0)
        {
            // Startup grace: the first activity tick lands on the very first cycle start,
            // so this window closes fast. Treat as UP with a status marker.
            return Health.up()
                    .withDetail("state", "starting")
                    .withDetail("stuckThreshold", threshold.toString())
                    .build();
        }

        long now = nowMillisSupplier.getAsLong();
        Duration idle = Duration.ofMillis(now - lastActivityAt);
        if (idle.compareTo(threshold) > 0)
        {
            return Health.down()
                    .withDetail("state", "stuck")
                    .withDetail("lastActivityAtEpochMillis", lastActivityAt)
                    .withDetail("idle", idle.toString())
                    .withDetail("stuckThreshold", threshold.toString())
                    .build();
        }

        return Health.up()
                .withDetail("state", "progressing")
                .withDetail("lastActivityAtEpochMillis", lastActivityAt)
                .withDetail("idle", idle.toString())
                .withDetail("stuckThreshold", threshold.toString())
                .build();
    }

    private void invokeListeners(String hookName, Consumer<ReindexCycleListener> hook)
    {
        for (ReindexCycleListener listener : listeners)
        {
            try
            {
                hook.accept(listener);
            }
            catch (RuntimeException ex)
            {
                LOGGER.warn("Cycle listener {} failed {}: {}",
                        listener.getClass().getSimpleName(), hookName, ex.getMessage(), ex);
            }
        }
    }
}
