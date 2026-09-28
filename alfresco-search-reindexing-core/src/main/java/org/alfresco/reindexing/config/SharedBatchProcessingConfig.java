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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.batch.infrastructure.item.support.CompositeItemProcessor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import org.alfresco.content.mediatype.AcceptedContentMediaTypeCacheConfig;
import org.alfresco.content.mediatype.AcceptedContentMediaTypesCache;
import org.alfresco.content.mediatype.AcceptedContentMediaTypesCacheProperties;
import org.alfresco.database.connector.AlfrescoMetadataRepository;
import org.alfresco.database.connector.model.AlfrescoNode;
import org.alfresco.database.connector.model.TagData;
import org.alfresco.indexing.shared.ElasticsearchFieldsPostProcessor;
import org.alfresco.indexing.shared.ElasticsearchProperties;
import org.alfresco.indexing.shared.config.FilteringConfig;
import org.alfresco.indexing.shared.config.multilingual.MultilingualSupportSharedConfig;
import org.alfresco.indexing.shared.tag.AddTagsPostProcessor;
import org.alfresco.indexing.shared.tag.MeasuringTagRepository;
import org.alfresco.indexing.shared.tag.TagRepository;
import org.alfresco.indexing.shared.transform.TransformStatusExtender;
import org.alfresco.metrics.MetricsConstants;
import org.alfresco.metrics.ReindexingMetrics;
import org.alfresco.reindexing.StandardPathsResolver;
import org.alfresco.reindexing.domain.transform.ContentTransformSupportService;
import org.alfresco.reindexing.listeners.FilterCountingListener;
import org.alfresco.reindexing.listeners.JobExitStatusAggregatorListener;
import org.alfresco.reindexing.listeners.JobLifecycleListener;
import org.alfresco.reindexing.listeners.ReadCountingListener;
import org.alfresco.reindexing.listeners.ReindexMetricsStepListener;
import org.alfresco.reindexing.listeners.StepLifecycleListener;
import org.alfresco.reindexing.listeners.WriteMetricsFlushListener;
import org.alfresco.reindexing.model.ReindexingEvent;
import org.alfresco.reindexing.processors.AlfrescoNodeToReindexingEventProcessor;
import org.alfresco.reindexing.processors.ReindexEventFieldsRemoverProcessor;
import org.alfresco.reindexing.processors.ReindexEventNodeAspectFilterProcessor;
import org.alfresco.reindexing.processors.ReindexEventNodeTypeFilterProcessor;
import org.alfresco.reindexing.processors.ReindexEventPathAdderProcessor;
import org.alfresco.reindexing.processors.ReindexEventTrStatusAdderProcessor;
import org.alfresco.reindexing.processors.UnresolvedNamespaceListener;
import org.alfresco.reindexing.writers.ChunkWriteMetricsAccumulator;
import org.alfresco.repo.event.databind.ObjectMapperFactory;

/**
 * Bean definitions shared between the reindexing module (JMS-driven content path) and the batch-indexing module (direct-HTTP content path).
 * <p>
 * Module-specific bits — JMS template + content event writer, or the direct-HTTP writer, plus the writer composition that picks between them — stay in each module's own {@code @Configuration}.
 */
@Configuration
@SuppressWarnings("PMD.CouplingBetweenObjects")
@EnableConfigurationProperties({ElasticsearchProperties.class, AcceptedContentMediaTypesCacheProperties.class})
@Import({FilteringConfig.class,
        MultilingualSupportSharedConfig.class,
        AcceptedContentMediaTypeCacheConfig.class,
        AcceptedContentMediaTypesCacheProperties.class,
        ElasticSearchReIndexConfig.class
})
public class SharedBatchProcessingConfig
{
    private final ReindexingCoreProperties reindexingProperties;
    private final AcceptedContentMediaTypesCache mediaTypesCache;

    public SharedBatchProcessingConfig(
            ReindexingCoreProperties reindexingProperties,
            AcceptedContentMediaTypesCache mediaTypesCache)
    {
        this.reindexingProperties = reindexingProperties;
        this.mediaTypesCache = mediaTypesCache;
    }

    @EventListener(value = ApplicationStartedEvent.class,
            condition = "@acceptedContentMediaTypesCacheProperties.isEnabled()")
    public void mediaTypeCacheScheduler()
    {
        mediaTypesCache.refresh();
    }

    @Bean
    @Primary
    public ElasticsearchFieldsPostProcessor addTagsPostProcessor(
            AlfrescoMetadataRepository metadataRepository,
            MeterRegistry meterRegistry,
            @Value("${alfresco.reindex.failOnMissingTag:false}") boolean failOnMissingTag,
            @Value("${alfresco.reindex.addEmptyTagAttribute:true}") boolean addEmptyTagAttribute,
            @Value("${alfresco.reindex.removeTaggableAttribute:true}") boolean removeTaggableAttribute)
    {
        final TagRepository tagRepo = tagId -> Optional.ofNullable(metadataRepository.getAllTags().get(tagId)).map(TagData::getTagName);
        return new AddTagsPostProcessor(new MeasuringTagRepository(tagRepo, meterRegistry),
                removeTaggableAttribute, addEmptyTagAttribute, failOnMissingTag);
    }

    @Bean
    public ObjectMapper objectMapper()
    {
        return ObjectMapperFactory.createInstance();
    }

    @Bean
    public ContentTransformSupportService contentTransformSupportService(
            FilteringConfig filteringConfig,
            AcceptedContentMediaTypesCache mediaTypesCache)
    {
        return new ContentTransformSupportService(filteringConfig, mediaTypesCache);
    }

    @Bean
    public TransformStatusExtender transformStatusExtender()
    {
        return new TransformStatusExtender();
    }

    /* destroyMethod="" avoids calling close method twice, because Spring tries to automatically infer a destroyMethod when using Java configuration. */

    @Bean
    public ChunkWriteMetricsAccumulator chunkWriteMetricsAccumulator()
    {
        return new ChunkWriteMetricsAccumulator();
    }

    @Bean
    public WriteMetricsFlushListener writeMetricsFlushListener(
            ChunkWriteMetricsAccumulator chunkWriteMetricsAccumulator,
            ReindexingMetrics reindexingMetrics,
            ReindexingCoreProperties elasticsearchReindexProperties)
    {
        return new WriteMetricsFlushListener(chunkWriteMetricsAccumulator, reindexingMetrics, elasticsearchReindexProperties);
    }

    @Bean(name = "reindexTaskExecutor")
    public ThreadPoolTaskExecutor reindexTaskExecutor()
    {
        int processors = reindexingProperties.getConcurrentProcessors();
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(processors);
        executor.setMaxPoolSize(processors);
        executor.setAllowCoreThreadTimeOut(true);
        executor.setThreadNamePrefix("reindex-");
        return executor;
    }

    @Bean
    public JobExitStatusAggregatorListener jobExitStatusAggregatorListener()
    {
        return new JobExitStatusAggregatorListener();
    }

    @Bean
    public StepLifecycleListener stepLifecycleListener()
    {
        return new StepLifecycleListener();
    }

    @Bean
    public JobLifecycleListener lifecycleListener(ReindexingMetrics reindexingMetrics, ReindexNodeRole reindexNodeRole)
    {
        return new JobLifecycleListener(reindexingMetrics, reindexNodeRole);
    }

    @Bean
    public ReadCountingListener readCountingListener(ReindexingMetrics reindexingMetrics)
    {
        return new ReadCountingListener(reindexingMetrics);
    }

    @Bean
    public FilterCountingListener filterCountingListener(ReindexingMetrics reindexingMetrics)
    {
        return new FilterCountingListener(reindexingMetrics);
    }

    @Bean
    public ReindexMetricsStepListener reindexMetricsStepListener(ReindexingMetrics reindexingMetrics)
    {
        List<String> enabledWriters = new ArrayList<>();
        if (reindexingProperties.isMetadataIndexingEnabled())
        {
            enabledWriters.add(MetricsConstants.METADATA);
        }
        if (reindexingProperties.isPathIndexingEnabled())
        {
            enabledWriters.add(MetricsConstants.PATH);
        }
        if (reindexingProperties.isContentIndexingEnabled())
        {
            enabledWriters.add(MetricsConstants.CONTENT);
        }
        return new ReindexMetricsStepListener(reindexingMetrics, List.copyOf(enabledWriters));
    }

    @Bean
    public AlfrescoNodeToReindexingEventProcessor nodeToRepoEventProcessor(PrefixNamespaceConfig prefixNamespaceConfig,
            ReindexingMetrics reindexingMetrics,
            ObjectProvider<UnresolvedNamespaceListener> unresolvedNamespaceListener)
    {
        // ObjectProvider keeps the listener optional — the reindexing module ships without a bean,
        // batch-indexing registers one to feed its DLQ.
        return new AlfrescoNodeToReindexingEventProcessor(prefixNamespaceConfig, reindexingMetrics,
                unresolvedNamespaceListener.getIfAvailable(), reindexingProperties.getOnUnresolvedNamespace());
    }

    @Bean
    public StandardPathsResolver pathResolver(AlfrescoMetadataRepository metadataRepository)
    {
        return new StandardPathsResolver(metadataRepository);
    }

    @Bean
    public ReindexEventPathAdderProcessor repoEventToReindexingRepoEventProcessor(
            StandardPathsResolver pathResolver,
            ReindexingCoreProperties elasticsearchReindexProperties)
    {
        return new ReindexEventPathAdderProcessor(pathResolver, elasticsearchReindexProperties);
    }

    @Bean
    public ReindexEventNodeTypeFilterProcessor reindexEventNodeTypeFilterProcessor(FilteringConfig filteringConfig)
    {
        return new ReindexEventNodeTypeFilterProcessor(filteringConfig);
    }

    @Bean
    public ReindexEventNodeAspectFilterProcessor reindexEventNodeAspectFilterProcessor(FilteringConfig filteringConfig)
    {
        return new ReindexEventNodeAspectFilterProcessor(filteringConfig);
    }

    @Bean
    public ReindexEventFieldsRemoverProcessor repoEventFieldsRemoverProcessor(FilteringConfig filteringConfig)
    {
        return new ReindexEventFieldsRemoverProcessor(filteringConfig);
    }

    @Bean
    ReindexEventTrStatusAdderProcessor reindexEventTrStatusAdderProcessor(
            ContentTransformSupportService contentTransformSupportService,
            TransformStatusExtender transformStatusExtender)
    {
        return new ReindexEventTrStatusAdderProcessor(contentTransformSupportService, transformStatusExtender);
    }

    @Bean("processors")
    public ItemProcessor<AlfrescoNode, ReindexingEvent> processors(
            AlfrescoNodeToReindexingEventProcessor nodeToReindexingEventProcessor,
            ReindexEventFieldsRemoverProcessor fieldsRemoverProcessor,
            ReindexEventNodeTypeFilterProcessor nodeTypeFilterProcessor,
            ReindexEventPathAdderProcessor pathAdderProcessor,
            ReindexEventNodeAspectFilterProcessor nodeAspectFilterProcessor,
            ReindexEventTrStatusAdderProcessor trStatusAdderProcessor,
            ReindexingCoreProperties elasticsearchReindexProperties)
    {
        List<ItemProcessor<?, ReindexingEvent>> processorsChain = new ArrayList<>();
        processorsChain.add(nodeToReindexingEventProcessor);
        processorsChain.add(fieldsRemoverProcessor);
        processorsChain.add(nodeTypeFilterProcessor);
        processorsChain.add(nodeAspectFilterProcessor);
        processorsChain.add(pathAdderProcessor);
        if (elasticsearchReindexProperties.isContentIndexingEnabled())
        {
            processorsChain.add(trStatusAdderProcessor);
        }
        return new CompositeItemProcessor<>(processorsChain);
    }
}
