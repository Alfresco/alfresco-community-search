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

import static java.util.Collections.emptyList;
import static java.util.Collections.emptySet;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toSet;

import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.CM_CATEGORIES;
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.CM_CATEGORY;
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.CM_CONTENT_TR_STATUS;
import static org.alfresco.repo.event.v1.model.EventType.NODE_DELETED;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

import lombok.experimental.UtilityClass;
import org.springframework.util.CollectionUtils;

import org.alfresco.indexing.shared.TransformStatus;
import org.alfresco.repo.event.v1.model.DataAttributes;
import org.alfresco.repo.event.v1.model.EventType;
import org.alfresco.repo.event.v1.model.NodeResource;
import org.alfresco.repo.event.v1.model.RepoEvent;
import org.alfresco.repo.event.v1.model.Resource;

@UtilityClass
public class ResourceTools
{
    public static Optional<String> extractTransformStatus(RepoEvent<? extends DataAttributes<? extends Resource>> repoEvent)
    {
        return ofNullable(repoEvent)
                .map(RepoEvent::getData)
                .map(DataAttributes::getResource)
                .filter(NodeResource.class::isInstance)
                .map(NodeResource.class::cast)
                .map(NodeResource::getProperties)
                .map(props -> props.get(CM_CONTENT_TR_STATUS))
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .filter(TransformStatus::isValidStatus);
    }

    public static boolean isCategoryDelete(final RepoEvent<?> event)
    {
        final NodeResource resource = (NodeResource) event.getData().getResource();
        return isEventOfType(event, NODE_DELETED) && CM_CATEGORY.equals(resource.getNodeType());
    }

    /**
     * Check if categories for the node were updated.
     *
     * @param event
     *            incoming repo event
     * @return
     * 
     *         <pre>
     *         true
     *         </pre>
     * 
     *         if the event states that categories actually changed for node resource.
     */
    public static boolean wereCategoriesUpdated(final RepoEvent<?> event)
    {
        return wasFieldUpdated(event, ResourceTools::categoriesForNode);
    }

    /**
     * Check if the primary hierarchy was updated. This will happen if the event corresponds to a rename or move of a node.
     *
     * @param event
     *            incoming repo event
     * @return
     * 
     *         <pre>
     *         true
     *         </pre>
     * 
     *         if the event states that primary hierarchies actually changed for node resource.
     */
    public static boolean wasPrimaryHierarchyUpdated(final RepoEvent<?> event)
    {
        final boolean wasPrimaryHierarchyUpdated = wasFieldUpdated(event, NodeResource::getPrimaryHierarchy);
        final boolean wasNameUpdated = wasFieldUpdated(event, NodeResource::getPrimaryAssocQName) || wasFieldUpdated(event, NodeResource::getName);
        return wasPrimaryHierarchyUpdated || wasNameUpdated;
    }

    public static boolean wereSecondaryParentsUpdated(final RepoEvent<?> event)
    {
        return wasFieldUpdated(event, ResourceTools::getSecondaryParents);
    }

    public static boolean isEventOfType(final RepoEvent<?> event, final EventType type)
    {
        return type.getType().equals(event.getType());
    }

    /**
     * Get the categories that a node is assigned to.
     *
     * @param nodeResource
     *            Contains the properties for the node.
     * @return The set of node references strings for the categories.
     */
    public static Set<String> categoriesForNode(NodeResource nodeResource)
    {
        if (nodeResource == null)
        {
            return emptySet();
        }
        Map<String, ?> properties = nodeResource.getProperties();
        if (properties == null || properties.get(CM_CATEGORIES) == null)
        {
            return emptySet();
        }
        Object categories = properties.get(CM_CATEGORIES);
        return categoriesFromPropertyValue(categories).collect(toSet());
    }

    public static boolean wasFieldUpdated(final RepoEvent<?> event, final Function<NodeResource, ?> fieldGetter)
    {
        if (isEventOfType(event, EventType.NODE_CREATED))
        {
            final var resource = (NodeResource) event.getData().getResource();
            final var fieldValue = fieldGetter.apply(resource);
            return isNotEmpty(fieldValue);
        }
        else if (isEventOfType(event, EventType.NODE_UPDATED))
        {
            final var resource = (NodeResource) event.getData().getResource();
            final var resourceBefore = (NodeResource) event.getData().getResourceBefore();
            final var fieldValue = fieldGetter.apply(resource);
            final var fieldValueBefore = fieldGetter.apply(resourceBefore);
            return hasActuallyChanged(fieldValue, fieldValueBefore);
        }
        else
        {
            return false;
        }
    }

    private static List<String> getSecondaryParents(final NodeResource resource)
    {
        return CollectionUtils.isEmpty(resource.getSecondaryParents()) ? emptyList() : resource.getSecondaryParents();
    }

    /**
     * Tests whether a node resource field actually holds a value. A {@code null} value, a blank string and an empty collection or map all count as empty.
     */
    private static boolean isNotEmpty(Object value)
    {
        if (value == null)
        {
            return false;
        }
        if (value instanceof String string)
        {
            return !string.isBlank();
        }
        if (value instanceof Collection<?> collection)
        {
            return !collection.isEmpty();
        }
        if (value instanceof Map<?, ?> map)
        {
            return !map.isEmpty();
        }
        return true;
    }

    private static boolean hasActuallyChanged(Object fieldValue, Object fieldValueBefore)
    {
        return fieldValueBefore != null && !Objects.equals(fieldValue, fieldValueBefore);
    }

    @SuppressWarnings("unchecked")
    private static Stream<String> categoriesFromPropertyValue(Object categories)
    {
        if (categories instanceof String)
        {
            return Stream.of((String) categories);
        }
        if (categories instanceof Map)
        {
            return Stream.of((String) ((Map<?, ?>) categories).get("id"));
        }
        if (categories instanceof Collection)
        {
            Collection<?> categoryNodeRefs = (Collection<?>) categories;
            return categoryNodeRefs.stream().flatMap(ResourceTools::categoriesFromPropertyValue);
        }
        throw new UnsupportedOperationException("Unsupported category property value: " + categories);
    }
}
