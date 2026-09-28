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

import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.parameters.RunIdIncrementer;
import org.springframework.batch.core.listener.SkipListener;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.ChunkOrientedStepBuilder;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.batch.infrastructure.item.ItemReader;
import org.springframework.batch.infrastructure.item.ItemStreamReader;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.beans.factory.ObjectProvider;
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
import org.alfresco.reindexing.listeners.StepLifecycleListener;
import org.alfresco.reindexing.listeners.WriteMetricsFlushListener;
import org.alfresco.reindexing.model.ReindexingEvent;
import org.alfresco.reindexing.readers.AlfrescoPagingItemReaderBuilder;
import org.alfresco.reindexing.readers.PageReadFailureListener;

@Slf4j
@EnableConfigurationProperties({ElasticsearchProperties.class})
@SuppressWarnings("PMD.CouplingBetweenObjects")
public class ElasticsearchReindexByIdJobConfig extends ElasticsearchReindexValidateDbStepConfig
{
    public static final String JOB_NAME = "reindexByIds";

    private final ReindexNodeRole nodeRole;

    public ElasticsearchReindexByIdJobConfig(ReindexingCoreProperties reindexingProperties, ReindexNodeRole nodeRole)
    {
        super(reindexingProperties);
        this.nodeRole = nodeRole;
    }

    @Bean
    public Job reindexByIdsJob(
            JobRepository jobRepository,
            JobExitStatusAggregatorListener jobExitStatusAggregatorListener,
            JobLifecycleListener jobLifecycleListener,
            Step validateDbSchemaStep,
            Step reindexByIdsStep)
    {
        return new JobBuilder(JOB_NAME, jobRepository)
                .incrementer(new RunIdIncrementer())
                .listener(jobExitStatusAggregatorListener)
                .listener(jobLifecycleListener)
                .flow(validateDbSchemaStep)
                .next(reindexByIdsStep)
                .end()
                .build();
    }

    @Bean
    public Step reindexByIdsStep(
            JobRepository jobRepository,
            ItemReader<AlfrescoNode> reindexByIdItemReader,
            ItemProcessor<AlfrescoNode, ReindexingEvent> processors,
            ItemWriter<ReindexingEvent> writer,
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
        String stepName = "reindexByIdsStep";
        var stepBuilder = new ChunkOrientedStepBuilder<AlfrescoNode, ReindexingEvent>(stepName, jobRepository, reindexingProperties.getBatchSize())
                .listener(stepLifecycleListener)
                .listener(reindexMetricsStepListener)
                .listener(readCountingListener)
                .listener(filterCountingListener)
                .listener(writeMetricsFlushListener)
                .reader(reindexByIdItemReader)
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

    @Bean(name = "reindexByIdItemReader")
    public ItemStreamReader<AlfrescoNode> reindexByIdItemReader(
            AlfrescoMetadataRepository metadataRepository,
            ObjectProvider<PageReadFailureListener> pageReadFailureListenerProvider)
    {
        validateIdBounds();

        log.info("Reindexing by ID bounds: {} (inclusive) - {} (exclusive)",
                reindexingProperties.getFromId(), reindexingProperties.getToId());

        return AlfrescoPagingItemReaderBuilder.forReadingById(reindexingProperties.getFromId(), reindexingProperties.getToId())
                .name("reindexingByIdReader")
                .metadataRepository(metadataRepository)
                .pageSize(reindexingProperties.getPageSize())
                .pageReadFailureListener(pageReadFailureListenerProvider.getIfAvailable())
                .build();
    }

    /**
     * Fail fast on a range that can never read anything. Built on every start, so the check applies only to an actual {@code reindexByIds} run — other jobs leave {@code fromId}/{@code toId} at their {@code 0} defaults, and nodes whose range is managed externally get it at runtime instead.
     */
    private void validateIdBounds()
    {
        if (!JOB_NAME.equals(reindexingProperties.getJobName()) || nodeRole.rangeManagedExternally())
        {
            return;
        }
        long fromId = reindexingProperties.getFromId();
        long toId = reindexingProperties.getToId();
        if (fromId < 0 || toId <= fromId)
        {
            throw new IllegalArgumentException(String.format(
                    "Job '%s' requires 0 <= alfresco.reindex.fromId < alfresco.reindex.toId (toId is exclusive) "
                            + "but was fromId=%d, toId=%d",
                    JOB_NAME, fromId, toId));
        }
    }
}
