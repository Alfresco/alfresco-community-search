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

import java.util.List;

import io.micrometer.core.instrument.MeterRegistry;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import org.alfresco.database.connector.AlfrescoMetadataRepository;
import org.alfresco.elasticsearch.batchindexing.ContinuousReindexingService;
import org.alfresco.elasticsearch.batchindexing.ElasticsearchWatermarkStore;
import org.alfresco.elasticsearch.batchindexing.WatermarkAccessException;
import org.alfresco.elasticsearch.batchindexing.WatermarkBootstrap;
import org.alfresco.elasticsearch.batchindexing.listeners.CacheInvalidationListener;
import org.alfresco.elasticsearch.batchindexing.listeners.ProgressHeartbeatListener;
import org.alfresco.elasticsearch.batchindexing.metrics.ContinuousReindexingMetrics;
import org.alfresco.elasticsearch.batchindexing.observability.ContinuousReindexingMonitor;
import org.alfresco.elasticsearch.batchindexing.observability.ReindexCycleListener;
import org.alfresco.indexing.shared.ElasticsearchProperties;

/**
 * Configuration for continuous reindexing mode — always active in the batch-indexing module.
 */
@Configuration
public class ContinuousReindexingConfig
{

    @Bean
    public CacheInvalidationListener cacheInvalidationListener(AlfrescoMetadataRepository metadataRepository)
    {
        return new CacheInvalidationListener(metadataRepository);
    }

    /** Gated like {@link #continuousReindexingService}: its only consumer is that bean, and its bootstrap (incl. smart-seed) must not run as a side effect of a one-shot job's startup. */
    @Bean
    @ConditionalOnExpression("'${alfresco.reindex.jobName:}'.isEmpty()")
    public ElasticsearchWatermarkStore elasticsearchWatermarkStore(
            OpenSearchClient openSearchClient,
            ContinuousReindexingProperties continuousProperties,
            ElasticsearchProperties elasticsearchProperties,
            AlfrescoMetadataRepository metadataRepository) throws WatermarkAccessException
    {
        ElasticsearchWatermarkStore store = new ElasticsearchWatermarkStore(
                openSearchClient,
                continuousProperties.getWatermarkIndexName(),
                continuousProperties.getWatermarkRetryMaxAttempts(),
                continuousProperties.getWatermarkRetryDelay().toMillis());
        new WatermarkBootstrap(store, openSearchClient, continuousProperties, elasticsearchProperties, metadataRepository).bootstrap();
        return store;
    }

    @Bean
    public ContinuousReindexingMonitor continuousReindexingMonitor(
            ContinuousReindexingProperties continuousProperties,
            List<ReindexCycleListener> cycleListeners)
    {
        return new ContinuousReindexingMonitor(continuousProperties, cycleListeners);
    }

    /** Absent (not just non-auto-starting) unless running continuously — i.e. when no one-shot alfresco.reindex.jobName is set — so a one-shot run never contends with the scheduler. */
    @Bean(destroyMethod = "destroy")
    @ConditionalOnExpression("'${alfresco.reindex.jobName:}'.isEmpty()")
    public ContinuousReindexingService continuousReindexingService(
            JobLauncher jobLauncher,
            Job reindexByDateJob,
            ElasticsearchWatermarkStore watermarkStore,
            ContinuousReindexingProperties continuousProperties,
            ContinuousReindexingMonitor monitor)
    {
        // Terminates on a watermark conflict (another instance is running, risking duplicate
        // reindexing) and on onUnresolvedNamespace=stop.
        return new ContinuousReindexingService(
                jobLauncher, reindexByDateJob, watermarkStore, continuousProperties,
                ContinuousReindexingService.TERMINATE_JVM,
                monitor);
    }

    @Bean
    public ProgressHeartbeatListener progressHeartbeatListener(ContinuousReindexingMonitor monitor)
    {
        return new ProgressHeartbeatListener(monitor);
    }

    @Bean
    public ContinuousReindexingMetrics continuousReindexingMetrics(
            ContinuousReindexingMonitor monitor,
            MeterRegistry meterRegistry)
    {
        return new ContinuousReindexingMetrics(monitor, meterRegistry);
    }
}
