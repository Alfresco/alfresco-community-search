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

/**
 * Descriptor for a rename cascade: the old and new terminal path segments to swap in descendants' {@code PATH} / {@code UNPREFIXED_PATH} arrays. Old segments come from an mget against the source ES doc; new segments come from the reindexing event.
 *
 * @param nodeRef
 *            Alfresco node reference of the renamed node.
 * @param descendantUuid
 *            bare UUID used to scope the descendant query.
 * @param oldPrefixedSegment
 *            pre-rename PATH segment (e.g. {@code cm:oldname}).
 * @param newPrefixedSegment
 *            post-rename PATH segment (e.g. {@code cm:newname}).
 * @param oldUnprefixedSegment
 *            pre-rename UNPREFIXED_PATH segment.
 * @param newUnprefixedSegment
 *            post-rename UNPREFIXED_PATH segment.
 */
public record RenameCascadeSource(
        String nodeRef,
        String descendantUuid,
        String oldPrefixedSegment,
        String newPrefixedSegment,
        String oldUnprefixedSegment,
        String newUnprefixedSegment)
{
    /** True when the rename didn't actually change the name. */
    public boolean isNoop()
    {
        return oldPrefixedSegment != null
                && oldPrefixedSegment.equals(newPrefixedSegment)
                && oldUnprefixedSegment != null
                && oldUnprefixedSegment.equals(newUnprefixedSegment);
    }
}
