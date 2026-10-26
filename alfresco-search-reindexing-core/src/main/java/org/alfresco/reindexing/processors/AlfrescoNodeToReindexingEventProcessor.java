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

import static java.time.Instant.ofEpochMilli;
import static java.time.ZonedDateTime.ofInstant;
import static java.util.Optional.ofNullable;
import static java.util.function.Predicate.not;

import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.ACCESS_DATE_FIELD;
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.TYPE_DELETED;

import java.io.Serializable;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.infrastructure.item.ItemProcessor;

import org.alfresco.database.connector.model.AccessControlEntry;
import org.alfresco.database.connector.model.AccessControlEntryKey;
import org.alfresco.database.connector.model.AlfrescoNode;
import org.alfresco.database.connector.model.ChildAssocMetaData;
import org.alfresco.database.connector.model.ContentMetadata;
import org.alfresco.elasticsearch.shared.ElasticsearchConstants;
import org.alfresco.metrics.ReindexingMetrics;
import org.alfresco.reindexing.config.PrefixNamespaceConfig;
import org.alfresco.reindexing.config.UnresolvedNamespaceAction;
import org.alfresco.reindexing.model.ArchivedOriginalParentAssoc;
import org.alfresco.reindexing.model.ReindexingEvent;
import org.alfresco.repo.event.v1.model.ContentInfo;
import org.alfresco.repo.event.v1.model.EventData;
import org.alfresco.repo.event.v1.model.EventType;
import org.alfresco.repo.event.v1.model.NodeResource;
import org.alfresco.repo.event.v1.model.RepoEvent;
import org.alfresco.repo.event.v1.model.UserInfo;

/**
 * {@link ItemProcessor} that converts an {@link AlfrescoNode} to a {@link RepoEvent}
 */
public class AlfrescoNodeToReindexingEventProcessor extends AlfrescoNodeProcessor implements ItemProcessor<AlfrescoNode, ReindexingEvent>
{

    private static final Logger LOGGER = LoggerFactory.getLogger(AlfrescoNodeToReindexingEventProcessor.class);

    private static final String ARCHIVE_STORE_PROTOCOL = "archive";
    private static final String SYSTEM_MODEL_URI = "http://www.alfresco.org/model/system/1.0";
    private static final String ARCHIVED_ORIGINAL_PARENT_ASSOC = "archivedOriginalParentAssoc";

    /** Optional. Nullable in tests; wired in production via SharedBatchProcessingConfig. */
    private final UnresolvedNamespaceListener unresolvedNamespaceListener;
    private final UnresolvedNamespaceAction onUnresolvedNamespace;

    private final Set<String> nonReadPermissions;

    public AlfrescoNodeToReindexingEventProcessor(PrefixNamespaceConfig prefixNamespaceConfig, Set<String> nonReadPermissions)
    {
        this(prefixNamespaceConfig, null, null, UnresolvedNamespaceAction.FILTER, nonReadPermissions);
    }

    public AlfrescoNodeToReindexingEventProcessor(PrefixNamespaceConfig prefixNamespaceConfig,
            ReindexingMetrics reindexingMetrics,
            UnresolvedNamespaceListener unresolvedNamespaceListener,
            UnresolvedNamespaceAction onUnresolvedNamespace,
            Set<String> nonReadPermissions)
    {
        super(prefixNamespaceConfig, reindexingMetrics);
        this.unresolvedNamespaceListener = unresolvedNamespaceListener;
        this.onUnresolvedNamespace = onUnresolvedNamespace;
        this.nonReadPermissions = nonReadPermissions;
    }

    @Override
    public ReindexingEvent process(final AlfrescoNode node)
    {
        try
        {
            return buildEvent(node);
        }
        catch (UnresolvedNamespaceException expected)
        {
            // Already reported at WARN with the URI, the kind and the name, and the configured
            // policy chose to fail the item. A second line with a stack trace is noise.
            throw expected;
        }
        catch (RuntimeException exception)
        {
            LOGGER.error("impossible to get event for node "
                    + node.getId() + ". Error: " + exception.getMessage(),
                    exception);

            throw exception;
        }
    }

    /** The node is dropped downstream, so record it here while the source node is still to hand. */
    private void reportLostNode(AlfrescoNode node)
    {
        LOGGER.warn("Node {} will not be indexed: its type namespace has no prefix mapping", node.getId());

        if (unresolvedNamespaceListener != null)
        {
            unresolvedNamespaceListener.onUnresolvedNamespace(node.getId(), node.getNodeRef(),
                    node.getTimestamp(), node.getType().getUri(), node.getType().getLocalName());
        }

        if (onUnresolvedNamespace != UnresolvedNamespaceAction.FILTER)
        {
            throw new UnresolvedNamespaceException(node.getId(), node.getType().getUri(),
                    node.getType().getLocalName(), onUnresolvedNamespace == UnresolvedNamespaceAction.STOP);
        }
    }

    private ReindexingEvent buildEvent(final AlfrescoNode node)
    {

        // A delete needs no type, properties or aspects, so resolve nothing that could fail.
        if (node.hasBeenMarkedAsDeleted())
        {
            return buildDeleteEvent(node);
        }

        NodeResource.Builder nodeResourceBuilder = NodeResource.builder();

        Map<String, Serializable> properties = getProperties(node);
        Map<String, Map<String, String>> localizedProperties = getLocalizedProperties(node);

        if (isNotIndexed(properties))
        {
            if (LOGGER.isTraceEnabled())
            {
                LOGGER.trace("Reindex emitting DELETE for node {} (cm:isIndexed=false)", node.getId());
            }
            return buildDeleteEvent(node);
        }

        Set<String> aspects = getAspects(node);

        if (node.getType() != null)
        {
            getPrefixedTypeName(node.getType())
                    .ifPresentOrElse(nodeResourceBuilder::setNodeType,
                            // An unset type is discarded downstream by the node-type filter, so say so here.
                            () -> reportLostNode(node));
        }

        ofNullable(properties.get(ElasticsearchConstants.NAME)).ifPresent(name -> {
            nodeResourceBuilder.setName((String) name);
            properties.remove(ElasticsearchConstants.NAME);
        });

        ofNullable(node.getAccessed()).ifPresent(accessed -> properties.put(ACCESS_DATE_FIELD, accessed));

        ofNullable(properties.get(ElasticsearchConstants.CONTENT_ATTRIBUTE_NAME))
                .map(content -> (ContentMetadata) content)
                .ifPresent(content -> {
                    nodeResourceBuilder.setContent(
                            new ContentInfo(content.getMimetypeStr(), content.getContentSize(), content.getEncoding()));
                    properties.remove(ElasticsearchConstants.CONTENT_ATTRIBUTE_NAME);
                });

        nodeResourceBuilder.setId(node.getNodeRef())
                .setModifiedAt(node.getModifiedAt())
                .setModifiedByUser(userInfo(node.getModifier()))
                .setProperties(properties)
                .setLocalizedProperties(localizedProperties)
                .setAspectNames(aspects)
                .setCreatedAt(node.getCreatedAt())
                .setCreatedByUser(userInfo(node.getCreator()));

        if (isArchived(node))
        {
            nodeResourceBuilder.setIsPermanentlyDeleted(false);
        }

        var eventData = EventData.<NodeResource> builder()
                .setResource(nodeResourceBuilder.build())
                .setResourceReaderAuthorities(getResourceReaderAuthorities(node))
                .setResourceDeniedAuthorities(getResourceDeniedAuthorities(node))
                .build();

        ZonedDateTime eventTimestamp = ofInstant(ofEpochMilli(node.getTimestamp()), ZoneId.systemDefault());

        var repoEvent = RepoEvent.<EventData<NodeResource>> builder()
                .setData(eventData)
                .setTime(eventTimestamp)
                .setId("reindex")
                .setType((isArchived(node) ? EventType.NODE_DELETED : EventType.NODE_CREATED).getType())
                .build();

        ChildAssocMetaData primaryParent = ofNullable(node.getPrimaryParentAssociation())
                .orElse(ChildAssocMetaData.createRootNode(node.getId()));
        return ReindexingEvent.builder()
                .withRepoEvent(repoEvent)
                .withPrimaryParent(primaryParent)
                .withSecondaryParents(node.getSecondaryParentAssociations())
                .withNodeDbId(node.getId())
                .withArchivedOriginalParentAssoc(isArchived(node) ? archivedOriginalParentAssoc(node, properties) : null)
                .build();
    }

    private ArchivedOriginalParentAssoc archivedOriginalParentAssoc(AlfrescoNode node, Map<String, Serializable> properties)
    {
        return getPrefixedPropertyName(SYSTEM_MODEL_URI, ARCHIVED_ORIGINAL_PARENT_ASSOC)
                .map(properties::get)
                .flatMap(ArchivedOriginalParentAssoc::parse)
                .orElse(null);
    }

    private UserInfo userInfo(String userId)
    {
        return userId == null || userId.isBlank() ? null : new UserInfo(userId, null, null);
    }

    /**
     * A node is archived (moved to the trashcan) when it lives in the archive store, as reported by its store protocol.
     */
    private boolean isArchived(AlfrescoNode node)
    {
        return ARCHIVE_STORE_PROTOCOL.equals(node.getProtocol());
    }

    private ReindexingEvent buildDeleteEvent(AlfrescoNode node)
    {
        NodeResource.Builder nodeResourceBuilder = NodeResource.builder()
                .setId(node.getNodeRef())
                .setNodeType(TYPE_DELETED)
                .setIsPermanentlyDeleted(true);
        var repoEvent = RepoEvent.<EventData<NodeResource>> builder()
                .setData(EventData.<NodeResource> builder()
                        .setResource(nodeResourceBuilder.build())
                        .build())
                .setTime(ofInstant(ofEpochMilli(node.getTimestamp()), ZoneId.systemDefault()))
                .setId(UUID.randomUUID().toString())
                .setType(EventType.NODE_DELETED.getType())
                .build();
        return ReindexingEvent.builder()
                .withRepoEvent(repoEvent)
                .withNodeDbId(node.getId())
                .build();
    }

    private Set<String> getResourceReaderAuthorities(AlfrescoNode node)
    {
        return ofNullable(node.getAccessControlList())
                .map(accessControlList -> accessControlList.stream()
                        .filter(AccessControlEntry::getAllowed)
                        .filter(not(this::isNonReadPermission))
                        .map(AccessControlEntry::getAccessControlEntryKey)
                        .map(AccessControlEntryKey::getAuthority)
                        .collect(Collectors.toSet()))
                .orElse(Collections.emptySet());
    }

    private Set<String> getResourceDeniedAuthorities(AlfrescoNode node)
    {
        return ofNullable(node.getAccessControlList())
                .map(accessControlList -> accessControlList.stream()
                        .filter(not(AccessControlEntry::getAllowed))
                        .filter(not(this::isNonReadPermission))
                        .map(AccessControlEntry::getAccessControlEntryKey)
                        .map(AccessControlEntryKey::getAuthority)
                        .collect(Collectors.toSet()))
                .orElse(Collections.emptySet());
    }

    private boolean isNonReadPermission(AccessControlEntry ace)
    {
        return nonReadPermissions != null && nonReadPermissions.contains(ace.getPermission());
    }

    private Boolean isNotIndexed(Map<String, Serializable> properties)
    {
        return !(Boolean) properties.getOrDefault("cm:isIndexed", Boolean.TRUE);
    }
}
