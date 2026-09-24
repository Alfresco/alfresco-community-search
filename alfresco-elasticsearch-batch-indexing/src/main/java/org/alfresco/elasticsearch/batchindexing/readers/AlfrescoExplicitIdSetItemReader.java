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
package org.alfresco.elasticsearch.batchindexing.readers;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.util.ClassUtils;

import org.alfresco.database.connector.AlfrescoMetadataRepository;
import org.alfresco.database.connector.NodeParams;
import org.alfresco.reindexing.readers.AbstractAlfrescoNodeReader;
import org.alfresco.reindexing.readers.PageReadFailureListener;

/**
 * Reads an explicit, non-contiguous set of node ids (e.g. draining a DLQ by {@code dbId}), paging through a sorted copy of the set. Unlike {@code AlfrescoPagingItemReaderById} the ids need not be contiguous or ordered on input.
 */
public class AlfrescoExplicitIdSetItemReader extends AbstractAlfrescoNodeReader
{
    private final List<Long> sortedIds;
    private int nextIndex;

    public AlfrescoExplicitIdSetItemReader(AlfrescoMetadataRepository metadataRepository, Set<Long> ids)
    {
        this(metadataRepository, ids, null);
    }

    public AlfrescoExplicitIdSetItemReader(AlfrescoMetadataRepository metadataRepository, Set<Long> ids, PageReadFailureListener pageReadFailureListener)
    {
        super(metadataRepository, pageReadFailureListener);
        this.sortedIds = ids.stream().sorted().collect(Collectors.toUnmodifiableList());
        setName(ClassUtils.getShortName(AlfrescoExplicitIdSetItemReader.class));
    }

    @Override
    protected NodeParams getParams()
    {
        int pageSize = getPageSize();
        if (pageSize <= 0)
        {
            throw new IllegalStateException("pageSize must be greater than zero but was " + pageSize);
        }
        int end = Math.min(nextIndex + pageSize, sortedIds.size());
        Set<Long> page = Set.copyOf(sortedIds.subList(nextIndex, end));
        nextIndex = end;
        return NodeParams.searchByIds(page);
    }

    @Override
    protected boolean needMoreNodes()
    {
        return nextIndex < sortedIds.size();
    }
}
