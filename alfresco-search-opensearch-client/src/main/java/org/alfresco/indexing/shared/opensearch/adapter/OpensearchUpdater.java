/*-
 * #%L
 * Alfresco Search Community :: OpenSearch Client
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
package org.alfresco.indexing.shared.opensearch.adapter;

import static java.lang.String.format;

import java.io.IOException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.core.BulkRequest;
import org.opensearch.client.opensearch.core.BulkResponse;
import org.opensearch.client.opensearch.core.UpdateRequest;

import org.alfresco.indexing.shared.exception.ElasticsearchInfrastructureException;
import org.alfresco.indexing.shared.exception.FailedToUpsertNodeIndexException;

@Slf4j
@RequiredArgsConstructor
public class OpensearchUpdater
{
    private final OpenSearchClient openSearchClient;

    public void sendUpdate(UpdateRequest updateRequest, String nodeId)
    {
        try
        {
            log.debug("Updating node {}, request: {}", nodeId, updateRequest);
            openSearchClient.update(updateRequest, Object.class);
            log.debug("Updated node {}", nodeId);
        }
        catch (IOException e)
        {
            throw new ElasticsearchInfrastructureException(format("Failed to upsert index of node %s", nodeId), e);
        }
        catch (Exception e)
        {
            throw new FailedToUpsertNodeIndexException(nodeId, e);
        }
    }

    public BulkResponse sendBulk(BulkRequest bulkRequest, String nodeId)
    {
        try
        {
            log.debug("Modifying node {}, bulk request: {}", nodeId, bulkRequest);
            return openSearchClient.bulk(bulkRequest);
        }
        catch (IOException e)
        {
            throw new ElasticsearchInfrastructureException("Failed to update ElasticSearch with bulk request", e);
        }
    }
}
