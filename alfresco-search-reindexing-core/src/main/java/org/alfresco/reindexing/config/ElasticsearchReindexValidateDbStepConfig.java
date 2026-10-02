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

import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.ChunkOrientedStepBuilder;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.batch.infrastructure.item.ItemReader;
import org.springframework.batch.infrastructure.item.ItemStreamReader;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.util.backoff.ExponentialBackOff;

import org.alfresco.database.connector.AlfrescoMetadataRepository;
import org.alfresco.database.connector.model.AlfrescoNode;
import org.alfresco.indexing.shared.PropertiesDeterminingStrategy;
import org.alfresco.metrics.ReindexingMetrics;
import org.alfresco.reindexing.beans.RepositoryDescriptor;
import org.alfresco.reindexing.config.utils.StepConfigurer;
import org.alfresco.reindexing.processors.AlfrescoNodeToDescriptorProcessor;
import org.alfresco.reindexing.readers.AlfrescoPagingItemReaderBuilder;
import org.alfresco.reindexing.writers.RepositoryDescriptorItemWriter;

/**
 * Base class including a common validation step (validateDbSchemaStep) for re-indexing jobs.
 * <p>
 * Shared retry configuration lives in {@link RetryBackOffConfig} to avoid a data-source initialization cycle.
 */
@SuppressWarnings("PMD.AbstractClassWithoutAbstractMethod")
public abstract class ElasticsearchReindexValidateDbStepConfig
{
    protected final ReindexingCoreProperties reindexingProperties;

    public ElasticsearchReindexValidateDbStepConfig(ReindexingCoreProperties reindexingProperties)
    {
        this.reindexingProperties = reindexingProperties;
    }

    @Bean("validateDbSchemaStep")
    public Step validateDbSchemaStep(
            JobRepository jobRepository,
            ItemReader<AlfrescoNode> databaseSchemaReader,
            RepositoryDescriptorItemWriter itemWriter,
            ItemProcessor<AlfrescoNode, RepositoryDescriptor> validationProcessor,
            ExponentialBackOff exponentialBackOff,
            ThreadPoolTaskExecutor reindexTaskExecutor,
            ReindexingMetrics reindexingMetrics)
    {
        String stepName = "validateDbSchemaStep";
        var stepBuilder = new ChunkOrientedStepBuilder<AlfrescoNode, RepositoryDescriptor>(stepName, jobRepository, reindexingProperties.getBatchSize())
                .reader(databaseSchemaReader)
                .processor(validationProcessor)
                .writer(itemWriter);
        StepConfigurer.withRetryingAndMultithreadSettings(
                stepBuilder,
                stepName,
                reindexingProperties,
                exponentialBackOff,
                reindexTaskExecutor,
                reindexingMetrics);
        return stepBuilder.build();
    }

    @Bean
    public ItemProcessor<AlfrescoNode, RepositoryDescriptor> validationProcessor(PrefixNamespaceConfig prefixNamespaceConfig, PropertiesDeterminingStrategy propertiesDeterminingStrategy)
    {
        return new AlfrescoNodeToDescriptorProcessor(prefixNamespaceConfig, propertiesDeterminingStrategy);
    }

    @Bean(name = "databaseSchemaReader")
    public ItemStreamReader<AlfrescoNode> databaseSchemaReader(AlfrescoMetadataRepository metadataRepository)
    {
        return AlfrescoPagingItemReaderBuilder.forSchema()
                .name("databaseSchemaReader")
                .metadataRepository(metadataRepository)
                .pageSize(reindexingProperties.getPageSize())
                .build();
    }
}
