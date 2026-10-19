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
package org.alfresco.indexing.shared.utils;

import static org.alfresco.repo.event.v1.model.EventType.NODE_DELETED;

import java.util.Optional;

import lombok.experimental.UtilityClass;

import org.alfresco.repo.event.v1.model.DataAttributes;
import org.alfresco.repo.event.v1.model.NodeResource;
import org.alfresco.repo.event.v1.model.RepoEvent;

/**
 * Distinguishes the two types of {@code org.alfresco.event.node.Deleted} events introduced for the trashcan feature.
 *
 * <table>
 * <caption>Interpretation of {@code NodeResource.isPermanentlyDeleted}</caption>
 * <tr>
 * <th>Flag</th>
 * <th>Meaning</th>
 * </tr>
 * <tr>
 * <td>{@code false}</td>
 * <td>node was archived (moved to the trashcan) - metadata and content must be retained</td>
 * </tr>
 * <tr>
 * <td>{@code true}</td>
 * <td>node was purged - the document is reduced to a minimal tombstone</td>
 * </tr>
 * <tr>
 * <td>absent</td>
 * <td>legacy producer (Community ACS) - treated as a purge for backward compatibility</td>
 * </tr>
 * </table>
 */
@UtilityClass
public class DeletionTypeChecker
{
    /**
     * @return {@code true} only for a {@code NODE_DELETED} event explicitly flagged as an archive (trashcan) delete.
     */
    public static boolean isArchiveDelete(RepoEvent<?> event)
    {
        return isNodeDeleted(event) && Boolean.FALSE.equals(getIsPermanentlyDeleted(event));
    }

    private static boolean isNodeDeleted(RepoEvent<?> event)
    {
        return event != null && NODE_DELETED.getType().equals(event.getType());
    }

    private static Boolean getIsPermanentlyDeleted(RepoEvent<?> event)
    {
        return Optional.of(event)
                .map(RepoEvent::getData)
                .map(DataAttributes::getResource)
                .filter(NodeResource.class::isInstance)
                .map(NodeResource.class::cast)
                .map(NodeResource::isPermanentlyDeleted)
                .orElse(null);
    }
}
