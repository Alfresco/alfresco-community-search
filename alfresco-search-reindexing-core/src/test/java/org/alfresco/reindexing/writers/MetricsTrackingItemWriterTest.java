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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.item.ItemStreamWriter;
import org.springframework.batch.infrastructure.item.ItemWriter;

import org.alfresco.metrics.MetricsConstants;

class MetricsTrackingItemWriterTest
{
    private ChunkWriteMetricsAccumulator accumulator;

    @BeforeEach
    void setUp()
    {
        accumulator = mock(ChunkWriteMetricsAccumulator.class);
    }

    @Test
    void writeWhenDelegateSucceedsDelegatesAndRecordsNonNegativeDuration() throws Exception
    {
        @SuppressWarnings("unchecked")
        ItemWriter<String> delegate = mock(ItemWriter.class);
        MetricsTrackingItemWriter<String> writer = new MetricsTrackingItemWriter<>(delegate, accumulator, MetricsConstants.METADATA);
        Chunk<String> chunk = Chunk.of("a", "b");

        writer.write(chunk);

        verify(delegate).write(chunk);
        ArgumentCaptor<Long> durationCaptor = ArgumentCaptor.forClass(Long.class);
        verify(accumulator).recordDuration(eq(MetricsConstants.METADATA), durationCaptor.capture());
        assertThat(durationCaptor.getValue()).isGreaterThanOrEqualTo(0L);
    }

    @Test
    void writeWhenDelegateThrowsPropagatesExceptionAndDoesNotRecordDuration() throws Exception
    {
        @SuppressWarnings("unchecked")
        ItemWriter<String> delegate = mock(ItemWriter.class);
        Chunk<String> chunk = Chunk.of("a");
        doThrow(new IllegalStateException("write failed")).when(delegate).write(chunk);
        MetricsTrackingItemWriter<String> writer = new MetricsTrackingItemWriter<>(delegate, accumulator, MetricsConstants.CONTENT);

        assertThatThrownBy(() -> writer.write(chunk))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("write failed");

        verifyNoInteractions(accumulator);
    }

    @Test
    void streamMethodsWhenDelegateIsItemStreamForwardToDelegate() throws Exception
    {
        @SuppressWarnings("unchecked")
        ItemStreamWriter<String> delegate = mock(ItemStreamWriter.class);
        MetricsTrackingItemWriter<String> writer = new MetricsTrackingItemWriter<>(delegate, accumulator, MetricsConstants.PATH);
        ExecutionContext executionContext = new ExecutionContext();

        writer.open(executionContext);
        writer.update(executionContext);
        writer.close();

        verify(delegate).open(executionContext);
        verify(delegate).update(executionContext);
        verify(delegate).close();
    }

    @Test
    void streamMethodsWhenDelegateIsPlainItemWriterAreSafeNoOps()
    {
        @SuppressWarnings("unchecked")
        ItemWriter<String> delegate = mock(ItemWriter.class);
        MetricsTrackingItemWriter<String> writer = new MetricsTrackingItemWriter<>(delegate, accumulator, MetricsConstants.METADATA);
        ExecutionContext executionContext = new ExecutionContext();

        // A plain ItemWriter is not an ItemStream, so these must not fail and must not touch the delegate.
        writer.open(executionContext);
        writer.update(executionContext);
        writer.close();

        verifyNoInteractions(delegate);
    }
}
