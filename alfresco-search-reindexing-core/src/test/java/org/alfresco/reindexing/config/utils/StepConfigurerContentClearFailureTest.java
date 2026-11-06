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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.listener.SkipListener;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.repository.support.ResourcelessJobRepository;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.core.step.builder.ChunkOrientedStepBuilder;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.item.support.ListItemReader;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.util.backoff.FixedBackOff;

import org.alfresco.indexing.shared.exception.ContentClearException;
import org.alfresco.metrics.ReindexingMetrics;
import org.alfresco.reindexing.config.ReindexingCoreProperties;

/**
 * Runs a real step configured by {@link StepConfigurer} with the default {@code skipLimit=100}: a persistent failure to clear excluded content must fail the step after retries, not be skipped.
 */
class StepConfigurerContentClearFailureTest
{
    @Test
    @SuppressWarnings("unchecked")
    void persistentClearFailureIsRetriedThenFailsTheStepWithoutSkipping() throws Exception
    {
        AtomicInteger writeAttempts = new AtomicInteger();
        JobRepository jobRepository = new ResourcelessJobRepository();
        ChunkOrientedStepBuilder<String, String> stepBuilder = new StepBuilder("contentStep", jobRepository).<String, String> chunk(2)
                .reader(new ListItemReader<>(List.of("node-1", "node-2")))
                .writer(chunk -> {
                    writeAttempts.incrementAndGet();
                    throw new ContentClearException("bulk clear failed");
                });
        var properties = new ReindexingCoreProperties();
        properties.setRetryingEnabled(true);
        properties.setSkipLimit(100);
        ObjectProvider<SkipListener<?, ?>> noSkipListeners = mock(ObjectProvider.class);
        when(noSkipListeners.orderedStream()).thenReturn(Stream.empty());

        StepConfigurer.withRetryAndSkipSettings(stepBuilder, "contentStep", properties, new FixedBackOff(0L, 2),
                mock(ReindexingMetrics.class), noSkipListeners);
        JobParameters jobParameters = new JobParameters();
        JobExecution jobExecution = jobRepository.createJobExecution(
                jobRepository.createJobInstance("contentJob", jobParameters), jobParameters, new ExecutionContext());
        StepExecution stepExecution = jobRepository.createStepExecution("contentStep", jobExecution);
        stepBuilder.build().execute(stepExecution);

        assertThat(stepExecution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(stepExecution.getSkipCount()).isZero();
        assertThat(writeAttempts.get()).as("retried before failing").isGreaterThan(1);
    }
}
