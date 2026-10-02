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
package org.alfresco.reindexing.listeners;

import java.util.Map;

import org.springframework.batch.core.listener.ItemWriteListener;
import org.springframework.batch.infrastructure.item.Chunk;

import org.alfresco.metrics.ReindexingMetrics;
import org.alfresco.reindexing.config.ReindexingCoreProperties;
import org.alfresco.reindexing.writers.ChunkWriteMetricsAccumulator;
import org.alfresco.reindexing.writers.WriterTimingSummary;
import org.alfresco.repo.event.EventAttributes;

public class WriteMetricsFlushListener implements ItemWriteListener<EventAttributes>
{
    private final ChunkWriteMetricsAccumulator accumulator;
    private final ReindexingMetrics metrics;
    private final ReindexingCoreProperties reindexProperties;
    private final WriterTimingSummary writerTimingSummary;

    public WriteMetricsFlushListener(ChunkWriteMetricsAccumulator accumulator, ReindexingMetrics metrics,
            ReindexingCoreProperties reindexProperties, WriterTimingSummary writerTimingSummary)
    {
        this.accumulator = accumulator;
        this.metrics = metrics;
        this.reindexProperties = reindexProperties;
        this.writerTimingSummary = writerTimingSummary;
    }

    @Override
    public void beforeWrite(Chunk<? extends EventAttributes> items)
    {
        accumulator.reset();
    }

    @Override
    public void afterWrite(Chunk<? extends EventAttributes> items)
    {
        Map<String, Long> published = accumulator.flush(metrics);
        if (writerTimingSummary != null && published != null)
        {
            published.forEach(writerTimingSummary::record);
        }
    }

    @Override
    public void onWriteError(Exception exception, Chunk<? extends EventAttributes> items)
    {
        // Retries are exhausted and the chunk will not commit. Discard the buffered successes/durations and charge a
        // whole-chunk failure to every enabled writer, so the failed chunk is visible across all writers. The enabled
        // set (merged vs metadata/path, plus content) comes from the single source of truth on the properties.
        accumulator.reset();
        long chunkSize = items.size();
        for (String writerKey : reindexProperties.getEnabledWriterKeys())
        {
            metrics.reindexWriteFailure(writerKey, chunkSize);
        }
    }
}
