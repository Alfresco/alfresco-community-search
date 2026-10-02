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
package org.alfresco.reindexing.config;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.parameters.RunIdIncrementer;
import org.springframework.batch.core.listener.ItemWriteListener;
import org.springframework.batch.core.listener.SkipListener;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.ChunkOrientedStepBuilder;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.batch.infrastructure.item.ItemReader;
import org.springframework.batch.infrastructure.item.ItemStreamReader;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.util.backoff.ExponentialBackOff;

import org.alfresco.database.connector.AlfrescoMetadataRepository;
import org.alfresco.database.connector.model.AlfrescoNode;
import org.alfresco.indexing.shared.ElasticsearchProperties;
import org.alfresco.metrics.ReindexingMetrics;
import org.alfresco.reindexing.config.utils.StepConfigurer;
import org.alfresco.reindexing.listeners.FilterCountingListener;
import org.alfresco.reindexing.listeners.JobExitStatusAggregatorListener;
import org.alfresco.reindexing.listeners.JobLifecycleListener;
import org.alfresco.reindexing.listeners.ReadCountingListener;
import org.alfresco.reindexing.listeners.ReindexMetricsStepListener;
import org.alfresco.reindexing.listeners.ReindexSummaryStepListener;
import org.alfresco.reindexing.listeners.StepLifecycleListener;
import org.alfresco.reindexing.listeners.WriteMetricsFlushListener;
import org.alfresco.reindexing.model.ReindexingEvent;
import org.alfresco.reindexing.readers.AlfrescoPagingItemReaderBuilder;
import org.alfresco.reindexing.readers.PageReadFailureListener;

@Slf4j
@EnableConfigurationProperties({ElasticsearchProperties.class})
@SuppressWarnings("PMD.CouplingBetweenObjects")
public class ElasticsearchReindexByDateJobConfig extends ElasticsearchReindexValidateDbStepConfig
{
    public static final String REINDEX_BY_DATE_STEP_NAME = "reindexByDateStep";

    public ElasticsearchReindexByDateJobConfig(ReindexingCoreProperties reindexingProperties)
    {
        super(reindexingProperties);
    }

    @Bean
    public Job reindexByDateJob(
            JobExitStatusAggregatorListener jobExitStatusAggregatorListener,
            JobLifecycleListener jobLifecycleListener,
            JobRepository jobRepository,
            Step validateDbSchemaStep,
            Step reindexByDateStep)
    {
        return new JobBuilder("reindexByDate", jobRepository)
                .incrementer(new RunIdIncrementer())
                .listener(jobExitStatusAggregatorListener)
                .listener(jobLifecycleListener)
                .flow(validateDbSchemaStep)
                .next(reindexByDateStep)
                .end()
                .build();
    }

    @Bean
    public Step reindexByDateStep(
            JobRepository jobRepository,
            ItemReader<AlfrescoNode> reindexByDateItemReader,
            ItemWriter<ReindexingEvent> writer,
            ItemProcessor<AlfrescoNode, ReindexingEvent> processors,
            ThreadPoolTaskExecutor reindexTaskExecutor,
            ExponentialBackOff exponentialBackOff,
            StepLifecycleListener stepLifecycleListener,
            ReindexMetricsStepListener reindexMetricsStepListener,
            ReindexSummaryStepListener reindexSummaryStepListener,
            ReadCountingListener readCountingListener,
            FilterCountingListener filterCountingListener,
            WriteMetricsFlushListener writeMetricsFlushListener,
            ReindexingMetrics reindexingMetrics,
            ObjectProvider<SkipListener<?, ?>> skipListenerProvider,
            ObjectProvider<ItemWriteListener<?>> writeListenerProvider)
    {

        var stepBuilder = new ChunkOrientedStepBuilder<AlfrescoNode, ReindexingEvent>(REINDEX_BY_DATE_STEP_NAME, jobRepository, reindexingProperties.getBatchSize())
                .listener(stepLifecycleListener)
                .listener(reindexMetricsStepListener)
                .listener(reindexSummaryStepListener)
                .listener(readCountingListener)
                .listener(filterCountingListener)
                .listener(writeMetricsFlushListener)
                .reader(reindexByDateItemReader)
                .processor(processors)
                .writer(writer);
        StepConfigurer.attachWriteListeners(stepBuilder, REINDEX_BY_DATE_STEP_NAME, writeListenerProvider);
        StepConfigurer.withRetrySkipAndMultithreadSettings(
                stepBuilder,
                REINDEX_BY_DATE_STEP_NAME,
                reindexingProperties,
                exponentialBackOff,
                reindexTaskExecutor,
                reindexingMetrics,
                skipListenerProvider);
        return stepBuilder.build();
    }

    @Bean(name = "reindexByDateItemReader")
    @StepScope
    public ItemStreamReader<AlfrescoNode> reindexByDateItemReader(
            AlfrescoMetadataRepository metadataRepository,
            ObjectProvider<PageReadFailureListener> pageReadFailureListenerProvider,
            @Value("#{jobParameters['fromTime']}") Long fromTimeEpochMs,
            @Value("#{jobParameters['toTime']}") Long toTimeEpochMs)
    {
        final LocalDateTime from;
        final LocalDateTime to;
        if (fromTimeEpochMs != null && toTimeEpochMs != null)
        {
            // Continuous mode: ContinuousReindexingService passes a per-cycle window.
            from = LocalDateTime.ofInstant(Instant.ofEpochMilli(fromTimeEpochMs), ZoneOffset.UTC);
            to = LocalDateTime.ofInstant(Instant.ofEpochMilli(toTimeEpochMs), ZoneOffset.UTC);
        }
        else
        {
            // One-shot reindex CLI: bounds come from alfresco.reindex.fromTime / toTime.
            from = reindexingProperties.getFromTime();
            to = reindexingProperties.getToTime();
        }
        validateTimeBounds(from, to);

        log.info("Reindexing by date bounds: {} - {}", from, to);

        return AlfrescoPagingItemReaderBuilder
                .forTimestampRange(from, to)
                .name("reindexingByDateReader")
                .metadataRepository(metadataRepository)
                .pageSize(reindexingProperties.getPageSize())
                .pageReadFailureListener(pageReadFailureListenerProvider.getIfAvailable())
                .build();
    }

    /**
     * Fail fast on a window that can never read anything, whether the bounds came from job parameters or from {@code alfresco.reindex.fromTime}/{@code toTime}. Unset one-shot bounds would otherwise surface as a bare NPE, and a reversed window as a job that completes having read nothing.
     */
    static void validateTimeBounds(LocalDateTime from, LocalDateTime to)
    {
        if (from == null || to == null)
        {
            throw new IllegalArgumentException(String.format(
                    "Job 'reindexByDate' requires both alfresco.reindex.fromTime and alfresco.reindex.toTime "
                            + "(format yyyyMMddHHmm) but was fromTime=%s, toTime=%s",
                    from, to));
        }
        if (!from.isBefore(to))
        {
            throw new IllegalArgumentException(String.format(
                    "Job 'reindexByDate' requires alfresco.reindex.fromTime to be before alfresco.reindex.toTime "
                            + "but was fromTime=%s, toTime=%s",
                    from, to));
        }
    }
}
