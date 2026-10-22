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
 * Descriptor for a delete event that corresponds to a {@code cm:category} node.
 *
 * @param nodeRef
 *            Alfresco node reference of the deleted node
 * @param descendantUuid
 *            bare UUID — value stored inside referring documents' {@code CATEGORY_ANCESTOR} / {@code cm:categories} / {@code PARENT} arrays
 * @param deletedCategoryPaths
 *            snapshot of the deleted category's {@code PATH} entries (prefixed); used to strip matching {@code PATH} entries from referring docs. May be empty when the pre-delete doc could not be read.
 * @param deletedCategoryUnprefixedPaths
 *            snapshot of the deleted category's {@code UNPREFIXED_PATH} entries; strips matching {@code UNPREFIXED_PATH} entries from referring docs
 */
public record CategoryDeleteCascadeSource(String nodeRef, String descendantUuid,
        List<String> deletedCategoryPaths, List<String> deletedCategoryUnprefixedPaths)
{}
