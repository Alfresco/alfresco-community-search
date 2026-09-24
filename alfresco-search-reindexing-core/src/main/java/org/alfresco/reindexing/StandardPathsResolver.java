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
package org.alfresco.reindexing;

import static org.alfresco.database.connector.ParentChildAssociationOrdinality.PRIMARY;
import static org.alfresco.database.connector.ParentChildAssociationOrdinality.PRIMARY_AND_SECONDARY;

import java.time.ZonedDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.alfresco.database.connector.AlfrescoMetadataRepository;
import org.alfresco.database.connector.ChildAssocParams;
import org.alfresco.database.connector.ParentChildAssociationOrdinality;
import org.alfresco.database.connector.model.ChildAssocMetaData;
import org.alfresco.database.connector.model.Path;

/**
 * This component is responsible for finding all the standard paths for a node (i.e. all paths except category pseudo-paths).
 *
 * The paths are retrieved by recursively looking for childNodeAssociations in the database. The childNodeAssociations are cached in order to speed up the process.
 */
public class StandardPathsResolver
{
    private static final Logger LOGGER = LoggerFactory.getLogger(StandardPathsResolver.class);

    final AlfrescoMetadataRepository metadataRepository;

    public StandardPathsResolver(AlfrescoMetadataRepository metadataRepository)
    {
        this.metadataRepository = metadataRepository;
    }

    public Path findPrimaryPath(ChildAssocMetaData primaryParentAssociation, ZonedDateTime nodeTime)
    {
        return traverse(Set.of(primaryParentAssociation), nodeTime, true).stream().findAny()
                .orElseThrow(() -> new IllegalStateException("Failed to find primary path for node."));
    }

    /**
     * Compute the paths for the node referred to by a reindexingEvent
     *
     * @param parentAssociations
     *            parent associations from the leaf node
     * @param nodeTime
     *            node update time. used for cache validation
     */
    public Set<Path> findPaths(Set<ChildAssocMetaData> parentAssociations, ZonedDateTime nodeTime)
    {
        return traverse(parentAssociations, nodeTime, false);
    }

    /**
     * Compute the paths for a node using the node reference of the parent.
     *
     * @param standardAssociation
     *            A standard parent-child association for the node, used to get information about the child.
     * @param parentNodeRef
     *            The NodeRef of the parent node.
     * @param nodeTime
     *            The node update time (for cache validation).
     * @return The set of paths for the parent node.
     */
    public Set<Path> findPaths(String parentNodeRef, ChildAssocMetaData standardAssociation, ZonedDateTime nodeTime)
    {
        Long parentDBId = metadataRepository.getDBIdFromNodeRef(parentNodeRef);
        // Create a fake association using the given parent.
        ChildAssocMetaData pseudoAssociation = new ChildAssocMetaData();
        pseudoAssociation.setParentUuid(parentNodeRef);
        pseudoAssociation.setParentId(parentDBId);
        pseudoAssociation.setChildId(standardAssociation.getChildId());
        pseudoAssociation.setName(standardAssociation.getName());
        pseudoAssociation.setUri(standardAssociation.getUri());
        return traverse(Set.of(pseudoAssociation), nodeTime, false);
    }

    /**
     * Find the standard paths for the node.
     *
     * @param originalParentAssociations
     *            The parent-child associations for the node.
     * @param nodeTime
     *            node update time. used for cache validation
     * @param primaryAssociationOnly
     *            Set to true to only get the primary path.
     * @return A set containing the paths for the node.
     */
    private Set<Path> traverse(Set<ChildAssocMetaData> originalParentAssociations, ZonedDateTime nodeTime, boolean primaryAssociationOnly)
    {
        Set<ParentChildAssociationOrdinality> ordinalities = primaryAssociationOnly ? Set.of(PRIMARY) : PRIMARY_AND_SECONDARY;
        Set<Path> paths = new HashSet<>();
        Queue<List<ChildAssocMetaData>> partialPaths = new ArrayDeque<>();
        for (ChildAssocMetaData parentAssociation : originalParentAssociations)
        {
            if (parentAssociation.getParentId() == null)
            {
                // Special handling for the root node.
                paths.add(new Path());
            }
            else if (parentAssociation.getParentId() == -1L)
            {
                // handling for deleted parent node
                continue;
            }
            else
            {
                partialPaths.add(List.of(parentAssociation));
            }
        }
        while (!partialPaths.isEmpty())
        {
            List<ChildAssocMetaData> partialPath = partialPaths.remove();
            ChildAssocMetaData childAssocMetaData = partialPath.get(partialPath.size() - 1);

            Long parentId = childAssocMetaData.getParentId();
            Set<ChildAssocMetaData> parentAssociations = null;
            if (parentId != null)
            {
                ChildAssocParams childAssocParams = new ChildAssocParams(ordinalities, parentId, nodeTime);
                parentAssociations = metadataRepository.getChildAssocMetaData(childAssocParams);
            }
            if (parentAssociations == null || parentAssociations.isEmpty())
            {
                // The path is complete.
                paths.add(new Path(partialPath));
            }
            else
            {
                // Create new partial paths by extending the existing partial path.
                for (ChildAssocMetaData parentAssociation : parentAssociations)
                {
                    if (partialPath.contains(parentAssociation))
                    {
                        LOGGER.error("Infinite cycle found in parent hierarchy - storing finite path {}", partialPath);
                        paths.add(new Path(partialPath));
                    }
                    else
                    {
                        List<ChildAssocMetaData> newPartialPath = new ArrayList<>(partialPath);
                        newPartialPath.add(parentAssociation);
                        partialPaths.add(newPartialPath);
                    }
                }
            }
        }
        return paths;
    }
}
