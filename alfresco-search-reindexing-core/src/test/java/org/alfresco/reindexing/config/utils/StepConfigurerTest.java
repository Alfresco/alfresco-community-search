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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Answers.RETURNS_SELF;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.batch.core.listener.SkipListener;
import org.springframework.batch.core.step.builder.ChunkOrientedStepBuilder;
import org.springframework.batch.core.step.skip.LimitCheckingItemSkipPolicy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.util.backoff.ExponentialBackOff;

import org.alfresco.metrics.ReindexingMetrics;
import org.alfresco.reindexing.config.ReindexingCoreProperties;
import org.alfresco.reindexing.processors.UnresolvedNamespaceException;
import org.alfresco.reindexing.writers.TerminalWriteFailureException;

class StepConfigurerTest
{
    private static final String STEP_NAME = "aStep";

    @Test
    void retryOnlyVariantDoesNotWireSkip()
    {
        var builder = mock(ChunkOrientedStepBuilder.class, RETURNS_SELF);
        var props = props(true, 100);

        StepConfigurer.withRetryingAndMultithreadSettings(builder, STEP_NAME, props, backOff(), executor(), metrics());

        verify(builder).retry(Exception.class);
        verify(builder, never()).skipPolicy(any());
        verify(builder, never()).skipListener(any());
    }

    @Test
    void retrySkipVariantWiresSkipWhenLimitPositive()
    {
        var builder = mock(ChunkOrientedStepBuilder.class, RETURNS_SELF);
        var props = props(true, 100);
        ObjectProvider<SkipListener<?, ?>> provider = emptyProvider();

        StepConfigurer.withRetrySkipAndMultithreadSettings(builder, STEP_NAME, props, backOff(), executor(), metrics(), provider);

        verify(builder).retry(Exception.class);
        verify(builder).skipPolicy(any(LimitCheckingItemSkipPolicy.class));
        verify(builder, never()).skipListener(any());
    }

    @Test
    void retrySkipVariantAttachesListenerFromProvider()
    {
        var builder = mock(ChunkOrientedStepBuilder.class, RETURNS_SELF);
        var props = props(true, 100);
        var listener = mock(SkipListener.class);
        ObjectProvider<SkipListener<?, ?>> provider = providerOf(listener);

        StepConfigurer.withRetrySkipAndMultithreadSettings(builder, STEP_NAME, props, backOff(), executor(), metrics(), provider);

        verify(builder).skipListener(listener);
    }

    @Test
    void retrySkipVariantSkipsWiringWhenSkipLimitZero()
    {
        var builder = mock(ChunkOrientedStepBuilder.class, RETURNS_SELF);
        var props = props(true, 0);
        var listener = mock(SkipListener.class);
        ObjectProvider<SkipListener<?, ?>> provider = providerOf(listener);

        StepConfigurer.withRetrySkipAndMultithreadSettings(builder, STEP_NAME, props, backOff(), executor(), metrics(), provider);

        verify(builder).retry(Exception.class);
        verify(builder, never()).skipPolicy(any());
        verify(builder, never()).skipListener(any());
    }

    @Test
    void retryStaysOptional()
    {
        var builder = mock(ChunkOrientedStepBuilder.class, RETURNS_SELF);
        var props = props(false, 100);
        ObjectProvider<SkipListener<?, ?>> provider = emptyProvider();

        StepConfigurer.withRetrySkipAndMultithreadSettings(builder, STEP_NAME, props, backOff(), executor(), metrics(), provider);

        verify(builder, never()).retry(any(Class.class));
        verify(builder).skipPolicy(any(LimitCheckingItemSkipPolicy.class));
    }

    @Test
    @DisplayName("a terminal write failure is not retried: the writer already exhausted its own retries")
    void terminalWriteFailureIsNotRetried()
    {
        var builder = mock(ChunkOrientedStepBuilder.class, RETURNS_SELF);

        StepConfigurer.withRetryingAndMultithreadSettings(builder, STEP_NAME, props(true, 100), backOff(), executor(), metrics());

        ArgumentCaptor<RetryPolicy> policy = ArgumentCaptor.forClass(RetryPolicy.class);
        verify(builder).retryPolicy(policy.capture());

        // A transient fault is worth another chunk attempt.
        assertThat(policy.getValue().shouldRetry(new RuntimeException("connection reset"))).isTrue();
        // These two are deterministic: retrying only repeats the writes that already succeeded.
        assertThat(policy.getValue().shouldRetry(new UnresolvedNamespaceException(1L, "http://acme", "foo", true))).isFalse();
        assertThat(policy.getValue().shouldRetry(new TerminalWriteFailureException("metadata", 3))).isFalse();
    }

    private static ReindexingMetrics metrics()
    {
        return mock(ReindexingMetrics.class);
    }

    private static ReindexingCoreProperties props(boolean retryingEnabled, int skipLimit)
    {
        var props = new ReindexingCoreProperties();
        props.setRetryingEnabled(retryingEnabled);
        props.setSkipLimit(skipLimit);
        return props;
    }

    private static ExponentialBackOff backOff()
    {
        return new ExponentialBackOff();
    }

    private static ThreadPoolTaskExecutor executor()
    {
        return new ThreadPoolTaskExecutor();
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<SkipListener<?, ?>> emptyProvider()
    {
        ObjectProvider<SkipListener<?, ?>> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenReturn(Stream.empty());
        return provider;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<SkipListener<?, ?>> providerOf(SkipListener<?, ?> listener)
    {
        ObjectProvider<SkipListener<?, ?>> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenReturn(Stream.of(listener));
        return provider;
    }
}
