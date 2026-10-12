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
package org.alfresco.reindexing.readers;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.springframework.batch.infrastructure.item.ItemStreamReader;
import org.springframework.util.Assert;

import org.alfresco.database.connector.AlfrescoMetadataRepository;
import org.alfresco.database.connector.NodeParams;
import org.alfresco.database.connector.model.AlfrescoNode;

public class AlfrescoPagingItemReaderBuilder
{
    private String name;
    private int pageSize = 10;
    private AlfrescoMetadataRepository metadataRepository;
    private NodeParams nodeParams;
    private long[] byIdParams;
    private PageReadFailureListener pageReadFailureListener;

    AlfrescoPagingItemReaderBuilder()
    {}

    public static AlfrescoPagingItemReaderBuilder forIdRange(long from, long to)
    {
        final AlfrescoPagingItemReaderBuilder builder = new AlfrescoPagingItemReaderBuilder();
        builder.nodeParams = NodeParams.searchByIdRange(from, to);
        return builder;
    }

    public static AlfrescoPagingItemReaderBuilder forFolderNodeId(String folderNodeId)
    {
        final AlfrescoPagingItemReaderBuilder builder = new AlfrescoPagingItemReaderBuilder();
        builder.nodeParams = NodeParams.searchByFolderId(folderNodeId);
        return builder;
    }

    public static AlfrescoPagingItemReaderBuilder forTimestampRange(LocalDateTime from, LocalDateTime to)
    {
        final AlfrescoPagingItemReaderBuilder builder = new AlfrescoPagingItemReaderBuilder();

        final long fromTs = from.toInstant(ZoneOffset.UTC).toEpochMilli();
        final long toTs = to.toInstant(ZoneOffset.UTC).toEpochMilli();
        builder.nodeParams = NodeParams.searchByTimestampRange(fromTs, toTs);

        return builder;
    }

    public static AlfrescoPagingItemReaderBuilder forSchema()
    {
        final AlfrescoPagingItemReaderBuilder builder = new AlfrescoPagingItemReaderBuilder();
        builder.nodeParams = NodeParams.searchForSchemaNode();
        return builder;
    }

    public static AlfrescoPagingItemReaderBuilder forReadingById(long from, long to)
    {
        final AlfrescoPagingItemReaderBuilder builder = new AlfrescoPagingItemReaderBuilder();
        builder.byIdParams = new long[]{from, to};
        return builder;
    }

    public AlfrescoPagingItemReaderBuilder name(String name)
    {
        this.name = name;
        return this;
    }

    public AlfrescoPagingItemReaderBuilder metadataRepository(AlfrescoMetadataRepository metadataRepository)
    {
        this.metadataRepository = metadataRepository;
        return this;
    }

    public AlfrescoPagingItemReaderBuilder pageSize(int pageSize)
    {
        this.pageSize = pageSize;
        return this;
    }

    public AlfrescoPagingItemReaderBuilder pageReadFailureListener(PageReadFailureListener pageReadFailureListener)
    {
        this.pageReadFailureListener = pageReadFailureListener;
        return this;
    }

    public ItemStreamReader<AlfrescoNode> build()
    {
        Assert.isTrue(this.pageSize > 0, "pageSize must be greater than zero");

        AbstractAlfrescoNodeReader reader = createReaderInstance();
        reader.setPageSize(this.pageSize);
        reader.setName(this.name);

        return reader;
    }

    protected AbstractAlfrescoNodeReader createReaderInstance()
    {
        if (byIdParams != null)
            return new AlfrescoPagingItemReaderById(metadataRepository, byIdParams[0], byIdParams[1], pageReadFailureListener);

        // Use keyset pagination for timestamp ranges to avoid offset-based pagination performance issues
        if (nodeParams != null && nodeParams.getTimestampRange().isPresent())
        {
            var range = nodeParams.getTimestampRange().get();
            return new AlfrescoPagingItemReaderByTimestamp(metadataRepository, range.getBegin(), range.getEnd(), pageReadFailureListener);
        }

        return new AlfrescoPagingItemReader(metadataRepository, nodeParams, pageReadFailureListener);
    }
}
