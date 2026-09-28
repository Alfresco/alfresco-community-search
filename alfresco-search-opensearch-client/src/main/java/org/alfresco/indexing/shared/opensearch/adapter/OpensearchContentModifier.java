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

import lombok.RequiredArgsConstructor;

import org.alfresco.indexing.shared.opensearch.ElasticsearchContentModifyPort;
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
}
