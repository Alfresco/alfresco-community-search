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

import static java.util.Collections.emptyMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;

import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.CM_CATEGORIES;
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.CM_CONTENT_TR_STATUS;
import static org.alfresco.repo.event.v1.model.EventType.NODE_CREATED;
import static org.alfresco.repo.event.v1.model.EventType.NODE_UPDATED;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.alfresco.indexing.shared.TransformStatus;
import org.alfresco.repo.event.v1.model.DataAttributes;
import org.alfresco.repo.event.v1.model.NodeResource;
import org.alfresco.repo.event.v1.model.RepoEvent;

/**
 * Unit tests for {@link ResourceTools}.
 */
@ExtendWith(MockitoExtension.class)
class ResourceToolsTest
{

    private static final String DUMMY_NODE_ID = "dummy-node-id";
    private static final String SOME_CATEGORY_ID = "some-category-id";
    private static final String ASSOC_NAME = "ASSOC_NAME";

    @Mock
    private RepoEvent<DataAttributes<NodeResource>> repoEvent;
    @Mock
    private DataAttributes<NodeResource> data;
    @Mock
    private NodeResource nodeResource;
    @Mock
    private NodeResource nodeResourceBefore;

    @Test
    void testCategoriesForNodeWithoutCategoryInformation()
    {
        Map<String, Serializable> properties = emptyMap();
        given(nodeResource.getProperties()).willReturn(properties);

        Set<String> actual = ResourceTools.categoriesForNode(nodeResource);

        assertThat(actual).isEmpty();
    }

    @Test
    void testCategoriesForNodeFromString()
    {
        Map<String, Serializable> properties = Map.of(CM_CATEGORIES, "1234");
        given(nodeResource.getProperties()).willReturn(properties);

        Set<String> actual = ResourceTools.categoriesForNode(nodeResource);

        assertThat(actual).isEqualTo(Set.of("1234"));
    }

    @Test
    void testCategoriesForNodeFromMap()
    {
        Map<String, Serializable> properties = Map.of(CM_CATEGORIES, (Serializable) Map.of("id", "1234"));
        given(nodeResource.getProperties()).willReturn(properties);

        Set<String> actual = ResourceTools.categoriesForNode(nodeResource);

        assertThat(actual).isEqualTo(Set.of("1234"));
    }

    @Test
    void testCategoriesForNodeFromSet()
    {
        Map<String, String> categoryA = Map.of("id", "1234");
        Map<String, String> categoryB = Map.of("id", "5678");
        Map<String, Serializable> properties = Map.of(CM_CATEGORIES, (Serializable) Set.of(categoryA, categoryB));
        given(nodeResource.getProperties()).willReturn(properties);

        Set<String> actual = ResourceTools.categoriesForNode(nodeResource);

        assertThat(actual).isEqualTo(Set.of("1234", "5678"));
    }

    @Test
    void testCategoriesForNodeUnsupportedType()
    {
        Map<String, Serializable> properties = Map.of(CM_CATEGORIES, (Serializable) Set.of(1234));
        given(nodeResource.getProperties()).willReturn(properties);

        assertThrows(UnsupportedOperationException.class, () -> ResourceTools.categoriesForNode(nodeResource));
    }

    @Test
    public void testWereCategoriesUpdatedReturnsTrue()
    {
        given(repoEvent.getData()).willReturn(data);
        given(repoEvent.getType()).willReturn(NODE_UPDATED.getType());
        given(data.getResource()).willReturn(nodeResource);
        given(data.getResourceBefore()).willReturn(nodeResourceBefore);
        given(nodeResourceBefore.getProperties()).willReturn(Collections.singletonMap(CM_CATEGORIES, null));
        given(nodeResource.getProperties()).willReturn(Collections.singletonMap(CM_CATEGORIES, SOME_CATEGORY_ID));

        assertTrue(ResourceTools.wereCategoriesUpdated(repoEvent));
    }

    @Test
    public void testWereCategoriesUpdatedReturnsFalseWhenNodeCreated()
    {
        given(repoEvent.getData()).willReturn(data);
        given(repoEvent.getType()).willReturn(NODE_CREATED.getType());
        given(data.getResource()).willReturn(nodeResource);
        given(nodeResource.getProperties()).willReturn(null);

        assertFalse(ResourceTools.wereCategoriesUpdated(repoEvent));
    }

    @Test
    public void testWereCategoriesUpdatedReturnsFalse()
    {
        given(repoEvent.getData()).willReturn(data);
        given(repoEvent.getType()).willReturn(NODE_UPDATED.getType());
        given(data.getResource()).willReturn(nodeResource);
        given(data.getResourceBefore()).willReturn(nodeResourceBefore);
        given(nodeResourceBefore.getProperties()).willReturn(Collections.singletonMap(CM_CATEGORIES, null));
        given(nodeResource.getProperties()).willReturn(Collections.singletonMap(CM_CATEGORIES, null));

        assertFalse(ResourceTools.wereCategoriesUpdated(repoEvent));
    }

    @Test
    public void testWasPrimaryHierarchyUpdatedReturnsTrueWhenNodeCreated()
    {
        given(repoEvent.getData()).willReturn(data);
        given(repoEvent.getType()).willReturn(NODE_CREATED.getType());
        given(data.getResource()).willReturn(nodeResource);
        given(nodeResource.getPrimaryHierarchy()).willReturn(List.of(DUMMY_NODE_ID));

        assertTrue(ResourceTools.wasPrimaryHierarchyUpdated(repoEvent));
    }

    @Test
    public void testWasPrimaryHierarchyUpdatedReturnsTrueWhenMoved()
    {
        given(repoEvent.getData()).willReturn(data);
        given(repoEvent.getType()).willReturn(NODE_UPDATED.getType());
        given(data.getResource()).willReturn(nodeResource);
        given(data.getResourceBefore()).willReturn(nodeResourceBefore);
        given(nodeResourceBefore.getPrimaryHierarchy()).willReturn(List.of(DUMMY_NODE_ID));
        given(nodeResource.getPrimaryHierarchy()).willReturn(List.of(DUMMY_NODE_ID + "-updated"));

        assertTrue(ResourceTools.wasPrimaryHierarchyUpdated(repoEvent));
    }

    @Test
    public void testWasPrimaryHierarchyUpdatedReturnsTrueWhenRenamed()
    {
        given(repoEvent.getData()).willReturn(data);
        given(repoEvent.getType()).willReturn(NODE_UPDATED.getType());
        given(data.getResource()).willReturn(nodeResource);
        given(data.getResourceBefore()).willReturn(nodeResourceBefore);
        given(nodeResourceBefore.getPrimaryHierarchy()).willReturn(List.of(DUMMY_NODE_ID));
        given(nodeResource.getPrimaryHierarchy()).willReturn(List.of(DUMMY_NODE_ID));
        given(nodeResourceBefore.getPrimaryAssocQName()).willReturn(ASSOC_NAME);
        given(nodeResource.getPrimaryAssocQName()).willReturn(ASSOC_NAME + "_UPDATED");

        assertTrue(ResourceTools.wasPrimaryHierarchyUpdated(repoEvent));
    }

    @Test
    public void testWasPrimaryHierarchyUpdatedReturnsFalse()
    {
        given(repoEvent.getData()).willReturn(data);
        given(repoEvent.getType()).willReturn(NODE_UPDATED.getType());
        given(data.getResource()).willReturn(nodeResource);
        given(data.getResourceBefore()).willReturn(nodeResourceBefore);
        given(nodeResourceBefore.getPrimaryHierarchy()).willReturn(List.of(DUMMY_NODE_ID));
        given(nodeResource.getPrimaryHierarchy()).willReturn(List.of(DUMMY_NODE_ID));
        given(nodeResourceBefore.getPrimaryAssocQName()).willReturn(ASSOC_NAME);
        given(nodeResource.getPrimaryAssocQName()).willReturn(ASSOC_NAME);

        assertFalse(ResourceTools.wasPrimaryHierarchyUpdated(repoEvent));
    }

    @ParameterizedTest
    @EnumSource(TransformStatus.class)
    public void shouldSuccessfullyExtractTransformationStatusWhenPresent(TransformStatus transformStatus)
    {
        given(repoEvent.getData()).willReturn(data);
        given(data.getResource()).willReturn(nodeResource);
        given(nodeResource.getProperties()).willReturn(Map.of(CM_CONTENT_TR_STATUS, transformStatus.name()));

        Optional<String> actualTransformStatus = ResourceTools.extractTransformStatus(repoEvent);

        Optional<String> expectedTransformStatus = Optional.of(transformStatus.name());
        assertEquals(expectedTransformStatus, actualTransformStatus);
    }

    @Test
    public void shouldReturnEmptyOptionalWhenTransformationStatusIsMissing()
    {
        given(repoEvent.getData()).willReturn(data);
        given(data.getResource()).willReturn(nodeResource);
        given(nodeResource.getProperties()).willReturn(Map.of());

        Optional<String> actualTransformStatus = ResourceTools.extractTransformStatus(repoEvent);

        assertThat(actualTransformStatus).isEmpty();
    }

    @Test
    public void shouldReturnEmptyOptionalWhenGetPropertiesReturnsNull()
    {
        given(repoEvent.getData()).willReturn(data);
        given(data.getResource()).willReturn(nodeResource);
        given(nodeResource.getProperties()).willReturn(null);

        Optional<String> actualTransformStatus = ResourceTools.extractTransformStatus(repoEvent);

        assertThat(actualTransformStatus).isEmpty();
    }

    @Test
    public void shouldReturnEmptyOptionalWhenTransformationStatusIsOfIncorrectType()
    {
        given(repoEvent.getData()).willReturn(data);
        given(data.getResource()).willReturn(nodeResource);
        given(nodeResource.getProperties()).willReturn(Map.of(CM_CONTENT_TR_STATUS, "random string instead of TransformStatus"));

        Optional<String> actualTransformStatus = ResourceTools.extractTransformStatus(repoEvent);

        assertThat(actualTransformStatus).isEmpty();
    }
}
