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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.alfresco.metrics.ReindexingMetrics;

public class ChunkWriteMetricsAccumulator
{
    private final ThreadLocal<Map<String, Counts>> countBuffer = ThreadLocal.withInitial(ConcurrentHashMap::new);

    private final ThreadLocal<Map<String, Long>> durationBuffer = ThreadLocal.withInitial(ConcurrentHashMap::new);

    private record Counts(long success, long failure)
    {}

    /**
     * Snapshot of a chunk thread's buffers so worker threads (see {@link ConcurrentCompositeItemWriter}) can record into the SAME maps as the owning chunk thread. The buffers are {@link ConcurrentHashMap}s and each writer records under a distinct module key, so concurrent puts are safe and the {@code beforeWrite}/{@code afterWrite} flush on the chunk thread still sees everything.
     */
    public record Buffers(Map<String, Counts> counts, Map<String, Long> durations)
    {}

    public void recordCount(String module, long success, long failure)
    {
        countBuffer.get().put(module, new Counts(success, failure));
    }

    public void recordDuration(String module, long durationMs)
    {
        durationBuffer.get().put(module, durationMs);
    }

    /** Capture the current thread's buffers so they can be adopted by worker threads. */
    public Buffers captureBuffers()
    {
        return new Buffers(countBuffer.get(), durationBuffer.get());
    }

    /** Run {@code task} on the current (worker) thread with the given buffers bound, then restore. */
    public void runWithBuffers(Buffers buffers, Runnable task)
    {
        Map<String, Counts> previousCounts = countBuffer.get();
        Map<String, Long> previousDurations = durationBuffer.get();
        countBuffer.set(buffers.counts());
        durationBuffer.set(buffers.durations());
        try
        {
            task.run();
        }
        finally
        {
            countBuffer.set(previousCounts);
            durationBuffer.set(previousDurations);
        }
    }

    public void reset()
    {
        countBuffer.get().clear();
        durationBuffer.get().clear();
    }

    public Map<String, Long> flush(ReindexingMetrics metrics)
    {
        Map<String, Counts> counts = countBuffer.get();
        counts.forEach((module, c) -> {
            metrics.reindexWriteSuccess(module, c.success());
            metrics.reindexWriteFailure(module, c.failure());
        });
        counts.clear();

        Map<String, Long> durations = durationBuffer.get();
        Map<String, Long> published = Map.copyOf(durations);
        durations.forEach(metrics::recordChunkWriteDuration);
        durations.clear();
        return published;
    }
}
