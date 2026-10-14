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
package org.alfresco.indexing.shared;

import static java.util.Collections.emptyList;
import static java.util.Optional.of;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.alfresco.repo.event.v1.model.ContentInfo;
import org.alfresco.repo.event.v1.model.EventData;
import org.alfresco.repo.event.v1.model.EventType;
import org.alfresco.repo.event.v1.model.NodeResource;
import org.alfresco.repo.event.v1.model.RepoEvent;
import org.alfresco.repo.event.v1.model.UserInfo;

@SuppressWarnings({"PMD.TooManyMethods", "PMD.TooManyFields"})
public class EventGenerator
{

    private String id;
    private EventType eventType;
    private boolean hasContent;
    private boolean hasMetadata;
    private ZonedDateTime modifiedAt;
    private ZonedDateTime createdAt;
    private NodeResource resource;
    private NodeResource resourceBefore;
    private EventData<NodeResource> eventData;
    private ContentInfo contentInfo;
    private boolean hasData;
    private boolean isFolder;
    private Set<String> aspects;
    private String name;
    private String nodeType;
    private String modifierId;
    private String creatorId;
    private Map<String, Serializable> properties;
    private Set<String> deniedAuths;
    private Set<String> readerAuths;
    private List<String> primaryHierarchy;
    private List<String> previousPrimaryHierarchy;
    private String primaryAssocQName;
    private String previousPrimaryAssocQName;
    private Boolean isPermanentlyDeleted;

    public EventGenerator()
    {
        this.modifiedAt = ZonedDateTime.of(LocalDate.of(2011, 3, 13), LocalTime.NOON, ZoneId.of("UTC+8"));
        this.createdAt = ZonedDateTime.of(LocalDate.of(2011, 3, 13), LocalTime.NOON, ZoneId.of("UTC+8"));
        this.modifierId = "modifier";
        this.contentInfo = new ContentInfo("application/pdf", 12l, "UTF-8");
        this.properties = new HashMap<>();
        this.id = Integer.toString(new Random().nextInt());
        this.properties.put("cm:description", "this is a description");
        this.hasData = true;
        this.isFolder = false;
        readerAuths = Set.of("GROUP_EVERYONE");
        deniedAuths = Set.of("DENIED_USER");
    }

    public static RepoEvent<EventData<NodeResource>> generateEvent(EventType type, boolean hasContent, boolean hasMetadata)
    {
        return builder()
                .withType(type)
                .withContentSet(hasContent)
                .withMetadataSet(hasMetadata)
                .generateEvent();
    }

    public static EventGenerator builder()
    {
        return new EventGenerator();
    }

    public EventGenerator withType(EventType eventType)
    {
        this.eventType = eventType;
        return this;
    }

    public EventGenerator withPrimaryHierarchy(List<String> primaryHierarchy)
    {
        this.primaryHierarchy = primaryHierarchy;
        return this;
    }

    public EventGenerator withPreviousPrimaryHierarchy(List<String> primaryHierarchy)
    {
        this.previousPrimaryHierarchy = primaryHierarchy;
        return this;
    }

    public EventGenerator withProperty(String propertyName, Serializable propertyValue)
    {
        this.properties.put(propertyName, propertyValue);
        return this;
    }

    public EventGenerator withNodeType(String nodeType)
    {
        this.nodeType = nodeType;
        return this;
    }

    public EventGenerator withName(String name)
    {
        this.name = name;
        return this;
    }

    public EventGenerator withUserModifier(String userId)
    {
        this.modifierId = userId;
        return this;
    }

    public EventGenerator withCreatedTime(ZonedDateTime createdTime)
    {
        this.createdAt = createdTime;
        return this;
    }

    public EventGenerator withUserCreator(String userId)
    {
        this.creatorId = userId;
        return this;
    }

    public EventGenerator withMetadataSet(boolean hasMetadata)
    {
        this.hasMetadata = hasMetadata;
        return this;
    }

    public EventGenerator withContentSet(boolean hasContent)
    {
        this.hasContent = hasContent;
        return this;
    }

    public EventGenerator withIsFolder(boolean isFolder)
    {
        this.isFolder = isFolder;
        return this;
    }

    public EventGenerator withContentInfo(ContentInfo contentInfo)
    {
        this.contentInfo = contentInfo;
        return this;
    }

    public EventGenerator withModifiedTime(ZonedDateTime modifiedTime)
    {
        this.modifiedAt = modifiedTime;
        return this;
    }

    public EventGenerator withTitle(String title)
    {
        this.properties.put("cm:title", title);
        return this;
    }

    public EventGenerator withAspects(Set<String> aspects)
    {
        this.aspects = aspects;
        return this;
    }

    public EventGenerator withId(String id)
    {
        this.id = id;
        return this;
    }

    public EventGenerator withData(boolean flag)
    {
        this.hasData = flag;
        return this;
    }

    public EventGenerator withReaders(String... readerAuths)
    {
        this.readerAuths = Set.of(readerAuths);
        return this;
    }

    public EventGenerator withDenied(String... deniedAuths)
    {
        this.deniedAuths = Set.of(deniedAuths);
        return this;
    }

    public EventGenerator withPrimaryAssocQName(String primaryAssocQName)
    {
        this.primaryAssocQName = primaryAssocQName;
        return this;
    }

    public EventGenerator withPreviousPrimaryAssocQName(String primaryAssocQName)
    {
        this.previousPrimaryAssocQName = primaryAssocQName;
        return this;
    }

    /**
     * Sets the {@code isPermanentlyDeleted} flag on the generated {@link NodeResource}.
     *
     * @param isPermanentlyDeleted
     *            {@code false} for an archive (trashcan) delete, {@code true} for a purge, {@code null} to emulate a legacy producer that omits the flag
     */
    public EventGenerator withPermanentlyDeleted(Boolean isPermanentlyDeleted)
    {
        this.isPermanentlyDeleted = isPermanentlyDeleted;
        return this;
    }

    public RepoEvent<EventData<NodeResource>> generateEvent()
    {
        if (eventType == null)
        {
            throw new IllegalStateException("eventType must be defined");
        }

        populateResource();
        if (eventType == EventType.NODE_UPDATED)
        {
            populateResourceBefore();
        }

        if (hasData)
        {
            populateEventData();
        }

        return RepoEvent.<EventData<NodeResource>> builder()
                .setType(eventType.getType())
                .setData(eventData)
                .setTime(modifiedAt)
                .setId(id)
                .build();
    }


    private void populateResource()
    {
        resource = of(NodeResource.builder())
                .map(builder -> hasContent ? builder.setContent(getContentInfo()) : builder)
                .map(builder -> hasMetadata ? generateProperties(builder) : builder)
                .map(builder -> isPermanentlyDeleted != null ? builder.setIsPermanentlyDeleted(isPermanentlyDeleted) : builder)
                .get()
                .build();
    }

    private void populateResourceBefore()
    {
        resourceBefore = of(NodeResource.builder())
                .map(builder -> hasContent ? builder.setContent(getContentInfo()) : builder)
                .map(builder -> hasMetadata ? generateProperties(builder) : builder)
                .map(builder -> previousPrimaryHierarchy != null ? generateProperties(builder, previousPrimaryHierarchy) : builder)
                .map(builder -> previousPrimaryAssocQName != null ? builder.setPrimaryAssocQName(previousPrimaryAssocQName) : builder)
                .get()
                .build();
    }

    private void populateEventData()
    {
        eventData = EventData.<NodeResource> builder()
                .setResource(resource)
                .setResourceBefore(resourceBefore)
                .setResourceDeniedAuthorities(deniedAuths)
                .setResourceReaderAuthorities(readerAuths)
                .build();
    }

    private NodeResource.Builder generateProperties(NodeResource.Builder resourceBuilder)
    {
        return generateProperties(
                resourceBuilder,
                primaryHierarchy != null
                        ? primaryHierarchy
                        : emptyList());
    }

    private NodeResource.Builder generateProperties(NodeResource.Builder resourceBuilder, List<String> primaryHierarchy)
    {
        // User information
        if (creatorId != null)
        {
            resourceBuilder.setCreatedByUser(createUserInfo(creatorId));
        }

        if (modifierId != null)
        {
            resourceBuilder.setModifiedByUser(createUserInfo(modifierId));
        }

        // Date information
        resourceBuilder.setCreatedAt(createdAt);
        resourceBuilder.setModifiedAt(modifiedAt);

        // Properties
        // Date information
        resourceBuilder.setProperties(new HashMap<>(properties));
        resourceBuilder.setIsFile(true);
        resourceBuilder.setIsFolder(isFolder);

        resourceBuilder.setPrimaryAssocQName(this.primaryAssocQName);
        resourceBuilder.setPrimaryHierarchy(primaryHierarchy);

        resourceBuilder.setId(id);
        resourceBuilder.setName(name);

        // NodeType is mandatory, if that hasn't been indicated then a default value will be set
        resourceBuilder.setNodeType(nodeType);
        resourceBuilder.setAspectNames(aspects);

        resourceBuilder.setSecondaryParents(emptyList());

        return resourceBuilder;
    }


    private UserInfo createUserInfo(String id)
    {
        return new UserInfo(id, "firstname", "surname");
    }

    public ContentInfo getContentInfo()
    {
        return this.contentInfo;
    }

}
