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

import java.io.Serializable;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import org.alfresco.indexing.shared.config.FilteringConfig;
import org.alfresco.repo.event.v1.model.EventData;
import org.alfresco.repo.event.v1.model.NodeResource;
import org.alfresco.repo.event.v1.model.RepoEvent;

/**
 * Utility class containing methods to clone {@link RepoEvent} with modified state. It ensures safe operations on immutable objects.
 */
@Slf4j
@UtilityClass
public class RepoEventCloneUtils
{
    /**
     * Namespace-prefix of system properties filtered out by the repository's {@code NodePropertyFilter} ({@code sys:*}).
     */
    private static final String SYS_NAMESPACE_PREFIX = "sys:";

    /**
     * System properties retained despite the {@code sys:*} strip, mirroring the repository's {@code NodePropertyFilter.ALLOWED_PROPERTIES}. Required downstream for cascade re-indexing bookkeeping.
     */
    private static final Set<String> ALLOWED_PROPERTIES = Set.of("sys:cascadeTx", "sys:cascadeCRC");

    /**
     * Top-level properties the repository exposes as dedicated event fields and therefore removes from the properties map, mirroring the repository's {@code NodePropertyFilter.EXCLUDED_TOP_LEVEL_PROPS}. Stripping {@code cm:content} here also prevents the metadata upsert from clobbering the content indexer's extracted text when the repository emits unfiltered event2 events.
     */
    private static final Set<String> EXCLUDED_TOP_LEVEL_PROPS = Set.of(
            "cm:name", "cm:modifier", "cm:modified", "cm:creator", "cm:created", "cm:content");

    /**
     * Method that clones event with state extended by given additional {@link NodeResource} properties.
     */
    public static RepoEvent<EventData<NodeResource>> withAdditionalProps(
            RepoEvent<EventData<NodeResource>> srcEvent,
            Map<String, Serializable> additionalProperties)
    {
        return Optional.of(srcEvent)
                .filter(RepoEventCloneUtils::hasProperties)
                .map(propAdder(additionalProperties))
                .orElse(srcEvent);
    }

    /**
     * Method that clones event with state modified by removing given {@link NodeResource} properties.
     */
    public static RepoEvent<EventData<NodeResource>> withFilteredProps(
            RepoEvent<EventData<NodeResource>> srcEvent,
            Collection<String> propsToRemove)
    {
        return Optional.of(srcEvent)
                .filter(RepoEventCloneUtils::hasProperties)
                .map(propRemover(propsToRemove))
                .orElse(srcEvent);
    }

    private static UnaryOperator<RepoEvent<EventData<NodeResource>>> propAdder(Map<String, Serializable> additionalProperties)
    {
        return event -> {
            log.trace("Adding new properties: srcEvent={}, additionalProperties={}", event, additionalProperties);
            var props = retrieveProps(event);
            props.putAll(additionalProperties);
            return buildEvent(event, props);
        };
    }

    private static UnaryOperator<RepoEvent<EventData<NodeResource>>> propRemover(Collection<String> propsToRemove)
    {
        return event -> {
            log.trace("Removing properties: srcEvent={}, propsToRemove={}", event, propsToRemove);
            var props = retrieveProps(event);
            boolean wasFiltered = props.keySet().removeIf(key -> FilteringConfig.matchesAny(propsToRemove, key));
            wasFiltered |= props.keySet().removeIf(RepoEventCloneUtils::isRepositoryFilteredProperty);
            if (wasFiltered)
            {
                return buildEvent(event, props);
            }
            return event;
        };
    }

    /**
     * Method that clones event with state modified by removing matching {@link NodeResource} aspects. Supports exact and namespace-wildcard ({@code sys:*}) patterns via {@link FilteringConfig#matchesAny}.
     */
    public static RepoEvent<EventData<NodeResource>> withFilteredAspects(
            RepoEvent<EventData<NodeResource>> srcEvent,
            Collection<String> aspectsToRemove)
    {
        return Optional.of(srcEvent)
                .filter(RepoEventCloneUtils::hasAspects)
                .map(aspectRemover(aspectsToRemove))
                .orElse(srcEvent);
    }

    private static UnaryOperator<RepoEvent<EventData<NodeResource>>> aspectRemover(Collection<String> aspectsToRemove)
    {
        return event -> {
            log.trace("Removing aspects: srcEvent={}, aspectsToRemove={}", event, aspectsToRemove);
            var aspects = retrieveAspects(event);
            boolean wasFiltered = aspects.removeIf(aspect -> FilteringConfig.matchesAny(aspectsToRemove, aspect));
            if (wasFiltered)
            {
                return buildEventWithAspects(event, aspects);
            }
            return event;
        };
    }

    /**
     * Mirrors the repository {@code NodePropertyFilter}: removes the top-level properties exposed as dedicated event fields, and all {@code sys:*} properties except the allow-listed ones.
     */
    private static boolean isRepositoryFilteredProperty(String key)
    {
        if (key == null)
        {
            return false;
        }
        return EXCLUDED_TOP_LEVEL_PROPS.contains(key)
                || (key.startsWith(SYS_NAMESPACE_PREFIX) && !ALLOWED_PROPERTIES.contains(key));
    }

    private static Map<String, Serializable> retrieveProps(RepoEvent<EventData<NodeResource>> repoEvent)
    {
        return new HashMap<>(repoEvent.getData().getResource().getProperties());
    }

    private static Set<String> retrieveAspects(RepoEvent<EventData<NodeResource>> repoEvent)
    {
        return new HashSet<>(repoEvent.getData().getResource().getAspectNames());
    }

    private static RepoEvent<EventData<NodeResource>> buildEvent(
            RepoEvent<EventData<NodeResource>> repoEvent,
            Map<String, Serializable> props)
    {
        var nodeResource = repoEvent.getData().getResource().toBuilder()
                .setProperties(props)
                .build();
        var eventData = repoEvent.getData().toBuilder()
                .setResource(nodeResource)
                .build();
        var newRepoEvent = repoEvent.toBuilder()
                .setData(eventData)
                .build();
        log.trace("RepoEvent was updated: updatedEvent={}", repoEvent);
        return newRepoEvent;
    }

    private static RepoEvent<EventData<NodeResource>> buildEventWithAspects(
            RepoEvent<EventData<NodeResource>> repoEvent,
            Set<String> aspects)
    {
        var nodeResource = repoEvent.getData().getResource().toBuilder()
                .setAspectNames(aspects)
                .build();
        var eventData = repoEvent.getData().toBuilder()
                .setResource(nodeResource)
                .build();
        var newRepoEvent = repoEvent.toBuilder()
                .setData(eventData)
                .build();
        log.trace("RepoEvent aspects were updated: updatedEvent={}", newRepoEvent);
        return newRepoEvent;
    }

    private static boolean hasProperties(RepoEvent<EventData<NodeResource>> event)
    {
        return Optional.of(event)
                .map(RepoEvent::getData)
                .map(EventData::getResource)
                .map(NodeResource::getProperties)
                .isPresent();
    }

    private static boolean hasAspects(RepoEvent<EventData<NodeResource>> event)
    {
        return Optional.of(event)
                .map(RepoEvent::getData)
                .map(EventData::getResource)
                .map(NodeResource::getAspectNames)
                .isPresent();
    }
}
