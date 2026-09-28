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

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;

import org.alfresco.indexing.shared.ElasticsearchProperties;
import org.alfresco.metrics.ReindexingMetrics;
import org.alfresco.reindexing.opensearch.ElasticSearchUpdateOperations;
import org.alfresco.reindexing.writers.BulkItemFailureListener;
import org.alfresco.reindexing.writers.ChunkWriteMetricsAccumulator;
import org.alfresco.reindexing.writers.ElasticsearchPathWriter;
import org.alfresco.reindexing.writers.ElasticsearchRepoEventItemWriter;
import org.alfresco.reindexing.writers.PathBulkItemFailureListener;

/**
 * Search-engine writers for the reindexing pipeline.
 */
public class ReindexWriterConfig
{
    @Bean(destroyMethod = "")
    public ElasticsearchRepoEventItemWriter elasticsearchRepoEventItemWriter(
            ElasticSearchUpdateOperations elasticSearchUpdateOperations,
            ReindexingCoreProperties elasticsearchReindexProperties,
            ChunkWriteMetricsAccumulator chunkWriteMetricsAccumulator,
            ObjectProvider<BulkItemFailureListener> failureListener)
    {
        // ObjectProvider keeps the listener optional — the reindexing module ships without a bean,
        // batch-indexing registers one to feed its DLQ.
        return new ElasticsearchRepoEventItemWriter(elasticSearchUpdateOperations, elasticsearchReindexProperties,
                chunkWriteMetricsAccumulator, failureListener.getIfAvailable());
    }

    @Bean(destroyMethod = "")
    public ElasticsearchPathWriter elasticsearchPathWriter(ElasticsearchProperties elasticsearchProperties,
            ReindexingCoreProperties elasticsearchReindexProperties,
            PrefixNamespaceConfig prefixNamespaceConfig,
            ElasticSearchUpdateOperations elasticSearchUpdateOperations,
            ChunkWriteMetricsAccumulator chunkWriteMetricsAccumulator,
            ObjectProvider<PathBulkItemFailureListener> failureListener,
            ReindexingMetrics reindexingMetrics)
    {
        // ObjectProvider keeps the listener optional — the reindexing module ships without a bean,
        // batch-indexing registers one to feed its DLQ.
        return new ElasticsearchPathWriter(elasticsearchProperties, elasticsearchReindexProperties,
                prefixNamespaceConfig, elasticSearchUpdateOperations, chunkWriteMetricsAccumulator,
                failureListener.getIfAvailable(), reindexingMetrics);
    }
}
