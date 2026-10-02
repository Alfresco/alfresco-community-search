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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import org.alfresco.elasticsearch.batchindexing.config.ContinuousReindexingProperties;

class ContinuousReindexingMonitorTest
{
    private static final Duration THRESHOLD = Duration.ofMinutes(10);

    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private final ContinuousReindexingProperties properties = properties();

    private ContinuousReindexingMonitor newMonitor(List<ReindexCycleListener> listeners)
    {
        return new ContinuousReindexingMonitor(properties, listeners, clock::get);
    }

    private ContinuousReindexingMonitor newMonitor()
    {
        return newMonitor(List.of());
    }

    @Test
    @DisplayName("no activity recorded — UP with state=starting")
    void beforeAnyActivity()
    {
        Health health = newMonitor().health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("state", "starting");
    }

    @Test
    @DisplayName("activity within threshold — UP with state=progressing")
    void withinThreshold()
    {
        ContinuousReindexingMonitor monitor = newMonitor();
        monitor.notifyCycleStart();
        clock.addAndGet(THRESHOLD.toMillis() / 2);

        Health health = monitor.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("state", "progressing");
    }

    @Test
    @DisplayName("activity older than threshold — DOWN with state=stuck")
    void beyondThreshold()
    {
        ContinuousReindexingMonitor monitor = newMonitor();
        monitor.notifyCycleStart();
        clock.addAndGet(THRESHOLD.toMillis() + 1);

        Health health = monitor.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("state", "stuck");
    }

    @Test
    @DisplayName("repeated cycle failures — DOWN with state=failing, even while activity keeps ticking")
    void repeatedCycleFailuresReportDown()
    {
        ContinuousReindexingMonitor monitor = newMonitor();

        // Every failed cycle still ticks activity, which is why the idle-based check alone sees nothing wrong.
        for (int failures = 1; failures <= 3; failures++)
        {
            monitor.notifyCycleStart();
            monitor.notifyCycleFailure(failures);
            monitor.notifyCycleEnd();
        }

        Health health = monitor.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("state", "failing");
        assertThat(health.getDetails()).containsEntry("consecutiveFailures", 3);
    }

    @Test
    @DisplayName("failures below the limit stay UP — a transient blip must not flap the probe")
    void failuresBelowTheLimitStayUp()
    {
        ContinuousReindexingMonitor monitor = newMonitor();
        monitor.notifyCycleStart();
        monitor.notifyCycleFailure(1);
        monitor.notifyCycleEnd();
        monitor.notifyCycleStart();
        monitor.notifyCycleFailure(2);
        monitor.notifyCycleEnd();

        Health health = monitor.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("state", "progressing");
    }

    @Test
    @DisplayName("a successful cycle clears the failure state")
    void successClearsTheFailureState()
    {
        ContinuousReindexingMonitor monitor = newMonitor();
        monitor.notifyCycleStart();
        monitor.notifyCycleFailure(3);
        monitor.notifyCycleEnd();
        assertThat(monitor.health().getStatus()).isEqualTo(Status.DOWN);

        monitor.notifyCycleSuccess();

        assertThat(monitor.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    @DisplayName("a limit of zero disables the check")
    void zeroLimitDisablesTheCheck()
    {
        properties.setUnhealthyAfterConsecutiveFailures(0);
        ContinuousReindexingMonitor monitor = newMonitor();
        monitor.notifyCycleStart();
        monitor.notifyCycleFailure(99);
        monitor.notifyCycleEnd();

        assertThat(monitor.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    @DisplayName("recordActivity refreshes the heartbeat — recovers from near-stuck")
    void recordActivityRefreshes()
    {
        ContinuousReindexingMonitor monitor = newMonitor();
        monitor.notifyCycleStart();
        clock.addAndGet(THRESHOLD.toMillis() - 1_000);
        monitor.recordActivity();
        clock.addAndGet(THRESHOLD.toMillis() - 1_000);

        Health health = monitor.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("state", "progressing");
    }

    @Test
    @DisplayName("notifyCycleEnd advances both timestamps")
    void notifyCycleEndAdvancesBothTimestamps()
    {
        ContinuousReindexingMonitor monitor = newMonitor();
        monitor.notifyCycleStart();
        long endAt = clock.addAndGet(1_000);
        monitor.notifyCycleEnd();

        assertThat(monitor.getLastActivityAtEpochMillis()).isEqualTo(endAt);
        assertThat(monitor.getLastCycleFinishedAtEpochMillis()).isEqualTo(endAt);
    }

    @Test
    @DisplayName("notifyCycleStart does not advance the last-finished timestamp")
    void notifyCycleStartDoesNotAdvanceCycleFinished()
    {
        ContinuousReindexingMonitor monitor = newMonitor();
        monitor.notifyCycleEnd();
        long firstEndAt = monitor.getLastCycleFinishedAtEpochMillis();
        clock.addAndGet(1_000);
        monitor.notifyCycleStart();

        assertThat(monitor.getLastCycleFinishedAtEpochMillis()).isEqualTo(firstEndAt);
        assertThat(monitor.getLastActivityAtEpochMillis()).isEqualTo(clock.get());
    }

    @Test
    @DisplayName("notify* methods invoke every registered listener in order")
    void invokesEveryListenerInOrder()
    {
        ReindexCycleListener listenerA = mock(ReindexCycleListener.class);
        ReindexCycleListener listenerB = mock(ReindexCycleListener.class);
        ContinuousReindexingMonitor monitor = newMonitor(List.of(listenerA, listenerB));

        monitor.notifyCycleStart();
        monitor.notifyJobStart();
        monitor.notifyJobSuccess();
        monitor.notifyJobEnd();
        monitor.notifyCycleEnd();

        InOrder inOrder = Mockito.inOrder(listenerA, listenerB);
        for (ReindexCycleListener l : List.of(listenerA, listenerB))
        {
            inOrder.verify(l).onCycleStart();
        }
        for (ReindexCycleListener l : List.of(listenerA, listenerB))
        {
            inOrder.verify(l).onJobStart();
        }
        for (ReindexCycleListener l : List.of(listenerA, listenerB))
        {
            inOrder.verify(l).onJobSuccess();
        }
        for (ReindexCycleListener l : List.of(listenerA, listenerB))
        {
            inOrder.verify(l).onJobEnd();
        }
        for (ReindexCycleListener l : List.of(listenerA, listenerB))
        {
            inOrder.verify(l).onCycleEnd();
        }
    }

    @Test
    @DisplayName("a throwing listener does not stop other listeners or affect state")
    void listenerFailuresAreIsolated()
    {
        ReindexCycleListener broken = mock(ReindexCycleListener.class);
        Mockito.doThrow(new RuntimeException("boom")).when(broken).onJobStart();
        ReindexCycleListener healthy = mock(ReindexCycleListener.class);
        ContinuousReindexingMonitor monitor = newMonitor(List.of(broken, healthy));

        monitor.notifyJobStart();

        then(healthy).should().onJobStart();
        assertThat(monitor.getLastActivityAtEpochMillis()).isEqualTo(clock.get());
    }

    private static ContinuousReindexingProperties properties()
    {
        ContinuousReindexingProperties props = new ContinuousReindexingProperties();
        props.setMaxPollingInterval(Duration.ofMinutes(5));
        props.setStuckThreshold(THRESHOLD);
        return props;
    }
}
