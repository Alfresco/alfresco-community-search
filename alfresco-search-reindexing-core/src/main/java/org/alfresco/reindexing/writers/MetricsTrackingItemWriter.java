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

import org.jspecify.annotations.NonNull;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.item.ItemStream;
import org.springframework.batch.infrastructure.item.ItemStreamException;
import org.springframework.batch.infrastructure.item.ItemStreamWriter;
import org.springframework.batch.infrastructure.item.ItemWriter;

/**
 * An {@link ItemStreamWriter} decorator that records the duration of each chunk write. It delegates the actual writing to another {@link ItemWriter} and buffers the time taken into a {@link ChunkWriteMetricsAccumulator}, so the duration is published exactly once per committed chunk rather than on every fault-tolerant write retry.
 *
 * Uses {@link System#nanoTime()} for timing to prevent skewed durations caused by system clock adjustments (NTP/VM changes).
 *
 * @author Sayan Bhattacharya
 */
public class MetricsTrackingItemWriter<T> implements ItemStreamWriter<T>
{
    private final ItemWriter<T> delegate;
    private final ChunkWriteMetricsAccumulator writeMetricsAccumulator;
    private final String module;

    public MetricsTrackingItemWriter(ItemWriter<T> delegate, ChunkWriteMetricsAccumulator writeMetricsAccumulator, String module)
    {
        this.delegate = delegate;
        this.writeMetricsAccumulator = writeMetricsAccumulator;
        this.module = module;
    }

    @Override
    public void write(Chunk<? extends T> chunk) throws Exception
    {
        long startNanos = System.nanoTime();
        delegate.write(chunk);
        long endNanos = System.nanoTime();
        long durationMs = (endNanos - startNanos) / 1_000_000;
        writeMetricsAccumulator.recordDuration(module, durationMs);
    }

    @Override
    public void open(@NonNull ExecutionContext executionContext) throws ItemStreamException
    {
        if (delegate instanceof ItemStream stream)
        {
            stream.open(executionContext);
        }
    }

    @Override
    public void update(@NonNull ExecutionContext executionContext) throws ItemStreamException
    {
        if (delegate instanceof ItemStream stream)
        {
            stream.update(executionContext);
        }
    }

    @Override
    public void close() throws ItemStreamException
    {
        if (delegate instanceof ItemStream stream)
        {
            stream.close();
        }
    }
}
