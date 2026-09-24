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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import org.alfresco.elasticsearch.batchindexing.DlqReader;
import org.alfresco.elasticsearch.batchindexing.DlqWriter;
import org.alfresco.elasticsearch.batchindexing.listeners.DlqBulkItemFailureListener;
import org.alfresco.elasticsearch.batchindexing.listeners.DlqContentTransformFailureListener;
import org.alfresco.elasticsearch.batchindexing.listeners.DlqPageReadFailureListener;
import org.alfresco.elasticsearch.batchindexing.listeners.DlqPathBulkItemFailureListener;
import org.alfresco.elasticsearch.batchindexing.listeners.DlqSkipListener;
import org.alfresco.elasticsearch.batchindexing.listeners.DlqUnresolvedNamespaceListener;

/**
 * Wires the dead-letter queue components. Guarded by {@code alfresco.reindex.dead-letter.enabled}; when disabled no beans are created, so the reindexing skip-listener hook stays a no-op.
 */
@Configuration
@ConditionalOnProperty(prefix = "alfresco.reindex.dead-letter", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DeadLetterConfig
{
    @Bean
    public DlqWriter dlqWriter(OpenSearchClient openSearchClient, DeadLetterProperties properties)
    {
        DlqWriter writer = new DlqWriter(openSearchClient, properties.getIndexName());
        writer.ensureIndexExists();
        return writer;
    }

    @Bean
    public DlqReader dlqReader(OpenSearchClient openSearchClient, DeadLetterProperties properties)
    {
        return new DlqReader(openSearchClient, properties.getIndexName());
    }

    @Bean
    public DlqSkipListener dlqSkipListener(DlqWriter dlqWriter)
    {
        return new DlqSkipListener(dlqWriter);
    }

    @Bean
    public DlqBulkItemFailureListener dlqBulkItemFailureListener(DlqWriter dlqWriter)
    {
        return new DlqBulkItemFailureListener(dlqWriter);
    }

    @Bean
    public DlqPathBulkItemFailureListener dlqPathBulkItemFailureListener(DlqWriter dlqWriter)
    {
        return new DlqPathBulkItemFailureListener(dlqWriter);
    }

    @Bean
    public DlqUnresolvedNamespaceListener dlqUnresolvedNamespaceListener(DlqWriter dlqWriter)
    {
        return new DlqUnresolvedNamespaceListener(dlqWriter);
    }

    @Bean
    public DlqPageReadFailureListener dlqPageReadFailureListener(DlqWriter dlqWriter)
    {
        return new DlqPageReadFailureListener(dlqWriter);
    }

    @Bean
    public DlqContentTransformFailureListener dlqContentTransformFailureListener(DlqWriter dlqWriter)
    {
        return new DlqContentTransformFailureListener(dlqWriter);
    }
}
