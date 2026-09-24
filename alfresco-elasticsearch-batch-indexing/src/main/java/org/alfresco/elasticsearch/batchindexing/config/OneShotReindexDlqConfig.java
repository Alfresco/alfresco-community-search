/*-
 * #%L
 * Alfresco Elasticsearch Connector :: Batch Indexing Module
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
package org.alfresco.elasticsearch.batchindexing.config;

import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.parameters.RunIdIncrementer;
import org.springframework.batch.core.listener.SkipListener;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.ChunkOrientedStepBuilder;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.batch.infrastructure.item.ItemReader;
import org.springframework.batch.infrastructure.item.ItemStreamReader;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.util.backoff.ExponentialBackOff;

import org.alfresco.database.connector.AlfrescoMetadataRepository;
import org.alfresco.database.connector.model.AlfrescoNode;
import org.alfresco.elasticsearch.batchindexing.DlqDrainService;
import org.alfresco.elasticsearch.batchindexing.DlqReader;
import org.alfresco.elasticsearch.batchindexing.DlqWriter;
import org.alfresco.elasticsearch.batchindexing.readers.AlfrescoExplicitIdSetItemReader;
import org.alfresco.metrics.ReindexingMetrics;
import org.alfresco.reindexing.config.ReindexingCoreProperties;
import org.alfresco.reindexing.config.utils.StepConfigurer;
import org.alfresco.reindexing.listeners.FilterCountingListener;
import org.alfresco.reindexing.listeners.JobExitStatusAggregatorListener;
import org.alfresco.reindexing.listeners.JobLifecycleListener;
import org.alfresco.reindexing.listeners.ReadCountingListener;
import org.alfresco.reindexing.listeners.ReindexMetricsStepListener;
import org.alfresco.reindexing.listeners.StepLifecycleListener;
import org.alfresco.reindexing.listeners.WriteMetricsFlushListener;
import org.alfresco.reindexing.model.ReindexingEvent;
import org.alfresco.reindexing.readers.PageReadFailureListener;

/**
 * Bean wiring for the one-shot {@code reindexDlq} job — the DLQ drain (RETRY analogue). Three linear steps around {@link DlqDrainService}; Spring Batch's default step sequencing already skips later steps when an earlier one fails, so cleanup only runs after a successful drain:
 * <ol>
 * <li>{@code reindexDlqScanStep} — {@link DlqDrainService#scan()}: select drainable node-level entries and snapshot their {@code failureCount}.</li>
 * <li>{@code reindexDlqStep} — reindex exactly the scanned {@code dbId}s through the same processor/writer pipeline as the other one-shot jobs.</li>
 * <li>{@code reindexDlqCleanupStep} — {@link DlqDrainService#cleanup()}: delete each drained entry whose {@code failureCount} did not increase.</li>
 * </ol>
 */
@Configuration
@SuppressWarnings({"PMD.CouplingBetweenObjects", "PMD.ExcessiveParameterList"})
public class OneShotReindexDlqConfig
{
    private final ReindexingCoreProperties reindexingProperties;

    public OneShotReindexDlqConfig(ReindexingCoreProperties reindexingProperties)
    {
        this.reindexingProperties = reindexingProperties;
    }

    @Bean
    public DlqDrainService dlqDrainService(DlqReader dlqReader, DlqWriter dlqWriter, DlqRecoveryProperties dlqRecoveryProperties)
    {
        return new DlqDrainService(dlqReader, dlqWriter, dlqRecoveryProperties);
    }

    @Bean
    public Job reindexDlqJob(
            JobRepository jobRepository,
            JobExitStatusAggregatorListener jobExitStatusAggregatorListener,
            JobLifecycleListener jobLifecycleListener,
            Step reindexDlqScanStep,
            Step reindexDlqStep,
            Step reindexDlqCleanupStep)
    {
        return new JobBuilder("reindexDlq", jobRepository)
                .incrementer(new RunIdIncrementer())
                .listener(jobExitStatusAggregatorListener)
                .listener(jobLifecycleListener)
                .flow(reindexDlqScanStep)
                .next(reindexDlqStep)
                .next(reindexDlqCleanupStep)
                .end()
                .build();
    }

    @Bean
    public Step reindexDlqScanStep(
            JobRepository jobRepository,
            @Qualifier("batchTransactionManager") PlatformTransactionManager transactionManager,
            DlqDrainService dlqDrainService)
    {
        Tasklet tasklet = (contribution, chunkContext) -> {
            dlqDrainService.scan();
            return RepeatStatus.FINISHED;
        };
        return new StepBuilder("reindexDlqScanStep", jobRepository).tasklet(tasklet, transactionManager).build();
    }

    @Bean
    public Step reindexDlqStep(
            JobRepository jobRepository,
            ItemReader<AlfrescoNode> reindexDlqItemReader,
            ItemProcessor<AlfrescoNode, ReindexingEvent> processors,
            @Qualifier("writer") ItemWriter<ReindexingEvent> writer,
            ThreadPoolTaskExecutor reindexTaskExecutor,
            ExponentialBackOff exponentialBackOff,
            StepLifecycleListener stepLifecycleListener,
            ReindexMetricsStepListener reindexMetricsStepListener,
            ReadCountingListener readCountingListener,
            FilterCountingListener filterCountingListener,
            WriteMetricsFlushListener writeMetricsFlushListener,
            ReindexingMetrics reindexingMetrics,
            ObjectProvider<SkipListener<?, ?>> skipListenerProvider)
    {
        String stepName = "reindexDlqStep";
        var stepBuilder = new ChunkOrientedStepBuilder<AlfrescoNode, ReindexingEvent>(stepName, jobRepository, reindexingProperties.getBatchSize())
                .listener(stepLifecycleListener)
                .listener(reindexMetricsStepListener)
                .listener(readCountingListener)
                .listener(filterCountingListener)
                .listener(writeMetricsFlushListener)
                .reader(reindexDlqItemReader)
                .processor(processors)
                .writer(writer);
        StepConfigurer.withRetrySkipAndMultithreadSettings(
                stepBuilder,
                stepName,
                reindexingProperties,
                exponentialBackOff,
                reindexTaskExecutor,
                reindexingMetrics,
                skipListenerProvider);
        return stepBuilder.build();
    }

    @Bean(name = "reindexDlqItemReader")
    @StepScope
    public ItemStreamReader<AlfrescoNode> reindexDlqItemReader(
            AlfrescoMetadataRepository metadataRepository,
            DlqDrainService dlqDrainService,
            ObjectProvider<PageReadFailureListener> pageReadFailureListenerProvider)
    {
        AlfrescoExplicitIdSetItemReader reader = new AlfrescoExplicitIdSetItemReader(
                metadataRepository, dlqDrainService.getTargetDbIds(), pageReadFailureListenerProvider.getIfAvailable());
        reader.setPageSize(reindexingProperties.getPageSize());
        reader.setName("reindexDlqItemReader");
        return reader;
    }

    @Bean
    public Step reindexDlqCleanupStep(
            JobRepository jobRepository,
            @Qualifier("batchTransactionManager") PlatformTransactionManager transactionManager,
            DlqDrainService dlqDrainService)
    {
        Tasklet tasklet = (contribution, chunkContext) -> {
            dlqDrainService.cleanup();
            return RepeatStatus.FINISHED;
        };
        return new StepBuilder("reindexDlqCleanupStep", jobRepository).tasklet(tasklet, transactionManager).build();
    }
}
