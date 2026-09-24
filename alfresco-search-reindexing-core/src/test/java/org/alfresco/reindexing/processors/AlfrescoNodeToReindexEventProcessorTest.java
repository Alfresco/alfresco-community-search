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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.TYPE_DELETED;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.alfresco.database.connector.PrefixUriMapper;
import org.alfresco.database.connector.model.AlfrescoNode;
import org.alfresco.database.connector.model.NodeProperty;
import org.alfresco.database.connector.model.PropertyKey;
import org.alfresco.database.connector.model.PropertyValue;
import org.alfresco.database.connector.model.PropertyValueType;
import org.alfresco.reindexing.config.PrefixNamespaceConfig;
import org.alfresco.reindexing.model.ReindexingEvent;
import org.alfresco.repo.event.v1.model.EventType;
import org.alfresco.repo.event.v1.model.NodeResource;

@ExtendWith(MockitoExtension.class)
public class AlfrescoNodeToReindexEventProcessorTest
{

    @Mock
    private PrefixNamespaceConfig prefixNamespaceConfig;

    @Mock
    private AlfrescoNode alfrescoNode;

    @Test
    public void shouldEmitDeleteEventWhenIsIndexedIsFalse()
    {
        PrefixUriMapper prefixUriMapper = (a) -> Map.of("http://www.alfresco.org/model/content/1.0", "cm").get(a);
        when(prefixNamespaceConfig.prefixUriMapper()).thenReturn(prefixUriMapper);

        AlfrescoNodeToReindexingEventProcessor alfrescoNodeToReindexingEventProcessor = new AlfrescoNodeToReindexingEventProcessor(prefixNamespaceConfig);
        when(alfrescoNode.getNodeProperties()).thenReturn(Set.of(buildIsIndexedNodeProperties(false)));

        ReindexingEvent processResult = alfrescoNodeToReindexingEventProcessor.process(alfrescoNode);

        assertThat(processResult).isNotNull();
        assertThat(processResult.getType()).isEqualTo(EventType.NODE_DELETED.getType());
        assertThat(((NodeResource) processResult.getData().getResource()).getNodeType()).isEqualTo(TYPE_DELETED);
    }

    @Test
    public void shouldProcessNodeWhenIsIndexedIsTrue()
    {
        PrefixUriMapper prefixUriMapper = (a) -> Map.of("http://www.alfresco.org/model/content/1.0", "cm").get(a);
        when(prefixNamespaceConfig.prefixUriMapper()).thenReturn(prefixUriMapper);

        AlfrescoNodeToReindexingEventProcessor alfrescoNodeToReindexingEventProcessor = new AlfrescoNodeToReindexingEventProcessor(prefixNamespaceConfig);

        when(alfrescoNode.getNodeProperties()).thenReturn(Set.of(buildIsIndexedNodeProperties(true)));
        ReindexingEvent processResult = alfrescoNodeToReindexingEventProcessor.process(alfrescoNode);
        assertThat(processResult).isNotNull();
    }

    @Test
    public void shouldProcessNodeWhenIsIndexedIsMissing()
    {
        AlfrescoNodeToReindexingEventProcessor alfrescoNodeToReindexingEventProcessor = new AlfrescoNodeToReindexingEventProcessor(prefixNamespaceConfig);

        when(alfrescoNode.getNodeProperties()).thenReturn(Collections.emptySet());
        ReindexingEvent processResult = alfrescoNodeToReindexingEventProcessor.process(alfrescoNode);
        assertThat(processResult).isNotNull();
    }

    @NotNull private NodeProperty buildIsIndexedNodeProperties(boolean value)
    {
        NodeProperty isIndexedNodeProperty = new NodeProperty();
        PropertyKey propertyKey = new PropertyKey();
        propertyKey.setUri("http://www.alfresco.org/model/content/1.0");
        propertyKey.setLocalName("isIndexed");
        PropertyValue propertyValue = new PropertyValue();
        propertyValue.setPersistedType(PropertyValueType.BOOLEAN.getOrd());
        propertyValue.setBooleanValue(value);
        isIndexedNodeProperty.setPropertyKey(propertyKey);
        isIndexedNodeProperty.setPropertyValue(propertyValue);
        return isIndexedNodeProperty;
    }
}
