/*-
 * #%L
 * Alfresco Search Community :: Batch Indexing
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
 * Test-only helpers for the batch-indexing cascade package.
 */
final class CascadeTestHelper
{
    private CascadeTestHelper()
    {}

    /** Builds a {@link RenameCascadeSource} with just nodeRef and new unprefixed name populated. */
    static RenameCascadeSource skeleton(String nodeRef, String newName)
    {
        return new RenameCascadeSource(nodeRef, null, null, null, null, newName);
    }

    /** Builds a {@link CategoryDeleteCascadeSource} with empty path snapshots (paths irrelevant to the test). */
    static CategoryDeleteCascadeSource categoryDelete(String nodeRef, String descendantUuid)
    {
        return new CategoryDeleteCascadeSource(nodeRef, descendantUuid, List.of(), List.of());
    }

    /**
     * Builds a {@link CascadeSweepService} with all collaborators null. Used to verify the service's null-wiring safety guards.
     */
    static CascadeSweepService nullWiredSweepService()
    {
        return new CascadeSweepService(null, null, null);
    }
}
