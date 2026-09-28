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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.alfresco.metrics.MetricsConstants;
import org.alfresco.metrics.ReindexingMetrics;

class ChunkWriteMetricsAccumulatorTest
{
    private ChunkWriteMetricsAccumulator accumulator;
    private ReindexingMetrics metrics;

    @BeforeEach
    void setUp()
    {
        accumulator = new ChunkWriteMetricsAccumulator();
        metrics = mock(ReindexingMetrics.class);
    }

    @Test
    void flushWhenCountsAndDurationsBufferedPublishesThemThenClearsBuffers()
    {
        accumulator.recordCount(MetricsConstants.METADATA, 7L, 2L);
        accumulator.recordDuration(MetricsConstants.METADATA, 123L);

        accumulator.flush(metrics);

        verify(metrics).reindexWriteSuccess(MetricsConstants.METADATA, 7L);
        verify(metrics).reindexWriteFailure(MetricsConstants.METADATA, 2L);
        verify(metrics).recordChunkWriteDuration(MetricsConstants.METADATA, 123L);
        verifyNoMoreInteractions(metrics);
    }

    @Test
    void flushWhenCalledSecondTimeAfterFirstRecordsNothingBecauseBuffersCleared()
    {
        accumulator.recordCount(MetricsConstants.CONTENT, 3L, 1L);
        accumulator.recordDuration(MetricsConstants.CONTENT, 50L);
        accumulator.flush(metrics);

        ReindexingMetrics secondMetrics = mock(ReindexingMetrics.class);
        accumulator.flush(secondMetrics);

        verifyNoInteractions(secondMetrics);
    }

    @Test
    void resetWhenCalledBeforeFlushDiscardsBufferedCountsAndDurations()
    {
        accumulator.recordCount(MetricsConstants.PATH, 5L, 4L);
        accumulator.recordDuration(MetricsConstants.PATH, 999L);

        accumulator.reset();
        accumulator.flush(metrics);

        verifyNoInteractions(metrics);
    }

    @Test
    void flushWhenMultipleModulesBufferedPublishesEachModuleIndependently()
    {
        accumulator.recordCount(MetricsConstants.METADATA, 1L, 0L);
        accumulator.recordCount(MetricsConstants.CONTENT, 2L, 1L);
        accumulator.recordDuration(MetricsConstants.METADATA, 10L);
        accumulator.recordDuration(MetricsConstants.CONTENT, 20L);

        accumulator.flush(metrics);

        verify(metrics).reindexWriteSuccess(MetricsConstants.METADATA, 1L);
        verify(metrics).reindexWriteFailure(MetricsConstants.METADATA, 0L);
        verify(metrics).reindexWriteSuccess(MetricsConstants.CONTENT, 2L);
        verify(metrics).reindexWriteFailure(MetricsConstants.CONTENT, 1L);
        verify(metrics).recordChunkWriteDuration(MetricsConstants.METADATA, 10L);
        verify(metrics).recordChunkWriteDuration(MetricsConstants.CONTENT, 20L);
        verifyNoMoreInteractions(metrics);
    }

    @Test
    void recordCountWhenSameModuleRecordedTwiceLastWriteWinsWithinChunk()
    {
        accumulator.recordCount(MetricsConstants.METADATA, 1L, 1L);
        accumulator.recordCount(MetricsConstants.METADATA, 9L, 3L);

        accumulator.flush(metrics);

        verify(metrics).reindexWriteSuccess(MetricsConstants.METADATA, 9L);
        verify(metrics).reindexWriteFailure(MetricsConstants.METADATA, 3L);
        verifyNoMoreInteractions(metrics);
    }

    @Test
    void buffersWhenValuesRecordedOnAnotherThreadAreInvisibleToFlushingThread() throws InterruptedException
    {
        Thread worker = new Thread(() -> {
            accumulator.recordCount(MetricsConstants.METADATA, 100L, 50L);
            accumulator.recordDuration(MetricsConstants.METADATA, 500L);
        });
        worker.start();
        worker.join();

        // The flushing (main) thread has its own ThreadLocal buffers, so the worker's values are not visible here.
        accumulator.flush(metrics);

        verifyNoInteractions(metrics);
    }
}
