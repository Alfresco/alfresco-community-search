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
package org.alfresco.reindexing.processors;

import static java.util.Collections.emptySet;
import static java.util.Optional.ofNullable;

import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.TYPE_DELETED;
import static org.alfresco.indexing.shared.utils.ResourceTools.categoriesForNode;

import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import com.google.common.collect.Sets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.infrastructure.item.ItemProcessor;

import org.alfresco.database.connector.model.ChildAssocMetaData;
import org.alfresco.database.connector.model.Path;
import org.alfresco.reindexing.StandardPathsResolver;
import org.alfresco.reindexing.config.ReindexingCoreProperties;
import org.alfresco.reindexing.model.ReindexingEvent;
import org.alfresco.repo.event.v1.model.EventData;
import org.alfresco.repo.event.v1.model.NodeResource;

public class ReindexEventPathAdderProcessor implements ItemProcessor<ReindexingEvent, ReindexingEvent>
{

    private static final Logger LOGGER = LoggerFactory.getLogger(ReindexEventPathAdderProcessor.class);

    private final StandardPathsResolver pathResolver;
    private final ReindexingCoreProperties elasticsearchReindexProperties;

    public ReindexEventPathAdderProcessor(
            StandardPathsResolver pathResolver,
            ReindexingCoreProperties elasticsearchReindexProperties)
    {
        this.pathResolver = pathResolver;
        this.elasticsearchReindexProperties = elasticsearchReindexProperties;
    }

    @Override
    public ReindexingEvent process(ReindexingEvent item)
    {
        EventData<NodeResource> data = item.getData();
        NodeResource resource = data.getResource();

        if (elasticsearchReindexProperties.isPathIndexingEnabled() && !resource.getNodeType().equals(TYPE_DELETED))
        {
            LOGGER.debug("Adding paths for {}", resource);

            Optional<ChildAssocMetaData> archivedOriginalParentOpt = ofNullable(item.getArchivedOriginalParentAssoc())
                    .flatMap(original -> pathResolver.findOriginalParentAssociation(original, item.getNodeDbId()));
            ChildAssocMetaData primaryParent = archivedOriginalParentOpt.orElse(item.getPrimaryParent());
            Set<ChildAssocMetaData> secondaryParents = archivedOriginalParentOpt.isPresent() ? emptySet() : item.getSecondaryParents();

            Path primaryPath = pathResolver.findPrimaryPath(primaryParent, item.getTime())
                    .orElseThrow(() -> new IllegalStateException("Failed to find primary path for node " + item.getNodeDbId()));
            Set<Path> allPaths = pathResolver.findPaths(Sets.union(Set.of(primaryParent), secondaryParents), item.getTime());

            // Pseudo-paths for categories, ending with the same association as the real paths.
            Set<Path> categoryPaths = getCategoryPaths(resource, primaryParent, item.getTime());
            Set<Path> paths = Sets.union(allPaths, categoryPaths);

            return ReindexingEvent.builder()
                    .withRepoEvent(item.getRepoEvent())
                    .withPrimaryPath(primaryPath)
                    .withAllPaths(paths)
                    .withNodeDbId(item.getNodeDbId())
                    .build();
        }

        return item;
    }

    /**
     * Get the paths via categories from an event.
     *
     * @param nodeResource
     *            The node information from an event.
     * @param primaryParent
     *            The node's primary parent association.
     * @param nodeTime
     *            The node update time.
     * @return The set of paths via categories to the node.
     */
    Set<Path> getCategoryPaths(NodeResource nodeResource, ChildAssocMetaData primaryParent, ZonedDateTime nodeTime)
    {
        if (nodeResource == null || nodeResource.getProperties() == null)
        {
            return emptySet();
        }
        if (primaryParent == null)
        {
            throw new IllegalStateException("Failed to retrieve DB id for node " + nodeResource.getId());
        }

        Set<Path> categoryPaths = new HashSet<>();
        for (String categoryId : categoriesForNode(nodeResource))
        {
            Set<Path> paths = pathResolver.findPaths(categoryId, primaryParent, nodeTime);
            categoryPaths.addAll(paths);
        }

        return categoryPaths;
    }
}
