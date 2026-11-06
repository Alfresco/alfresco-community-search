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

import java.util.List;

import lombok.RequiredArgsConstructor;
import org.opensearch.client.opensearch.core.BulkRequest;
import org.opensearch.client.opensearch.core.BulkResponse;

import org.alfresco.indexing.shared.exception.ContentClearException;
import org.alfresco.indexing.shared.opensearch.ElasticsearchContentModifyPort;
import org.alfresco.indexing.shared.opensearch.command.ContentClearCmd;
import org.alfresco.indexing.shared.opensearch.command.ContentIndexCmd;
import org.alfresco.indexing.shared.opensearch.request.ContentRequestBuilder;

/**
 * Adapter implementing {@link ElasticsearchContentModifyPort} using OpenSearch client.
 */
@RequiredArgsConstructor
public class OpensearchContentModifier implements ElasticsearchContentModifyPort
{
    private final ContentRequestBuilder contentRequestBuilder;
    private final OpensearchUpdater opensearchUpdater;

    @Override
    public void upsertContent(ContentIndexCmd contentIndexCmd)
    {
        var updateRequest = contentRequestBuilder.createUpsertRequest(contentIndexCmd);
        opensearchUpdater.sendUpdate(updateRequest, contentIndexCmd.id());
    }

    @Override
    public void clearContents(List<ContentClearCmd> clears)
    {
        if (clears.isEmpty())
        {
            return;
        }
        var bulkRequest = new BulkRequest.Builder()
                .operations(clears.stream().map(contentRequestBuilder::createExclusionBulkOperation).toList())
                .build();
        BulkResponse response;
        try
        {
            response = opensearchUpdater.sendBulk(bulkRequest, "excluded content");
        }
        catch (RuntimeException e)
        {
            throw new ContentClearException("Failed to clear excluded content for %d nodes".formatted(clears.size()), e);
        }
        if (response.errors())
        {
            long failed = response.items().stream().filter(item -> item.error() != null).count();
            throw new ContentClearException(
                    "Failed to clear excluded content for %d of %d nodes".formatted(failed, clears.size()));
        }
    }
}
