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

import org.springframework.util.ClassUtils;

import org.alfresco.database.connector.AlfrescoMetadataRepository;
import org.alfresco.database.connector.NodeParams;

/**
 * Paging item reader that uses keyset pagination (cursor-based) for timestamp ranges. This implementation avoids the performance problems of offset-based pagination by using the last node ID as a cursor, ensuring OFFSET is always 0.
 *
 * Performance benefit depends on the database planner: optimal when an index walk on node ID is used, but may be O(window) if a timestamp index scan dominates. Always avoids the O(n) cost of offset-based pagination where n is the offset value.
 *
 * ## Fault Tolerance
 *
 * When a page-read fails (database error, network issue), this reader stops the scan and calls the pageReadFailureListener to record the failed timestamp window in the dead-letter queue. The next reindex cycle will retry the entire range. This behavior is consistent with other readers in the application (see {@link AlfrescoPagingItemReaderById}, {@link AlfrescoPagingItemReader}).
 *
 * Duplicate rows may be processed again on retry, but Elasticsearch writes are idempotent (overwrites are harmless).
 *
 * ## Restart Behavior
 *
 * On Spring Batch job restart (step execution resumed from checkpoint), this reader restarts from the beginning of the timestamp range, not from the last committed ID. This design choice is consistent with other readers in the reindexing application. Duplicate rows will be processed again on restart, but writes are idempotent.
 *
 * For long-running jobs requiring checkpoint resumption, restart support should be addressed as a product-wide enhancement across all readers (see Jira discussion on restart infrastructure).
 */
public class AlfrescoPagingItemReaderByTimestamp extends AbstractAlfrescoNodeReader
{

    private final long fromTimestampInclusive;
    private final long toTimestampExclusive;

    public AlfrescoPagingItemReaderByTimestamp(AlfrescoMetadataRepository metadataRepository, long fromTs, long toTs)
    {
        this(metadataRepository, fromTs, toTs, null);
    }

    public AlfrescoPagingItemReaderByTimestamp(AlfrescoMetadataRepository metadataRepository, long fromTs, long toTs, PageReadFailureListener pageReadFailureListener)
    {
        super(metadataRepository, pageReadFailureListener);
        this.fromTimestampInclusive = fromTs;
        this.toTimestampExclusive = toTs;
        setName(ClassUtils.getShortName(AlfrescoPagingItemReaderByTimestamp.class));
    }

    @Override
    protected NodeParams getParams()
    {
        // For keyset pagination, we need to track both timestamp and ID
        // to handle duplicate timestamps correctly
        final NodeParams params;

        if (results != null && !results.isEmpty())
        {
            // Use the last node ID to create a moving window
            // This ensures we continue from where we left off without using OFFSET
            Long lastId = results.get(results.size() - 1).getId();

            // Combine timestamp filter with ID range for keyset pagination
            // The database connector will filter by both timestamp AND id > lastId
            params = NodeParams.searchByTimestampRange(fromTimestampInclusive, toTimestampExclusive)
                    .withIdRange(lastId + 1, Long.MAX_VALUE);
        }
        else
        {
            // First page: just use timestamp range
            params = NodeParams.searchByTimestampRange(fromTimestampInclusive, toTimestampExclusive);
        }

        // Always use page=0, which means OFFSET=0 for optimal performance
        return params.withPaging(0, getPageSize());
    }

    @Override
    protected boolean needMoreNodes()
    {
        return results == null || !results.isEmpty();
    }
}
