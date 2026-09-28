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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Thread-safe, cycle-scoped bag of cascade sources observed during a batch-indexing cycle.
 * <p>
 * Populated by {@link CascadeCollectorWriter} as it observes {@code ReindexingEvent}s flowing through the composite {@code ItemWriter}, then drained by {@link CascadeSweepService} once the enclosing {@code reindexByDate} job completes successfully.
 * <p>
 * Both maps are keyed by node reference so duplicate observations within a cycle (e.g., overlapping windows) collapse into a single cascade source. The first-observed value wins on rename to avoid overwriting the recorded name if the same node re-appears later in the cycle.
 */
public class CascadeSourceStash
{
    private final ConcurrentMap<String, RenameCascadeSource> renames = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CategoryDeleteCascadeSource> categoryDeletes = new ConcurrentHashMap<>();

    public void recordRename(RenameCascadeSource source)
    {
        renames.putIfAbsent(source.nodeRef(), source);
    }

    public void recordCategoryDelete(CategoryDeleteCascadeSource source)
    {
        categoryDeletes.putIfAbsent(source.nodeRef(), source);
    }

    public int renameCount()
    {
        return renames.size();
    }

    public int categoryDeleteCount()
    {
        return categoryDeletes.size();
    }

    /**
     * Atomically empties the stash and returns everything observed since the last call.
     */
    public CascadeSourceBatch drain()
    {
        List<RenameCascadeSource> renameSnapshot = new ArrayList<>(renames.values());
        List<CategoryDeleteCascadeSource> deleteSnapshot = new ArrayList<>(categoryDeletes.values());
        renames.clear();
        categoryDeletes.clear();
        return new CascadeSourceBatch(renameSnapshot, deleteSnapshot);
    }

    public void clear()
    {
        renames.clear();
        categoryDeletes.clear();
    }
}
