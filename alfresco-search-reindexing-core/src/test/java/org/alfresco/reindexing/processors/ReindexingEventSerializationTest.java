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

import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.io.StringWriter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import jakarta.json.Json;
import jakarta.json.stream.JsonGenerator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.client.opensearch.core.BulkRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.alfresco.database.connector.model.*;
import org.alfresco.indexing.shared.ElasticsearchProperties;
import org.alfresco.indexing.shared.extractor.EncodedFields;
import org.alfresco.indexing.shared.extractor.EncodedFieldsExtractor;
import org.alfresco.reindexing.config.PrefixNamespaceConfig;
import org.alfresco.reindexing.model.ReindexingEvent;
import org.alfresco.reindexing.opensearch.request.ReindexingRequestBuilder;
import org.alfresco.repo.event.v1.model.NodeResource;
import org.alfresco.repo.event.v1.model.RepoEvent;

@ExtendWith(MockitoExtension.class)
class ReindexingEventSerializationTest
{
    @Mock
    private ElasticsearchProperties elasticsearchProperties;
    @Mock
    private EncodedFieldsExtractor encodedFieldsExtractor;
    private ReindexingRequestBuilder builder;
    private static final String ALFRESCO_INDEX_NAME = "alfresco";
    private static final int RETRY_COUNT = 5;
    private static final Logger LOGGER = LoggerFactory.getLogger(ReindexingEventSerializationTest.class);

    @BeforeEach
    void setUp()
    {
        when(elasticsearchProperties.getIndexName()).thenReturn(ALFRESCO_INDEX_NAME);
        when(elasticsearchProperties.getRetryOnConflict()).thenReturn(RETRY_COUNT);
        when(encodedFieldsExtractor.extract(any())).thenReturn(new EncodedFields());
        builder = new ReindexingRequestBuilder(elasticsearchProperties, encodedFieldsExtractor);
    }

    private ReindexingEvent getMockReindexingEvent()
    {
        AlfrescoNode node = new AlfrescoNode();
        node.setId(100L);
        node.setNodeRef("workspace://SpacesStore/broken-node");
        node.setTimestamp(System.currentTimeMillis());
        QName cengageAspect = QName.newTransientInstance("http://ns.useplus.org/ldf/cengage/1.0/", "asset");
        node.setType(cengageAspect);
        QName xmpAspect = QName.newTransientInstance("http://ns.adobe.com/xmp/1.0/", "hasMetadata");
        node.setAspects(Set.of(cengageAspect, xmpAspect));

        ContentMetadata cmContent = new ContentMetadata();
        cmContent.setId(1L);
        cmContent.setMimetypeStr("application/pdf");
        cmContent.setEncodingStr("UTF-8");
        cmContent.setContentSize(100L);

        ContentMetadata xmpContent = new ContentMetadata();
        xmpContent.setId(2L);
        xmpContent.setMimetypeStr("application/rdf+xml");
        xmpContent.setEncodingStr("UTF-8");
        xmpContent.setContentSize(200L);

        node.setContentData(Set.of(cmContent, xmpContent));

        NodeProperty cmNodeProperty = new NodeProperty();

        PropertyKey cmKey = new PropertyKey();
        cmKey.setNodeId(100L);
        cmKey.setUri("http://www.alfresco.org/model/content/1.0");
        cmKey.setLocalName("content");
        cmKey.setLocaleStr(".default");

        PropertyValue cmValue = new PropertyValue();
        cmValue.setPersistedType(PropertyValueType.LONG.getOrd());
        cmValue.setActualType(PropertyValueType.CONTENT_DATA_ID.getOrd());
        cmValue.setLongValue(1L);

        cmNodeProperty.setPropertyKey(cmKey);
        cmNodeProperty.setPropertyValue(cmValue);

        NodeProperty xmpNodeProperty = new NodeProperty();

        PropertyKey xmpKey = new PropertyKey();
        xmpKey.setNodeId(100L);
        xmpKey.setUri("http://ns.adobe.com/xmp/1.0/");
        xmpKey.setLocalName("content");
        xmpKey.setLocaleStr(".default");

        PropertyValue xmpValue = new PropertyValue();
        xmpValue.setPersistedType(PropertyValueType.LONG.getOrd());
        xmpValue.setActualType(PropertyValueType.CONTENT_DATA_ID.getOrd());
        xmpValue.setLongValue(2L);

        xmpNodeProperty.setPropertyKey(xmpKey);
        xmpNodeProperty.setPropertyValue(xmpValue);

        node.setNodeProperties(Set.of(cmNodeProperty, xmpNodeProperty));
        PrefixNamespaceConfig prefixConfig = new PrefixNamespaceConfig();
        prefixConfig.setPrefixUriMap(
                Map.of(
                        "http://www.alfresco.org/model/content/1.0", "cm",
                        "http://ns.adobe.com/xmp/1.0/", "xmp",
                        "http://ns.useplus.org/ldf/cengage/1.0/", "cengage"));

        AlfrescoNodeToReindexingEventProcessor processor = new AlfrescoNodeToReindexingEventProcessor(prefixConfig);

        LOGGER.info("AlfrescoNode before processing: {}", node);
        ReindexingEvent event = processor.process(node);
        LOGGER.info("NodeResource after processing: {}", event.getRepoEvent().getData().getResource());
        return event;
    }

    @Test
    void shouldNotContainJavaObjectsInReindexingEventProperties()
    {
        ReindexingEvent reindexingEvent = getMockReindexingEvent();
        List<RepoEvent> events = List.of(reindexingEvent.getRepoEvent());
        org.opensearch.client.opensearch.core.BulkRequest bulkRequest = builder.buildBulkRequest(events, 0);
        NodeResource nodeResource = reindexingEvent.getRepoEvent().getData().getResource();
        assertNoJavaObjectIsPresent(nodeResource);
        assertBulkRequestSerializable(bulkRequest);
    }

    // checks that, NodeResource should not contain any java object
    private void assertNoJavaObjectIsPresent(NodeResource nodeResource)
    {
        Map<?, ?> properties = nodeResource.getProperties();
        for (Map.Entry<?, ?> entry : properties.entrySet())
        {
            if (entry.getValue() instanceof Object)
            {
                fail("Property " + entry.getKey() + " contains a Java object of type " + entry.getValue().getClass().getName());
            }
        }

        Map<?, ?> localizedProperties = nodeResource.getLocalizedProperties();
        for (Map.Entry<?, ?> entry : localizedProperties.entrySet())
        {
            Map<?, ?> eachLocalizedProperty = (Map<?, ?>) entry.getValue();
            for (Map.Entry<?, ?> localizedEntry : eachLocalizedProperty.entrySet())
            {
                if (localizedEntry.getValue() instanceof Object)
                {
                    fail("Localized property " + localizedEntry.getKey() + " contains a Java object of type " + localizedEntry.getValue().getClass().getName());
                }
            }
        }

    }

    private void assertBulkRequestSerializable(BulkRequest bulkRequest)
    {
        try
        {
            StringWriter writer = new StringWriter();
            JsonGenerator generator = Json.createGenerator(writer);
            bulkRequest.serialize(generator, new JacksonJsonpMapper());
            generator.close();
            String json = writer.toString();
            LOGGER.info("Serialized bulk request: {}", json);
        }
        catch (Exception e)
        {
            fail("BulkRequest serialization failed: " + e.getMessage());
        }
    }
}
