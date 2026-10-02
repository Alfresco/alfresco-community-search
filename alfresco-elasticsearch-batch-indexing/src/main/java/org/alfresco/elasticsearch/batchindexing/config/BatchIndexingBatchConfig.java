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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import org.alfresco.elasticsearch.batchindexing.cascade.CascadeCollectorWriter;
import org.alfresco.elasticsearch.batchindexing.domain.transform.ContentTransformClient;
import org.alfresco.elasticsearch.batchindexing.writers.ContentTransformDirectWriter;
import org.alfresco.elasticsearch.batchindexing.writers.ContentTransformFailureListener;
import org.alfresco.indexing.shared.opensearch.ElasticsearchContentModifyPort;
import org.alfresco.metrics.MetricsBeansConfig;
import org.alfresco.metrics.MetricsConstants;
import org.alfresco.reindexing.config.ReindexWriterConfig;
import org.alfresco.reindexing.config.ReindexingCoreProperties;
import org.alfresco.reindexing.config.SharedBatchProcessingConfig;
import org.alfresco.reindexing.domain.transform.ContentTransformSupportService;
import org.alfresco.reindexing.model.ReindexingEvent;
import org.alfresco.reindexing.writers.ChunkWriteMetricsAccumulator;
import org.alfresco.reindexing.writers.ElasticsearchMergedReindexWriter;
import org.alfresco.reindexing.writers.ElasticsearchPathWriter;
import org.alfresco.reindexing.writers.ElasticsearchRepoEventItemWriter;
import org.alfresco.reindexing.writers.ReindexWriterComposer;

/**
 * Bean wiring for the batch-indexing processing pipeline. Imports {@link SharedBatchProcessingConfig} for processors, listeners, executor and the metadata/path writers; this class only contributes the direct-HTTP content writer and the composite writer that selects them.
 * <p>
 * No JMS dependency: content transformation always uses the direct HTTP path against ACS.
 * <p>
 * {@link MetricsBeansConfig} is imported explicitly (mirroring {@code ElasticsearchReindexBatchConfig}) because it is a plain {@code @Configuration} in {@code alfresco-search-index-model} and is not registered via that module's {@code AutoConfiguration.imports}.
 */
@Configuration
@Import({SharedBatchProcessingConfig.class, ReindexWriterConfig.class, MetricsBeansConfig.class})
public class BatchIndexingBatchConfig
{
    @Bean
    public ContentTransformDirectWriter contentTransformDirectWriter(
            ContentTransformSupportService contentTransformSupportService,
            ContentTransformClient contentTransformClient,
            ElasticsearchContentModifyPort contentModifyPort,
            ChunkWriteMetricsAccumulator chunkWriteMetricsAccumulator,
            @Value("${alfresco.storeRef:workspace://SpacesStore/}") String storeRef,
            @Value("${alfresco.content.transform.writeConcurrency:1}") int writeConcurrency,
            ObjectProvider<ContentTransformFailureListener> failureListener)
    {
        // ObjectProvider keeps the listener optional — batch-indexing registers one to feed its DLQ,
        // but the writer stays functional if the DLQ config is disabled.
        return new ContentTransformDirectWriter(contentTransformSupportService, contentTransformClient,
                contentModifyPort, storeRef, writeConcurrency, chunkWriteMetricsAccumulator,
                failureListener.getIfAvailable());
    }

    /* destroyMethod="" prevents Spring from auto-detecting close() on the CompositeItemWriter and re-invoking it at context shutdown; Spring Batch already closes it (and cascades to the ItemStream delegates below) at end-of-step. Mirrors the reindexing module's writer bean. */
    @Bean(destroyMethod = "")
    public ItemWriter<ReindexingEvent> writer(
            ReindexingCoreProperties elasticsearchReindexProperties,
            ElasticsearchRepoEventItemWriter elasticsearchRepoEventItemWriter,
            ContentTransformDirectWriter contentTransformDirectWriter,
            ElasticsearchPathWriter elasticsearchPathWriter,
            ElasticsearchMergedReindexWriter elasticsearchMergedReindexWriter,
            CascadeProperties cascadeProperties,
            CascadeCollectorWriter cascadeCollectorWriter,
            ChunkWriteMetricsAccumulator chunkWriteMetricsAccumulator)
    {
        // Cascade collector runs FIRST (as a leading writer): it inspects each event for rename /
        // category-delete signals before downstream writers overwrite Elasticsearch. Phase 1 is log-only,
        // but the head position is required for Phase 2 (mget of pre-overwrite ES state).
        List<ItemWriter<? super ReindexingEvent>> leadingWriters = new ArrayList<>();
        if (cascadeProperties.isEnabled())
        {
            leadingWriters.add(cascadeCollectorWriter);
        }
        // Merged / metadata+path / content selection is centralised in ReindexWriterComposer, driven by
        // ReindexingCoreProperties#getEnabledWriterKeys(). Merged mode folds the metadata + path writes
        // into a single scripted-upsert bulk (its failures are dead-lettered under the MERGED_WRITER stage).
        // Batch-indexing does not use the concurrent writer, so no executor is supplied.
        Map<String, ItemWriter<? super ReindexingEvent>> writersByKey = new HashMap<>();
        writersByKey.put(MetricsConstants.MERGED, elasticsearchMergedReindexWriter);
        writersByKey.put(MetricsConstants.METADATA, elasticsearchRepoEventItemWriter);
        writersByKey.put(MetricsConstants.PATH, elasticsearchPathWriter);
        writersByKey.put(MetricsConstants.CONTENT, contentTransformDirectWriter);
        return ReindexWriterComposer.compose(elasticsearchReindexProperties, writersByKey, leadingWriters,
                chunkWriteMetricsAccumulator, null);
    }
}
