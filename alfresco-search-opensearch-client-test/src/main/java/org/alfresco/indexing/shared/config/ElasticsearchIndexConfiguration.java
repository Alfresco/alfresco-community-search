/*-
 * #%L
 * Alfresco Search Community :: OpenSearch Client :: Test
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
package org.alfresco.indexing.shared.config;

import org.opensearch.client.opensearch.OpenSearchClient;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableAsync;

import org.alfresco.indexing.shared.ElasticIndexService;
import org.alfresco.indexing.shared.ElasticSearchIndexInitializationService;

@Configuration
@EnableRetry
@EnableAsync
public class ElasticsearchIndexConfiguration
{

    private class ElasticsearchIndexInitializer implements InitializingBean
    {

        private final ElasticSearchIndexInitializationService elasticSearchIndexInitializationService;
        private final ApplicationContext applicationContext;

        public ElasticsearchIndexInitializer(ElasticSearchIndexInitializationService elasticSearchIndexInitializationService, ApplicationContext applicationContext)
        {
            this.elasticSearchIndexInitializationService = elasticSearchIndexInitializationService;
            this.applicationContext = applicationContext;
        }

        @Override
        public void afterPropertiesSet() throws Exception
        {
            String indexName = applicationContext.getEnvironment().getProperty("elasticsearch.indexName");
            elasticSearchIndexInitializationService.createIndexMappingIfNotExists(indexName, "classpath:elasticsearch/conf/schema.json");
        }
    }

    @Bean
    public ElasticsearchIndexInitializer initializer(ElasticSearchIndexInitializationService elasticSearchIndexInitializationService, ApplicationContext applicationContext)
    {
        return new ElasticsearchIndexInitializer(elasticSearchIndexInitializationService, applicationContext);
    }

    @Bean
    public ElasticSearchIndexInitializationService ElasticSearchIndexInitializationService(ElasticIndexService elasticIndexService,
            ResourceLoader resourceLoader)
    {
        return new ElasticSearchIndexInitializationService(elasticIndexService, resourceLoader);
    }

    @Bean
    public ElasticIndexService elasticIndexService(OpenSearchClient openSearchClient)
    {
        return new ElasticIndexService(openSearchClient);
    }

}
