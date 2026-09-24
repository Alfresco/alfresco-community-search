/*
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
package org.alfresco.indexing.shared.extractor;

import static java.util.Collections.emptyList;
import static java.util.Optional.of;
import static java.util.Optional.ofNullable;
import static java.util.function.Predicate.not;
import static java.util.stream.Collectors.toSet;

import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.ASPECT;
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.CM_CONTENT_TR_STATUS;
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.CONTENT_ENCODING;
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.CONTENT_MIME_TYPE;
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.CONTENT_SIZE;
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.CREATION_DATE_FIELD;
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.DENIED;
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.MODIFICATION_DATE_FIELD;
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.NAME;
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.OWNER;
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.OWNER_PROPERTY_NAME;
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.PARENT;
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.PRIMARY_HIERARCHY;
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.PRIMARY_PARENT;
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.PROPERTIES;
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.READER;
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.TYPE;
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.USER_CREATOR;
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.USER_MODIFIER;
import static org.alfresco.indexing.shared.utils.ResourceTools.categoriesForNode;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import lombok.RequiredArgsConstructor;

import org.alfresco.indexing.shared.ElasticsearchFieldsPostProcessor;
import org.alfresco.indexing.shared.PropertiesDeterminingStrategy;
import org.alfresco.repo.event.v1.model.ContentInfo;
import org.alfresco.repo.event.v1.model.EventData;
import org.alfresco.repo.event.v1.model.NodeResource;
import org.alfresco.repo.event.v1.model.RepoEvent;
import org.alfresco.repo.event.v1.model.UserInfo;

@RequiredArgsConstructor
public class DefaultEncodedFieldsExtractor implements EncodedFieldsExtractor
{
    private final ElasticsearchFieldsPostProcessor elasticsearchFieldsPostProcessor;
    private final PropertiesDeterminingStrategy propertiesDeterminingStrategy;

    @Override
    public EncodedFields extract(RepoEvent<?> event)
    {
        var encodedFields = new EncodedFields();

        NodeResource resource = (NodeResource) event.getData().getResource();

        addType(resource, encodedFields);
        addPrimaryHierarchy(resource.getPrimaryHierarchy(), encodedFields);
        addParents(resource, encodedFields);
        addAclInformation(event, encodedFields);
        addUserInformation(resource, encodedFields);
        addDateInformation(resource, encodedFields);
        addName(resource, encodedFields);
        addProperties(resource, encodedFields);
        addAspects(resource, encodedFields);
        addContentInfo(resource, encodedFields);

        elasticsearchFieldsPostProcessor.postProcessFields(encodedFields);

        return encodedFields;
    }

    private void addContentInfo(NodeResource resource, EncodedFields encodedFields)
    {
        ContentInfo content = resource.getContent();
        if (content != null)
        {
            encodedFields.put(CONTENT_MIME_TYPE, content.getMimeType());
            encodedFields.put(CONTENT_SIZE, content.getSizeInBytes());
            encodedFields.put(CONTENT_ENCODING, content.getEncoding());
        }
    }

    private void addPrimaryHierarchy(List<String> primaryHierarchy, EncodedFields encodedFields)
    {
        ofNullable(primaryHierarchy)
                .filter(not(Collection::isEmpty))
                .ifPresent(hierarchy -> {
                    encodedFields.put(PRIMARY_HIERARCHY, hierarchy);
                    hierarchy.stream().findFirst().ifPresent(primaryParent -> encodedFields.put(PRIMARY_PARENT, primaryParent));
                });
    }

    private void addParents(NodeResource resource, EncodedFields encodedFields)
    {
        addParents(parentsOf(resource), encodedFields);
    }

    private Set<String> parentsOf(NodeResource resource)
    {
        return Stream.concat(standardParentsOf(resource).stream(), categoriesForNode(resource).stream())
                .collect(toSet());
    }

    private Set<String> standardParentsOf(NodeResource nodeResource)
    {
        return Stream.concat(
                ofNullable(nodeResource.getPrimaryHierarchy()).orElse(emptyList()).stream().findFirst().stream(),
                ofNullable(nodeResource.getSecondaryParents()).orElse(emptyList()).stream())
                .collect(toSet());
    }

    private void addParents(Collection<String> allParents, EncodedFields encodedFields)
    {
        of(allParents)
                .filter(not(Collection::isEmpty))
                .ifPresent(parents -> encodedFields.put(PARENT, parents));
    }

    private void addType(NodeResource resource, EncodedFields encodedFields)
    {
        encodedFields.put(TYPE, resource.getNodeType());
    }

    private void addAspects(NodeResource resource, EncodedFields encodedFields)
    {
        encodedFields.put(ASPECT, resource.getAspectNames());
    }

    private void addProperties(NodeResource resource, EncodedFields encodedFields)
    {
        Map<String, Serializable> properties = propertiesDeterminingStrategy.determineProperties(resource.getProperties(), resource.getLocalizedProperties());
        if (properties != null)
        {
            properties.remove(CM_CONTENT_TR_STATUS); // it's not a property of event, but internal field used for content transformation tracking (See RepoEventTrStatusAdderProcessor)
            properties.forEach(encodedFields::put);
            getOwner(properties, resource).ifPresent(owner -> encodedFields.put(OWNER, owner));
            encodedFields.put(PROPERTIES, properties.keySet());
        }
    }

    private void addDateInformation(NodeResource resource, EncodedFields encodedFields)
    {

        ofNullable(resource.getCreatedAt()).ifPresent(
                creationDate -> encodedFields.put(CREATION_DATE_FIELD, creationDate));

        ofNullable(resource.getModifiedAt()).ifPresent(
                modificationDate -> encodedFields.put(MODIFICATION_DATE_FIELD, modificationDate));

    }

    private void addUserInformation(NodeResource resource, EncodedFields encodedFields)
    {
        ofNullable(resource.getCreatedByUser())
                .ifPresent(creator -> encodedFields.put(USER_CREATOR, creator.getId()));

        ofNullable(resource.getModifiedByUser())
                .ifPresent(modifier -> encodedFields.put(USER_MODIFIER, modifier.getId()));
    }

    private void addName(NodeResource resource, EncodedFields encodedFields)
    {
        encodedFields.put(NAME, resource.getName());
    }

    private Optional<Serializable> getOwner(Map<String, Serializable> properties, NodeResource resource)
    {
        return ofNullable(properties.get(OWNER_PROPERTY_NAME))
                .or(() -> ofNullable(resource.getCreatedByUser())
                        .map(UserInfo::getId));
    }

    private void addAclInformation(RepoEvent<?> event, EncodedFields encodedFields)
    {
        EventData<?> enterpriseEventData = (EventData<?>) event.getData();
        encodedFields.put(READER, enterpriseEventData.getResourceReaderAuthorities());
        encodedFields.put(DENIED, enterpriseEventData.getResourceDeniedAuthorities());
    }
}
