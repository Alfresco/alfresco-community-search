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
package org.alfresco.reindexing.config;

/**
 * What to do with a node whose own type is in a namespace with no prefix mapping (ACS-12851).
 * <p>
 * Either way the loss is reported and recorded in the dead-letter index; this only decides whether indexing carries on.
 */
public enum UnresolvedNamespaceAction
{
    /** Drop the node and keep indexing. Spring Batch counts it as filtered. */
    FILTER,
    /** Fail the item, so it counts as skipped and {@code alfresco.reindex.skipLimit} applies. */
    SKIP,
    /**
     * Fail the item and refuse to carry on: the item is not skippable, so the cycle fails and batch-indexing terminates. Halts indexing for the whole repository until the prefix map is fixed.
     */
    STOP
}
