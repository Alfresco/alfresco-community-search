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
package org.alfresco.reindexing.writers;

import static org.alfresco.reindexing.beans.RepositoryDescriptor.ID_PROPERTY_NAME;

import java.io.Serializable;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.item.ItemStreamException;
import org.springframework.batch.infrastructure.item.ItemStreamWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import org.alfresco.indexing.shared.ElasticsearchProperties;
import org.alfresco.reindexing.beans.RepositoryDescriptor;
import org.alfresco.reindexing.opensearch.ElasticSearchUpdateOperations;

/**
 * Gets Repository Descriptor item to verify that DB Schema is greater than the minimum supported. Additionally, Repository Descriptor is indexed in Elasticsearch to verify the UUID of the Alfresco DB has the same value. This will ensure the repository in DB and Elasticsearch is the same one. It will raise a RuntimeException to abort any ongoing Spring Batch execution flow in case any of theses conditions is not met.
 */
@Component
public class RepositoryDescriptorItemWriter implements ItemStreamWriter<RepositoryDescriptor>
{
    private static final Logger LOGGER = LoggerFactory.getLogger(RepositoryDescriptorItemWriter.class);

    private final ElasticsearchProperties elasticsearchProperties;

    private final ElasticSearchUpdateOperations elasticSearchUpdateOperations;
    private final Integer minimumSchema;

    public RepositoryDescriptorItemWriter(
            ElasticSearchUpdateOperations elasticSearchUpdateOperations,
            ElasticsearchProperties elasticsearchProperties,
            @Value("${alfresco.db.minimum.schema.version}") Integer minimumSchema)
    {
        this.elasticSearchUpdateOperations = elasticSearchUpdateOperations;
        this.elasticsearchProperties = elasticsearchProperties;
        this.minimumSchema = minimumSchema;
    }

    @Override
    public void open(ExecutionContext executionContext) throws ItemStreamException
    {}

    @Override
    public void update(ExecutionContext executionContext) throws ItemStreamException
    {}

    @Override
    public void close() throws ItemStreamException
    {}

    @Override
    public void write(Chunk<? extends RepositoryDescriptor> items) throws Exception
    {
        // Verify DB Schema is supported by this Elasticsearch Connector version
        RepositoryDescriptor descriptorFromDb = items.getItems().get(0);
        if (descriptorFromDb.getSchema() < minimumSchema)
        {
            throw new RuntimeException("Minimum DB schema version supported by re-indexing component is " + minimumSchema
                    + ", but " + descriptorFromDb.getSchema() + " schema version was found in Alfresco Database. "
                    + " Descriptor from Alfresco Database: " + descriptorFromDb);
        }

        // Verify the ID of Repository Descriptor in DB has the same value as the one indexed in Elasticsearch
        Map<String, Object> descriptorFromElasticsearch = elasticSearchUpdateOperations.getDescriptorFromElasticsearch(RepositoryDescriptor.TYPE);
        if (descriptorFromElasticsearch == null)
        {
            indexDescriptor(descriptorFromDb);
        }
        else if (!descriptorFromDb.getId().equals(descriptorFromElasticsearch.get(ID_PROPERTY_NAME)))
        {
            throw new RuntimeException("Elasticsearch was indexing Alfresco Repository with ID '" + descriptorFromElasticsearch.get(ID_PROPERTY_NAME) + "' "
                    + "but current Alfresco Database is including nodes for Alfresco Repository with ID '" + descriptorFromDb.getId() + "'. "
                    + "Verify that the target Alfresco Database is the right one before trying the re-indexing operation again.");
        }
    }

    /**
     * Find Repository Descriptor in Elasticsearch by TYPE (sys:descriptor). It's expected to find 0 to 1 documents, when finding more than one Runtime exception is raised.
     */

    /**
     * Index Repository Descriptor in Elasticsearch with TYPE sys:descriptor.
     */
    private void indexDescriptor(RepositoryDescriptor descriptor) throws Exception
    {
        Map<String, Serializable> properties = descriptor.getProperties();
        properties.put("TYPE", RepositoryDescriptor.TYPE);
        Map<String, Object> newProperties = properties.entrySet()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        elasticSearchUpdateOperations.indexNode(descriptor.getId(), elasticsearchProperties.getIndexName(), newProperties);
    }

}
