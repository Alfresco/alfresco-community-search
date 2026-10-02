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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.infrastructure.item.Chunk;

import org.alfresco.metrics.MetricsConstants;
import org.alfresco.metrics.ReindexingMetrics;
import org.alfresco.reindexing.config.ReindexingCoreProperties;
import org.alfresco.reindexing.writers.ChunkWriteMetricsAccumulator;
import org.alfresco.reindexing.writers.WriterTimingSummary;
import org.alfresco.repo.event.EventAttributes;

class WriteMetricsFlushListenerTest
{
    private ChunkWriteMetricsAccumulator accumulator;
    private ReindexingMetrics metrics;
    private ReindexingCoreProperties properties;
    private WriteMetricsFlushListener listener;

    @BeforeEach
    void setUp()
    {
        accumulator = mock(ChunkWriteMetricsAccumulator.class);
        metrics = mock(ReindexingMetrics.class);
        properties = mock(ReindexingCoreProperties.class);
        listener = new WriteMetricsFlushListener(accumulator, metrics, properties, new WriterTimingSummary(false));
    }

    @Test
    void beforeWriteAlwaysResetsTheAccumulator()
    {
        listener.beforeWrite(chunkOfSize(2));

        verify(accumulator).reset();
        verifyNoInteractions(metrics);
    }

    @Test
    void afterWriteAlwaysFlushesTheAccumulatorIntoMetrics()
    {
        listener.afterWrite(chunkOfSize(2));

        verify(accumulator).flush(metrics);
    }

    @Test
    void onWriteErrorWhenSomeModulesEnabledResetsAndChargesFailureToEnabledModulesOnly()
    {
        when(properties.getEnabledWriterKeys()).thenReturn(List.of(MetricsConstants.METADATA, MetricsConstants.CONTENT));
        Chunk<EventAttributes> chunk = chunkOfSize(3);

        listener.onWriteError(new RuntimeException("boom"), chunk);

        verify(accumulator).reset();
        verify(metrics).reindexWriteFailure(MetricsConstants.METADATA, 3L);
        verify(metrics).reindexWriteFailure(MetricsConstants.CONTENT, 3L);
        verify(metrics, never()).reindexWriteFailure(MetricsConstants.PATH, 3L);
    }

    @Test
    void onWriteErrorInMergedModeChargesMergedAndContentNotMetadataOrPath()
    {
        when(properties.getEnabledWriterKeys()).thenReturn(List.of(MetricsConstants.MERGED, MetricsConstants.CONTENT));
        Chunk<EventAttributes> chunk = chunkOfSize(5);

        listener.onWriteError(new RuntimeException("boom"), chunk);

        verify(accumulator).reset();
        verify(metrics).reindexWriteFailure(MetricsConstants.MERGED, 5L);
        verify(metrics).reindexWriteFailure(MetricsConstants.CONTENT, 5L);
        verify(metrics, never()).reindexWriteFailure(MetricsConstants.METADATA, 5L);
        verify(metrics, never()).reindexWriteFailure(MetricsConstants.PATH, 5L);
    }

    @Test
    void onWriteErrorWhenAllModulesDisabledOnlyResetsAndRecordsNoFailures()
    {
        when(properties.getEnabledWriterKeys()).thenReturn(List.of());

        listener.onWriteError(new RuntimeException("boom"), chunkOfSize(4));

        verify(accumulator).reset();
        verifyNoInteractions(metrics);
    }

    private static Chunk<EventAttributes> chunkOfSize(int size)
    {
        Chunk<EventAttributes> chunk = new Chunk<>();
        for (int i = 0; i < size; i++)
        {
            chunk.add(mock(EventAttributes.class));
        }
        return chunk;
    }
}
