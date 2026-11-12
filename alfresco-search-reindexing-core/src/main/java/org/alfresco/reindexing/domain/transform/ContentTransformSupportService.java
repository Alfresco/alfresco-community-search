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
package org.alfresco.reindexing.domain.transform;

import static java.util.Optional.of;
import static java.util.Optional.ofNullable;

import static org.alfresco.repo.event.util.DataAttributesUtils.containsNotBlankContent;

import java.util.Objects;

import org.alfresco.content.mediatype.AcceptedContentMediaTypesCache;
import org.alfresco.elasticsearch.shared.filter.ContentIndexingFilter;
import org.alfresco.indexing.shared.config.FilteringConfig;
import org.alfresco.reindexing.model.ReindexingEvent;
import org.alfresco.repo.event.v1.model.*;

/**
 * The Domain Service containing business logic deciding if content transformation is supported in the event context.
 */
public class ContentTransformSupportService
{
    private static final String IS_CONTENT_INDEXED_PROPERTY = "cm:isContentIndexed";
    private final FilteringConfig filteringConfig;
    private final AcceptedContentMediaTypesCache mediaTypesCache;
    private final ContentIndexingFilter contentIndexingFilter;

    public ContentTransformSupportService(FilteringConfig filteringConfig, AcceptedContentMediaTypesCache mediaTypesCache)
    {
        this(filteringConfig, mediaTypesCache, ContentIndexingFilter.acceptAll());
    }

    /**
     * @param contentIndexingFilter
     *            content indexing policy (MIME type / size exclusions). Applied here so every content path (tr_status marking, direct and JMS content writers) honours it.
     */
    public ContentTransformSupportService(FilteringConfig filteringConfig, AcceptedContentMediaTypesCache mediaTypesCache,
            ContentIndexingFilter contentIndexingFilter)
    {
        this.filteringConfig = filteringConfig;
        this.mediaTypesCache = mediaTypesCache;
        this.contentIndexingFilter = Objects.requireNonNull(contentIndexingFilter, "contentIndexingFilter must not be null");
    }

    /**
     * Verifies if the content transformation is supported for the given reindexing event.
     */
    public boolean isContentTransformSupported(ReindexingEvent event)
    {
        return ofNullable(event)
                .filter(reindexingEvent -> shouldContentBeIndexed(reindexingEvent.getRepoEvent()))
                .filter(reindexingEvent -> containsNotBlankContent(reindexingEvent.getData()))
                .filter(this::isContentRequestable)
                .filter(this::isContentIndexableByPolicy)
                .isPresent();
    }

    private boolean shouldContentBeIndexed(RepoEvent<EventData<NodeResource>> repoEvent)
    {
        return ofNullable(repoEvent)
                .map(RepoEvent::getData)
                .map(EventData::getResource)
                .map(NodeResource::getProperties)
                .map(props -> props.get(ContentTransformSupportService.IS_CONTENT_INDEXED_PROPERTY))
                .filter(Boolean.class::isInstance)
                .map(Boolean.class::cast)
                .orElse(true);
    }

    private boolean isContentRequestable(ReindexingEvent event)
    {
        return of(event)
                .map(ReindexingEvent::getData)
                .map(DataAttributes::getResource)
                .filter(this::isContentMediaTypeSupported)
                .map(NodeResource::getNodeType)
                .filter(filteringConfig::doesntContainContentNodeType)
                .isPresent();
    }

    private boolean isContentMediaTypeSupported(NodeResource nodeResource)
    {
        return of(nodeResource)
                .map(NodeResource::getContent)
                .map(ContentInfo::getMimeType)
                .map(mediaTypesCache::isSupported)
                .orElse(false);
    }

    private boolean isContentIndexableByPolicy(ReindexingEvent event)
    {
        NodeResource resource = event.getData().getResource();
        return contentIndexingFilter.isIndexable(resource.getId(), resource.getContent());
    }
}
