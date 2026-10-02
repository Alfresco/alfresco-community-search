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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.item.ItemStreamWriter;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import org.alfresco.metrics.MetricsConstants;
import org.alfresco.metrics.ReindexingMetrics;

class ConcurrentCompositeItemWriterTest
{
    private ThreadPoolTaskExecutor executor;

    @BeforeEach
    void setUp()
    {
        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(4);
        executor.setThreadNamePrefix("test-writer-");
        executor.initialize();
    }

    @AfterEach
    void tearDown()
    {
        executor.shutdown();
    }

    @Test
    void writeDispatchesChunkToEveryDelegate() throws Exception
    {
        @SuppressWarnings("unchecked")
        ItemWriter<String> a = mock(ItemWriter.class);
        @SuppressWarnings("unchecked")
        ItemWriter<String> b = mock(ItemWriter.class);
        @SuppressWarnings("unchecked")
        ItemWriter<String> c = mock(ItemWriter.class);
        var writer = new ConcurrentCompositeItemWriter<>(List.of(a, b, c), executor, null);
        Chunk<String> chunk = Chunk.of("x", "y");

        writer.write(chunk);

        verify(a).write(chunk);
        verify(b).write(chunk);
        verify(c).write(chunk);
    }

    @Test
    void writeWhenADelegateThrowsPropagatesThatException() throws Exception
    {
        @SuppressWarnings("unchecked")
        ItemWriter<String> ok = mock(ItemWriter.class);
        @SuppressWarnings("unchecked")
        ItemWriter<String> boom = mock(ItemWriter.class);
        Chunk<String> chunk = Chunk.of("x");
        doThrow(new IllegalStateException("delegate failed")).when(boom).write(chunk);
        // boom is not index 0, so it runs on a worker thread and comes back through the future.
        var writer = new ConcurrentCompositeItemWriter<>(List.of(ok, boom), executor, null);

        assertThatThrownBy(() -> writer.write(chunk))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("delegate failed");
    }

    @Test
    void writeRecordsWorkerThreadMetricsIntoTheChunkThreadBuffers() throws Exception
    {
        ChunkWriteMetricsAccumulator accumulator = new ChunkWriteMetricsAccumulator();
        // Inline delegate (index 0) records metadata on the chunk thread; the other records path on a worker.
        ItemWriter<String> metadata = chunk -> accumulator.recordCount(MetricsConstants.METADATA, 2, 0);
        ItemWriter<String> path = chunk -> accumulator.recordCount(MetricsConstants.PATH, 2, 0);
        var writer = new ConcurrentCompositeItemWriter<>(List.of(metadata, path), executor, accumulator);

        accumulator.reset();
        writer.write(Chunk.of("x", "y"));

        ReindexingMetrics metrics = mock(ReindexingMetrics.class);
        accumulator.flush(metrics);
        // Both modules must be visible to the chunk-thread flush despite the path write running on a worker.
        verify(metrics).reindexWriteSuccess(MetricsConstants.METADATA, 2);
        verify(metrics).reindexWriteSuccess(MetricsConstants.PATH, 2);
    }

    @Test
    void streamLifecycleIsCascadedToItemStreamDelegates() throws Exception
    {
        @SuppressWarnings("unchecked")
        ItemStreamWriter<String> stream = mock(ItemStreamWriter.class);
        @SuppressWarnings("unchecked")
        ItemWriter<String> plain = mock(ItemWriter.class);
        var writer = new ConcurrentCompositeItemWriter<>(List.of(stream, plain), executor, null);
        ExecutionContext ctx = new ExecutionContext();

        writer.open(ctx);
        writer.update(ctx);
        writer.close();

        verify(stream).open(ctx);
        verify(stream).update(ctx);
        verify(stream).close();
    }

    @Test
    void writeRunsEachDelegateExactlyOncePerChunk() throws Exception
    {
        Queue<String> calls = new ConcurrentLinkedQueue<>();
        ItemWriter<String> a = chunk -> calls.add("a");
        ItemWriter<String> b = chunk -> calls.add("b");
        ItemWriter<String> c = chunk -> calls.add("c");
        var writer = new ConcurrentCompositeItemWriter<>(List.of(a, b, c), executor, null);

        writer.write(Chunk.of("x"));

        assertThat(calls).containsExactlyInAnyOrder("a", "b", "c");
    }

    @Test
    void dependencySafeModeRunsFirstDelegateToCompletionBeforeTheRest() throws Exception
    {
        Queue<String> order = new ConcurrentLinkedQueue<>();
        ItemWriter<String> metadata = chunk -> {
            Thread.sleep(100);
            order.add("metadata");
        };
        ItemWriter<String> path = chunk -> order.add("path");
        var writer = new ConcurrentCompositeItemWriter<>(List.of(metadata, path), executor, null, false);

        writer.write(Chunk.of("x"));

        // In dependency-safe mode metadata must finish before path is even submitted.
        assertThat(order).containsExactly("metadata", "path");
    }

    @Test
    void dependencySafeModeSkipsDependentWritesWhenFirstDelegateFails() throws Exception
    {
        Queue<String> calls = new ConcurrentLinkedQueue<>();
        ItemWriter<String> metadata = mock(ItemWriter.class);
        Chunk<String> chunk = Chunk.of("x");
        doThrow(new IllegalStateException("metadata failed")).when(metadata).write(chunk);
        ItemWriter<String> path = c -> calls.add("path");
        var writer = new ConcurrentCompositeItemWriter<>(List.of(metadata, path), executor, null, false);

        assertThatThrownBy(() -> writer.write(chunk))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("metadata failed");
        // Path must never run when the metadata write (the doc create) failed.
        assertThat(calls).isEmpty();
    }

    @Test
    void writeWhenDelegateExceedsJoinTimeoutFailsTheChunk()
    {
        // A tail delegate (worker thread) that outlives the 1s join timeout must fail the chunk.
        ItemWriter<String> fast = chunk -> {};
        ItemWriter<String> slow = chunk -> Thread.sleep(5_000);
        var writer = new ConcurrentCompositeItemWriter<>(List.of(fast, slow), executor, null, true, 1L);

        assertThatThrownBy(() -> writer.write(Chunk.of("x")))
                .isInstanceOf(TimeoutException.class);
    }

    @Test
    void constructorRejectsNullDelegatesAndExecutor()
    {
        assertThatThrownBy(() -> new ConcurrentCompositeItemWriter<>(null, executor, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("delegates must not be null");
        assertThatThrownBy(() -> new ConcurrentCompositeItemWriter<>(List.of(mock(ItemWriter.class)), null, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("taskExecutor must not be null");
    }
}
