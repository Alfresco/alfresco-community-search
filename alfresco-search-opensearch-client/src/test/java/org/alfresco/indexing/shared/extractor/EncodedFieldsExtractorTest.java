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

import static org.assertj.core.api.Assertions.assertThat;

import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.ALIVE;
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.ASPECT;
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.CM_CONTENT_TR_STATUS;
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.CONTENT_ENCODING;
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.CONTENT_MIME_TYPE;
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.CONTENT_SIZE;
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.CREATION_DATE_FIELD;
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.METADATA_INDEXING_LAST_UPDATE;
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.MODIFICATION_DATE_FIELD;
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.NAME;
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.OWNER;
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.OWNER_PROPERTY_NAME;
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.PRIMARY_HIERARCHY;
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.PRIMARY_PARENT;
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.PROPERTIES;
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.TYPE;
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.USER_CREATOR;
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.USER_MODIFIER;
import static org.alfresco.elasticsearch.shared.translator.AlfrescoQualifiedNameTranslator.encode;
import static org.alfresco.indexing.shared.TransformStatus.NO_TRANSFORM;

import java.io.Serializable;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.alfresco.indexing.shared.PropertiesDeterminingStrategy;
import org.alfresco.indexing.shared.multilingual.PropertiesPriorityBasedDeterminingStrategy;
import org.alfresco.repo.event.v1.model.ContentInfo;
import org.alfresco.repo.event.v1.model.EventData;
import org.alfresco.repo.event.v1.model.EventType;
import org.alfresco.repo.event.v1.model.NodeResource;
import org.alfresco.repo.event.v1.model.RepoEvent;
import org.alfresco.repo.event.v1.model.UserInfo;

class EncodedFieldsExtractorTest
{
    private static final String PARENT_ID = "12341234-1234-1234-1234-123412341234";
    private static final String GRANDPARENT_ID = "56785678-5678-5678-5678-567856785678";
    private EncodedFieldsExtractor encodedFieldsExtractor;

    @BeforeEach
    public void initTest()
    {
        PropertiesDeterminingStrategy propertiesDeterminingStrategy = new PropertiesPriorityBasedDeterminingStrategy(new String[0]);
        encodedFieldsExtractor = new DefaultEncodedFieldsExtractor(fields -> {}, propertiesDeterminingStrategy);
    }

    @Test
    public void should_extractExpectedFieldsFromEvent()
    {
        ZonedDateTime createdTime = ZonedDateTime.now().minusMonths(1);
        ZonedDateTime modifiedTime = ZonedDateTime.now().minusDays(1);
        Map<String, Serializable> properties = new HashMap<>();
        properties.put("cm:description", "this is a description");
        properties.put("cm:title", "my title");
        NodeResource resource = NodeResource.builder()
                .setId("id0")
                .setName("test")
                .setNodeType("cm:content")
                .setModifiedByUser(user("user_modifier"))
                .setCreatedByUser(user("user_creator"))
                .setAspectNames(Set.of("aspect1", "aspect2"))
                .setModifiedAt(modifiedTime)
                .setCreatedAt(createdTime)
                .setContent(new ContentInfo("application/pdf", 12L, "UTF-8"))
                .setPrimaryHierarchy(List.of(PARENT_ID, GRANDPARENT_ID))
                .setProperties(properties)
                .build();
        RepoEvent generatedEvent = repoEventWithResource(resource);

        Map<String, Object> fieldsMapping = encodedFieldsExtractor.extract(generatedEvent).getEncodedMapping();

        FieldsEncoder fieldsEncoder = new FieldsEncoder(fieldsMapping);
        assertThat(fieldsEncoder.get(NAME)).isEqualTo("test");
        assertThat(fieldsEncoder.get(ASPECT)).isEqualTo(Set.of("aspect1", "aspect2"));
        assertThat(fieldsEncoder.get(CREATION_DATE_FIELD)).isEqualTo(createdTime);
        assertThat(fieldsEncoder.get(TYPE)).isEqualTo("cm:content");
        assertThat(fieldsEncoder.get(NAME)).isEqualTo("test");
        assertThat(fieldsEncoder.get("cm:title")).isEqualTo("my title");
        assertThat(fieldsEncoder.get("cm:description")).isEqualTo("this is a description");
        assertThat(fieldsEncoder.get(PROPERTIES)).isEqualTo(Set.of("cm:title", "cm:description"));
        assertThat(fieldsEncoder.get(ASPECT)).isEqualTo(Set.of("aspect1", "aspect2"));
        assertThat(fieldsEncoder.get(MODIFICATION_DATE_FIELD)).isEqualTo(modifiedTime);
        assertThat(fieldsEncoder.get(CREATION_DATE_FIELD)).isEqualTo(createdTime);
        assertThat(fieldsEncoder.get(USER_CREATOR)).isEqualTo("user_creator");
        assertThat(fieldsEncoder.get(USER_MODIFIER)).isEqualTo("user_modifier");
        assertThat(fieldsEncoder.get(CONTENT_MIME_TYPE)).isEqualTo("application/pdf");
        assertThat(fieldsEncoder.get(CONTENT_SIZE)).isEqualTo(12L);
        assertThat(fieldsEncoder.get(CONTENT_ENCODING)).isEqualTo("UTF-8");
        assertThat(fieldsEncoder.get(PRIMARY_HIERARCHY)).isEqualTo(List.of(PARENT_ID, GRANDPARENT_ID));
        assertThat(fieldsEncoder.get(PRIMARY_PARENT)).isEqualTo(PARENT_ID);
        assertThat(fieldsEncoder.get(ALIVE)).isNull();
        assertThat(fieldsEncoder.get(METADATA_INDEXING_LAST_UPDATE)).isNull();
    }

    @Test
    public void should_extractOwnerFromOwnerProperty_when_ownerIsDefined()
    {
        RepoEvent generatedEvent = repoEventWithResource(nodeResourceWithMetadata(
                Map.of(OWNER_PROPERTY_NAME, "Mario"), user("user_creator")));

        Map<String, Object> fieldsMapping = encodedFieldsExtractor.extract(generatedEvent).getEncodedMapping();

        assertThat(fieldsMapping.get(OWNER)).isEqualTo("Mario");
    }

    @Test
    public void should_extractOwnerFromCreator_when_ownerIsNotDefined()
    {
        RepoEvent generatedEvent = repoEventWithResource(nodeResourceWithMetadata(Map.of(), user("Luigi")));

        Map<String, Object> fieldsMapping = encodedFieldsExtractor.extract(generatedEvent).getEncodedMapping();

        assertThat(fieldsMapping.get(OWNER)).isEqualTo("Luigi");
    }

    @Test
    public void should_indexNoOwner_when_neitherOwnerNorCreatorAreDefined()
    {
        RepoEvent generatedEvent = repoEventWithResource(nodeResourceWithMetadata(Map.of(), null));

        Map<String, Object> fieldsMapping = encodedFieldsExtractor.extract(generatedEvent).getEncodedMapping();

        assertThat(fieldsMapping.get(OWNER)).isNull();
    }

    @Test
    public void shouldNotContainTrStatusAsFieldAndNeitherInPropertiesSet()
    {
        var generatedEvent = repoEventWithResource(nodeResourceWithMetadata(
                Map.of(CM_CONTENT_TR_STATUS, NO_TRANSFORM.name()), null));

        Map<String, Object> fieldsMapping = encodedFieldsExtractor.extract(generatedEvent).getEncodedMapping();

        FieldsEncoder fieldsEncoder = new FieldsEncoder(fieldsMapping);
        assertThat(fieldsEncoder.get(CM_CONTENT_TR_STATUS)).isNull();
        assertThat(fieldsMapping.get(CM_CONTENT_TR_STATUS)).isNull();
        assertThat(fieldsEncoder.get(PROPERTIES))
                .isInstanceOf(Set.class)
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.set(String.class))
                .doesNotContain(CM_CONTENT_TR_STATUS, encode(CM_CONTENT_TR_STATUS));
    }

    private NodeResource nodeResourceWithMetadata(Map<String, Serializable> properties, UserInfo creator)
    {
        return NodeResource.builder()
                .setCreatedByUser(creator)
                .setProperties(new HashMap<>(properties))
                .setPrimaryHierarchy(List.of())
                .build();
    }

    private RepoEvent<EventData<NodeResource>> repoEventWithResource(NodeResource resource)
    {
        EventData<NodeResource> data = EventData.<NodeResource> builder()
                .setResource(resource)
                .build();
        return RepoEvent.<EventData<NodeResource>> builder()
                .setType(EventType.NODE_CREATED.getType())
                .setData(data)
                .build();
    }

    private UserInfo user(String id)
    {
        return new UserInfo(id, "firstname", "surname");
    }

    private record FieldsEncoder(Map<String, Object> fields)
    {
        private Object get(String fieldName)
        {
            return fields.get(encode(fieldName));
        }
    }
}
