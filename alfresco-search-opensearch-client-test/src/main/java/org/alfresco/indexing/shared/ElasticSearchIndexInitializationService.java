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
package org.alfresco.indexing.shared;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.nio.charset.Charset;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ResourceLoader;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

@Component
public class ElasticSearchIndexInitializationService
{

    protected final static Logger LOGGER = LoggerFactory.getLogger(ElasticSearchIndexInitializationService.class);

    private final ElasticIndexService indexService;
    private final ResourceLoader resourceLoader;

    public ElasticSearchIndexInitializationService(ElasticIndexService indexService,
            ResourceLoader resourceLoader)
    {
        this.indexService = indexService;
        this.resourceLoader = resourceLoader;
    }

    @Retryable(include = ConnectException.class,
            maxAttemptsExpression = "#{${alfresco.elasticsearch.init.connection-max-attemps:100}}",
            backoff = @Backoff(delayExpression = "#{${alfresco.elasticsearch.init.connection-delay:5000}}"))
    public void createIndexMappingIfNotExists(String indexName, String indexSchemaLocation)
    {
        LOGGER.info("Trying to create '" + indexName + "' index");
        if (!indexService.existsIndex(indexName))
        {
            try
            {
                indexService.createIndex(indexName, getJsonMapping(indexSchemaLocation));
                LOGGER.info("Index '" + indexName + "' created");
            }
            catch (IOException e)
            {
                LOGGER.error("File " + indexSchemaLocation + " couldn't be opened!", e);
            }
        }
        else
        {
            LOGGER.info("Index '" + indexName + "' already exists.");
        }
    }

    private String getJsonMapping(String indexSchemaLocation) throws IOException
    {
        InputStream elasticIndexMappingInputStream = resourceLoader.getResource(indexSchemaLocation)
                .getInputStream();
        return StreamUtils.copyToString(elasticIndexMappingInputStream, Charset.defaultCharset());
    }

}
