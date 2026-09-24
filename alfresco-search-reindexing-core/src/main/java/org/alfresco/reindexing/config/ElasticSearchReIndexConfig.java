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

import org.opensearch.client.opensearch.OpenSearchClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import org.alfresco.indexing.shared.ElasticsearchFieldsPostProcessor;
import org.alfresco.indexing.shared.ElasticsearchProperties;
import org.alfresco.indexing.shared.PropertiesDeterminingStrategy;
import org.alfresco.indexing.shared.config.multilingual.MultilingualSupportSharedConfig;
import org.alfresco.indexing.shared.extractor.DefaultEncodedFieldsExtractor;
import org.alfresco.indexing.shared.extractor.EncodedFieldsExtractor;
import org.alfresco.indexing.shared.opensearch.ElasticsearchContentModifyPort;
import org.alfresco.indexing.shared.opensearch.adapter.OpensearchContentModifier;
import org.alfresco.indexing.shared.opensearch.adapter.OpensearchUpdater;
import org.alfresco.indexing.shared.opensearch.request.ContentRequestBuilder;
import org.alfresco.reindexing.opensearch.ElasticSearchUpdateOperations;
import org.alfresco.reindexing.opensearch.impl.ElasticSearchUpdateOperationsImpl;
import org.alfresco.reindexing.opensearch.request.ReindexingRequestBuilder;

@Configuration
@Import(MultilingualSupportSharedConfig.class)
public class ElasticSearchReIndexConfig
{
    @Bean
    public EncodedFieldsExtractor encodedFieldsExtractor(
            ElasticsearchFieldsPostProcessor elasticsearchFieldsPostProcessor,
            PropertiesDeterminingStrategy propertiesDeterminingStrategy)
    {
        return new DefaultEncodedFieldsExtractor(elasticsearchFieldsPostProcessor, propertiesDeterminingStrategy);
    }

    @Bean
    public ReindexingRequestBuilder reindexingRequestBuilder(
            ElasticsearchProperties elasticsearchProperties,
            EncodedFieldsExtractor encodedFieldsExtractor)
    {
        return new ReindexingRequestBuilder(elasticsearchProperties, encodedFieldsExtractor);
    }

    @Bean
    public ElasticSearchUpdateOperations elasticSearchUpdateOperations(OpenSearchClient opensearchClient, ElasticsearchProperties elasticsearchProperties,
            ReindexingRequestBuilder reindexingRequestBuilder)
    {
        return new ElasticSearchUpdateOperationsImpl(opensearchClient, elasticsearchProperties, reindexingRequestBuilder);
    }

    @Bean
    public ContentRequestBuilder contentRequestBuilder(ElasticsearchProperties elasticsearchProperties)
    {
        return new ContentRequestBuilder(elasticsearchProperties);
    }

    @Bean
    public OpensearchUpdater opensearchUpdater(OpenSearchClient opensearchClient)
    {
        return new OpensearchUpdater(opensearchClient);
    }

    @Bean
    public ElasticsearchContentModifyPort elasticsearchContentModifyPort(
            ContentRequestBuilder contentRequestBuilder,
            OpensearchUpdater opensearchUpdater)
    {
        return new OpensearchContentModifier(contentRequestBuilder, opensearchUpdater);
    }
}
