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
package org.alfresco.indexing.shared.opensearch;

import java.util.List;

import org.alfresco.indexing.shared.opensearch.command.ContentClearCmd;
import org.alfresco.indexing.shared.opensearch.command.ContentIndexCmd;

/**
 * Secondary port for Content Indexing operations in Elasticsearch.
 */
public interface ElasticsearchContentModifyPort
{
    /**
     * Inserts or updates mapping related to Content Indexing of the node.
     *
     * @param contentIndexCmd
     *            the content indexing command
     */
    void upsertContent(ContentIndexCmd contentIndexCmd);

    /**
     * Clears the indexed content and transform status of several excluded nodes in one bulk request. Fails if any of them could not be cleared.
     * <p>
     * Defaults to throwing {@link UnsupportedOperationException} so existing implementations keep compiling; adapters used with a content indexing filter must override it.
     *
     * @param clears
     *            the clear commands, one per node
     */
    default void clearContents(List<ContentClearCmd> clears)
    {
        throw new UnsupportedOperationException(getClass().getName() + " does not support clearing excluded content");
    }
}
