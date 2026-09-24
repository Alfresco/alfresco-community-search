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
package org.alfresco.reindexing.config.utils;

import java.util.Map;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.listener.ChunkListener;
import org.springframework.batch.core.listener.SkipListener;
import org.springframework.batch.core.step.builder.ChunkOrientedStepBuilder;
import org.springframework.batch.core.step.skip.LimitCheckingItemSkipPolicy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.util.backoff.BackOff;

import org.alfresco.metrics.ReindexingMetrics;
import org.alfresco.reindexing.config.ReindexingCoreProperties;
import org.alfresco.reindexing.config.UnresolvedNamespaceAction;
import org.alfresco.reindexing.processors.UnresolvedNamespaceException;
import org.alfresco.reindexing.retry.LoggingRetryListener;

@Slf4j
@UtilityClass
public class StepConfigurer
{
    /**
     * Retry-only variant. Used by steps where skipping items would hide a real problem (e.g. schema validation).
     */
    public static void withRetryingAndMultithreadSettings(ChunkOrientedStepBuilder<?, ?> stepBuilder, String stepName, ReindexingCoreProperties reindexingProperties, BackOff backOff, ThreadPoolTaskExecutor reindexTaskExecutor, ReindexingMetrics reindexingMetrics)
    {
        configureRetry(stepBuilder, stepName, reindexingProperties, backOff, reindexingMetrics);
        configureMultithreading(stepBuilder, stepName, reindexingProperties, reindexTaskExecutor);
    }

    /**
     * Retry + skip variant. Used by indexing steps. Skip lets Spring Batch discard individual poison items instead of failing the whole step; the per-step skip cap comes from {@code alfresco.reindex.skipLimit}. If a {@link SkipListener} bean is present it is wired in — this is the hook batch-indexing uses to write dead-letter entries without the reindexing module depending on Elasticsearch.
     */
    public static void withRetrySkipAndMultithreadSettings(ChunkOrientedStepBuilder<?, ?> stepBuilder, String stepName, ReindexingCoreProperties reindexingProperties, BackOff backOff, ThreadPoolTaskExecutor reindexTaskExecutor, ReindexingMetrics reindexingMetrics, ObjectProvider<SkipListener<?, ?>> skipListenerProvider)
    {
        configureRetry(stepBuilder, stepName, reindexingProperties, backOff, reindexingMetrics);
        configureSkip(stepBuilder, stepName, reindexingProperties, skipListenerProvider);
        configureMultithreading(stepBuilder, stepName, reindexingProperties, reindexTaskExecutor);
    }

    private static void configureRetry(ChunkOrientedStepBuilder<?, ?> stepBuilder, String stepName, ReindexingCoreProperties reindexingProperties, BackOff backOff, ReindexingMetrics reindexingMetrics)
    {
        if (reindexingProperties.isRetryingEnabled())
        {
            log.info("[{}] Retry is enabled with: {}", stepName, backOff);
            stepBuilder.faultTolerant()
                    .retryListener(new LoggingRetryListener(stepName, reindexingMetrics))
                    .retryPolicy(RetryPolicy.builder()
                            .backOff(backOff)
                            // Deterministic: the prefix map will not change mid-cycle, so retrying only sleeps.
                            .excludes(UnresolvedNamespaceException.class)
                            .build())
                    .retry(Exception.class);
        }
        else
        {
            log.info("[{}] Retry is disabled for this step", stepName);
        }
    }

    /**
     * Attaches any {@link ChunkListener} beans discovered downstream. Batch-indexing uses this hook for its liveness heartbeat; the reindexing module itself contributes no chunk listeners so the provider is empty in the standalone reindex CLI.
     */
    @SuppressWarnings("rawtypes")
    public static void attachChunkListeners(ChunkOrientedStepBuilder<?, ?> stepBuilder, String stepName, ObjectProvider<ChunkListener<?, ?>> chunkListenerProvider)
    {
        chunkListenerProvider.orderedStream().forEach(listener -> {
            log.info("[{}] Chunk listener attached: {}", stepName, listener.getClass().getName());
            stepBuilder.listener((ChunkListener) listener);
        });
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void configureSkip(ChunkOrientedStepBuilder<?, ?> stepBuilder, String stepName, ReindexingCoreProperties reindexingProperties, ObjectProvider<SkipListener<?, ?>> skipListenerProvider)
    {
        int skipLimit = reindexingProperties.getSkipLimit();
        if (skipLimit <= 0)
        {
            log.info("[{}] Skip is disabled for this step (skipLimit={})", stepName, skipLimit);
            return;
        }
        log.info("[{}] Skip is enabled with skipLimit={}", stepName, skipLimit);
        if (reindexingProperties.getOnUnresolvedNamespace() == UnresolvedNamespaceAction.STOP)
        {
            // Non-skippable, so the step fails on the first such node and the cycle can terminate.
            log.info("[{}] An unresolved namespace will not be skipped (onUnresolvedNamespace=stop)", stepName);
            stepBuilder.faultTolerant()
                    .skipPolicy(new LimitCheckingItemSkipPolicy(skipLimit,
                            Map.of(UnresolvedNamespaceException.class, false, Exception.class, true)));
        }
        else
        {
            stepBuilder.faultTolerant()
                    .skip(Exception.class)
                    .skipLimit(skipLimit);
        }
        // Raw cast: SkipListener is generic in I/O of the step but batch-indexing's
        // DlqSkipListener only reads the Throwable + item, so it can be typed
        // as SkipListener<?, ?> and used across differently-typed steps.
        skipListenerProvider.orderedStream().forEach(listener -> {
            log.info("[{}] Skip listener attached: {}", stepName, listener.getClass().getName());
            stepBuilder.skipListener((SkipListener) listener);
        });
    }

    private static void configureMultithreading(ChunkOrientedStepBuilder<?, ?> stepBuilder, String stepName, ReindexingCoreProperties reindexingProperties, ThreadPoolTaskExecutor reindexTaskExecutor)
    {
        if (reindexingProperties.isMultithreadedStepEnabled())
        {
            log.info("[{}] Multithreading is enabled. Task executor configured with concurrentProcessors: {}", stepName, reindexingProperties.getConcurrentProcessors());
            stepBuilder.taskExecutor(reindexTaskExecutor);
        }
        else
        {
            log.info("[{}] Multithreading is disabled for this step", stepName);
        }
    }
}
