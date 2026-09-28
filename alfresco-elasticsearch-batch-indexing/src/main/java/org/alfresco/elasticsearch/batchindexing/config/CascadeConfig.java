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

import org.opensearch.client.opensearch.OpenSearchClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import org.alfresco.elasticsearch.batchindexing.cascade.CascadeCollectorWriter;
import org.alfresco.elasticsearch.batchindexing.cascade.CascadeSourceStash;
import org.alfresco.elasticsearch.batchindexing.cascade.CascadeSweepService;
import org.alfresco.elasticsearch.batchindexing.cascade.CategoryDeleteCascadeService;
import org.alfresco.elasticsearch.batchindexing.cascade.RenameCascadeService;
import org.alfresco.elasticsearch.batchindexing.cascade.SourceDocLookup;
import org.alfresco.indexing.shared.ElasticsearchProperties;

/**
 * Bean wiring for the batch-indexing cascade support. Beans are created unconditionally; the {@link CascadeProperties#isEnabled() enabled} flag controls whether the collector is wired into the composite writer.
 */
@Configuration
public class CascadeConfig
{
    @Bean
    public CascadeSourceStash cascadeSourceStash()
    {
        return new CascadeSourceStash();
    }

    @Bean
    public SourceDocLookup cascadeSourceDocLookup(OpenSearchClient openSearchClient,
            ElasticsearchProperties elasticsearchProperties)
    {
        return new SourceDocLookup(openSearchClient, elasticsearchProperties);
    }

    @Bean
    public CascadeCollectorWriter cascadeCollectorWriter(CascadeSourceStash cascadeSourceStash,
            SourceDocLookup cascadeSourceDocLookup)
    {
        return new CascadeCollectorWriter(cascadeSourceStash, cascadeSourceDocLookup);
    }

    @Bean
    public RenameCascadeService renameCascadeService(OpenSearchClient openSearchClient,
            ElasticsearchProperties elasticsearchProperties,
            CascadeProperties cascadeProperties)
    {
        return new RenameCascadeService(openSearchClient, elasticsearchProperties, cascadeProperties);
    }

    @Bean
    public CategoryDeleteCascadeService categoryDeleteCascadeService(OpenSearchClient openSearchClient,
            ElasticsearchProperties elasticsearchProperties,
            CascadeProperties cascadeProperties)
    {
        return new CategoryDeleteCascadeService(openSearchClient, elasticsearchProperties, cascadeProperties);
    }

    @Bean
    public CascadeSweepService cascadeSweepService(CascadeSourceStash cascadeSourceStash,
            RenameCascadeService renameCascadeService,
            CategoryDeleteCascadeService categoryDeleteCascadeService)
    {
        return new CascadeSweepService(cascadeSourceStash, renameCascadeService, categoryDeleteCascadeService);
    }
}
