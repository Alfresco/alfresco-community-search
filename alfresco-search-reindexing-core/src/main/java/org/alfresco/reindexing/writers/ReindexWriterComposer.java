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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.batch.infrastructure.item.support.CompositeItemWriter;
import org.springframework.core.task.AsyncTaskExecutor;

import org.alfresco.metrics.MetricsConstants;
import org.alfresco.reindexing.config.ReindexingCoreProperties;
import org.alfresco.reindexing.model.ReindexingEvent;

/**
 * Central factory that turns the {@link ReindexingCoreProperties#getEnabledWriterKeys() enabled writer keys} into the composed {@link ItemWriter} run by a reindex / batch-indexing step. Every module's {@code writer} bean delegates here, so the merged / concurrent / sequential selection is implemented once and the writer strategy can evolve in a single place.
 * <p>
 * The composition rules are:
 * <ul>
 * <li>Each enabled key is mapped to the module's writer via {@code writersByKey}. A key with no writer in the map is skipped, so a module that does not provide a given writer (e.g. the ATS-bypass harness has no content writer) need not supply it.</li>
 * <li>When {@code metricsAccumulator} is non-null each keyed writer is wrapped in a {@link MetricsTrackingItemWriter} tagged with its key; when null the writers are added bare (preserving the untracked behaviour of harness modules).</li>
 * <li>{@code leadingWriters} are prepended verbatim (not metrics-wrapped) so a module can run head delegates such as the cascade collector before the keyed writers.</li>
 * <li>The concurrent writer is selected only when a {@code concurrentExecutor} and {@code metricsAccumulator} are supplied, concurrent writers are enabled, there are no leading writers and merged mode is not active (merged is a single bulk, so concurrency does not apply). Otherwise a sequential {@link CompositeItemWriter} is returned.</li>
 * </ul>
 */
public final class ReindexWriterComposer
{
    private ReindexWriterComposer()
    {}

    /**
     * @param properties
     *            the reindex properties supplying the enabled writer keys and concurrency settings
     * @param writersByKey
     *            the module's writers keyed by {@link MetricsConstants} writer key ({@code MERGED}/{@code METADATA}/{@code PATH}/{@code CONTENT}); keys absent from the map are skipped
     * @param leadingWriters
     *            head delegates to run before the keyed writers (e.g. cascade collector); may be empty
     * @param metricsAccumulator
     *            accumulator used to wrap each keyed writer for per-writer timing, or {@code null} to add writers bare
     * @param concurrentExecutor
     *            executor backing the concurrent fan-out, or {@code null} when the module does not support concurrent writers
     * @return the composed writer for the step
     */
    public static ItemWriter<ReindexingEvent> compose(
            ReindexingCoreProperties properties,
            Map<String, ItemWriter<? super ReindexingEvent>> writersByKey,
            List<ItemWriter<? super ReindexingEvent>> leadingWriters,
            ChunkWriteMetricsAccumulator metricsAccumulator,
            AsyncTaskExecutor concurrentExecutor)
    {
        List<String> keys = properties.getEnabledWriterKeys();
        List<ItemWriter<? super ReindexingEvent>> keyedWriters = new ArrayList<>();
        for (String key : keys)
        {
            ItemWriter<? super ReindexingEvent> writer = writersByKey.get(key);
            if (writer == null)
            {
                continue;
            }
            keyedWriters.add(metricsAccumulator != null
                    ? new MetricsTrackingItemWriter<>(writer, metricsAccumulator, key)
                    : writer);
        }

        boolean useConcurrent = concurrentExecutor != null
                && metricsAccumulator != null
                && properties.isWritersConcurrentEnabled()
                && !keys.contains(MetricsConstants.MERGED);
        if (useConcurrent && leadingWriters.isEmpty())
        {
            return new ConcurrentCompositeItemWriter<>(keyedWriters, concurrentExecutor, metricsAccumulator,
                    properties.isPathUpsertEffective(), properties.getWriterJoinTimeoutSeconds());
        }

        List<ItemWriter<? super ReindexingEvent>> delegates = new ArrayList<>(leadingWriters);
        delegates.addAll(keyedWriters);
        CompositeItemWriter<ReindexingEvent> composite = new CompositeItemWriter<>();
        composite.setDelegates(delegates);
        return composite;
    }
}
