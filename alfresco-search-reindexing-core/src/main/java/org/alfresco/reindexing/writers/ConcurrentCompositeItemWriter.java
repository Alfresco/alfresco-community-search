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

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.item.ItemStream;
import org.springframework.batch.infrastructure.item.ItemStreamException;
import org.springframework.batch.infrastructure.item.ItemStreamWriter;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.core.task.AsyncTaskExecutor;

/**
 * A concurrent drop-in replacement for Spring Batch's {@code CompositeItemWriter}.
 * <p>
 * The stock composite writer runs its delegates strictly in order, so each chunk pays the metadata write, then the path write, then the content dispatch back-to-back. This writer runs them concurrently on a dedicated {@link AsyncTaskExecutor}. Two modes, selected by {@code fullConcurrency}:
 * <ul>
 * <li><b>Full fan-out</b> ({@code fullConcurrency=true}): every delegate runs concurrently — the first inline on the calling (chunk) thread, the rest on the executor. Wall-clock per chunk approaches {@code max(metadata, path, content)}. This is only create-safe when the path write is a scripted upsert (see {@code alfresco.reindex.pathUpsertEnabled}); otherwise a path update can race ahead of the metadata upsert and hit {@code document_missing_exception} for brand-new documents.</li>
 * <li><b>Dependency-safe</b> ({@code fullConcurrency=false}): the first delegate (metadata) runs to completion inline first, then the remaining delegates run concurrently on the executor. This preserves the create-order dependency (metadata upsert before path update) so it is safe even without path upsert, at the cost of serialising metadata: wall-clock per chunk approaches {@code metadata + max(path, content)}.</li>
 * </ul>
 * <p>
 * <b>Metrics.</b> The writers record their per-module counts and durations into a {@link ChunkWriteMetricsAccumulator} that is keyed by {@link ThreadLocal} (so the multithreaded step keeps chunks isolated). Because the delegates now run on worker threads, each worker task is wrapped so it adopts the chunk thread's buffers via {@link ChunkWriteMetricsAccumulator#runWithBuffers}; the chunk-boundary flush therefore still sees every module's numbers.
 * <p>
 * <b>Executor.</b> A pool <i>separate</i> from the step's task executor must be supplied — reusing the step pool would let chunk threads occupy every slot while waiting on delegate tasks that can never be scheduled (deadlock).
 * <p>
 * <b>Failures.</b> If any delegate throws, the first exception is rethrown with the rest attached as suppressed, preserving the composite writer's "fail the chunk" contract so Spring Batch retry / skip handling still applies.
 */
@SuppressWarnings({"PMD.SignatureDeclareThrowsException", "PMD.InvalidLogMessageFormat"})
public class ConcurrentCompositeItemWriter<T> implements ItemStreamWriter<T>
{
    private static final Logger LOGGER = LoggerFactory.getLogger(ConcurrentCompositeItemWriter.class);

    private final List<ItemWriter<? super T>> delegates;
    /** The first delegate (typically metadata), or {@code null} when there are no delegates. Hoisted so the hot path does not repeatedly index into {@link #delegates}. */
    private final ItemWriter<? super T> firstDelegate;
    private final AsyncTaskExecutor taskExecutor;
    /** Nullable — when absent no cross-thread metric binding is performed (e.g. in unit tests). */
    private final ChunkWriteMetricsAccumulator metricsAccumulator;
    /**
     * When true, all delegates run concurrently (create-safe only with path upsert). When false, the first delegate (metadata) completes before the rest run, preserving the create-order dependency.
     */
    private final boolean fullConcurrency;
    /** Per-delegate join timeout in seconds; {@code 0} (or negative) waits indefinitely (historical behaviour). */
    private final long joinTimeoutSeconds;

    public ConcurrentCompositeItemWriter(List<ItemWriter<? super T>> delegates, AsyncTaskExecutor taskExecutor,
            ChunkWriteMetricsAccumulator metricsAccumulator)
    {
        this(delegates, taskExecutor, metricsAccumulator, true);
    }

    public ConcurrentCompositeItemWriter(List<ItemWriter<? super T>> delegates, AsyncTaskExecutor taskExecutor,
            ChunkWriteMetricsAccumulator metricsAccumulator, boolean fullConcurrency)
    {
        this(delegates, taskExecutor, metricsAccumulator, fullConcurrency, 0L);
    }

    public ConcurrentCompositeItemWriter(List<ItemWriter<? super T>> delegates, AsyncTaskExecutor taskExecutor,
            ChunkWriteMetricsAccumulator metricsAccumulator, boolean fullConcurrency, long joinTimeoutSeconds)
    {
        this.delegates = List.copyOf(requireNonNull(delegates, "delegates must not be null"));
        this.firstDelegate = this.delegates.isEmpty() ? null : this.delegates.get(0);
        this.taskExecutor = requireNonNull(taskExecutor, "taskExecutor must not be null");
        // metricsAccumulator is intentionally nullable (no cross-thread metric binding in unit tests).
        this.metricsAccumulator = metricsAccumulator;
        this.fullConcurrency = fullConcurrency;
        this.joinTimeoutSeconds = joinTimeoutSeconds;
    }

    @Override
    public void write(Chunk<? extends T> chunk) throws Exception
    {
        if (delegates.isEmpty())
        {
            return;
        }
        if (delegates.size() == 1)
        {
            firstDelegate.write(chunk);
            return;
        }

        ChunkWriteMetricsAccumulator.Buffers buffers = metricsAccumulator == null ? null : metricsAccumulator.captureBuffers();

        if (fullConcurrency)
        {
            writeFullyConcurrent(chunk, buffers);
        }
        else
        {
            writeDependencySafe(chunk, buffers);
        }
    }

    /** Runs delegate 0 inline while delegates 1..n run on the executor, so all overlap. */
    private void writeFullyConcurrent(Chunk<? extends T> chunk, ChunkWriteMetricsAccumulator.Buffers buffers)
            throws Exception
    {
        List<Future<?>> futures = submitTail(chunk, buffers);

        List<Throwable> failures = new ArrayList<>();
        // Run the first (typically the slowest — metadata) delegate inline to avoid an idle chunk thread.
        try
        {
            firstDelegate.write(chunk);
        }
        catch (Exception e)
        {
            failures.add(e);
        }

        joinInto(futures, failures);
        throwIfAny(failures);
    }

    /**
     * Runs the first delegate (metadata) to completion, then delegates 1..n concurrently. If the first delegate fails the chunk is failed immediately and the dependent writes are never issued, so a path update can never race a not-yet-created document.
     */
    private void writeDependencySafe(Chunk<? extends T> chunk, ChunkWriteMetricsAccumulator.Buffers buffers)
            throws Exception
    {
        firstDelegate.write(chunk);

        List<Future<?>> futures = submitTail(chunk, buffers);
        List<Throwable> failures = new ArrayList<>();
        joinInto(futures, failures);
        throwIfAny(failures);
    }

    private List<Future<?>> submitTail(Chunk<? extends T> chunk, ChunkWriteMetricsAccumulator.Buffers buffers)
    {
        List<Future<?>> futures = new ArrayList<>(delegates.size() - 1);
        for (int i = 1; i < delegates.size(); i++)
        {
            ItemWriter<? super T> delegate = delegates.get(i);
            futures.add(taskExecutor.submit(() -> runDelegate(delegate, chunk, buffers)));
        }
        return futures;
    }

    private void joinInto(List<Future<?>> futures, List<Throwable> failures)
    {
        for (Future<?> future : futures)
        {
            try
            {
                if (joinTimeoutSeconds > 0)
                {
                    future.get(joinTimeoutSeconds, TimeUnit.SECONDS);
                }
                else
                {
                    future.get();
                }
            }
            catch (ExecutionException e)
            {
                failures.add(e.getCause() == null ? e : e.getCause());
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                failures.add(e);
            }
            catch (TimeoutException e)
            {
                // The delegate task did not finish within the configured bound; stop waiting, attempt to
                // cancel it and fail the chunk so Spring Batch retry/skip handling applies.
                future.cancel(true);
                failures.add(e);
            }
        }
    }

    private void runDelegate(ItemWriter<? super T> delegate, Chunk<? extends T> chunk,
            ChunkWriteMetricsAccumulator.Buffers buffers)
    {
        Runnable task = () -> {
            try
            {
                delegate.write(chunk);
            }
            catch (Exception e)
            {
                throw new DelegateWriteException(e);
            }
        };
        if (buffers == null)
        {
            task.run();
        }
        else
        {
            metricsAccumulator.runWithBuffers(buffers, task);
        }
    }

    private static void throwIfAny(List<Throwable> failures) throws Exception
    {
        if (failures.isEmpty())
        {
            return;
        }
        Throwable first = failures.get(0);
        if (first instanceof DelegateWriteException dwe && dwe.getCause() != null)
        {
            first = dwe.getCause();
        }
        for (int i = 1; i < failures.size(); i++)
        {
            Throwable other = failures.get(i);
            first.addSuppressed(other instanceof DelegateWriteException dwe && dwe.getCause() != null ? dwe.getCause() : other);
        }
        if (first instanceof Exception exception)
        {
            throw exception;
        }
        throw new IllegalStateException("Concurrent writer delegate failed", first);
    }

    @Override
    public void open(ExecutionContext executionContext) throws ItemStreamException
    {
        for (ItemWriter<? super T> delegate : delegates)
        {
            if (delegate instanceof ItemStream stream)
            {
                stream.open(executionContext);
            }
        }
    }

    @Override
    public void update(ExecutionContext executionContext) throws ItemStreamException
    {
        for (ItemWriter<? super T> delegate : delegates)
        {
            if (delegate instanceof ItemStream stream)
            {
                stream.update(executionContext);
            }
        }
    }

    @Override
    public void close() throws ItemStreamException
    {
        ItemStreamException firstFailure = null;
        for (ItemWriter<? super T> delegate : delegates)
        {
            if (delegate instanceof ItemStream stream)
            {
                try
                {
                    stream.close();
                }
                catch (ItemStreamException e)
                {
                    LOGGER.warn("Failed to close delegate writer {}", delegate.getClass().getSimpleName(), e);
                    if (firstFailure == null)
                    {
                        firstFailure = e;
                    }
                }
            }
        }
        if (firstFailure != null)
        {
            throw firstFailure;
        }
    }

    /** Wraps a checked delegate failure so it can travel through the {@link Runnable}/{@link Future} boundary. */
    private static final class DelegateWriteException extends RuntimeException
    {
        DelegateWriteException(Exception cause)
        {
            super(cause);
        }
    }
}
