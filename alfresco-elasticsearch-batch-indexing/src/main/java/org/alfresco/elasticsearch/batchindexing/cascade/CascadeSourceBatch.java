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
package org.alfresco.elasticsearch.batchindexing.cascade;

import java.util.List;

/**
 * Snapshot of cascade sources drained from {@link CascadeSourceStash} at the end of a reindex cycle.
 *
 * @param renames
 *            rename candidates observed during the cycle
 * @param categoryDeletes
 *            delete candidates observed during the cycle
 */
public record CascadeSourceBatch(
        List<RenameCascadeSource> renames,
        List<CategoryDeleteCascadeSource> categoryDeletes)
{
    public boolean isEmpty()
    {
        return renames.isEmpty() && categoryDeletes.isEmpty();
    }

    public int totalSize()
    {
        return renames.size() + categoryDeletes.size();
    }
}
