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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.CM_CATEGORIES;

import java.io.Serializable;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.alfresco.database.connector.model.ChildAssocMetaData;
import org.alfresco.database.connector.model.Path;
import org.alfresco.reindexing.StandardPathsResolver;
import org.alfresco.reindexing.config.ReindexingCoreProperties;
import org.alfresco.reindexing.model.ReindexingEvent;
import org.alfresco.repo.event.v1.model.EventData;
import org.alfresco.repo.event.v1.model.NodeResource;
import org.alfresco.repo.event.v1.model.RepoEvent;

@ExtendWith(MockitoExtension.class)
public class ReindexEventPathAdderTest
{
    private ReindexEventPathAdderProcessor processor;
    @Mock
    private StandardPathsResolver pathsResolver;
    @Mock
    private NodeResource nodeResource;
    @Mock
    private EventData data;
    @Mock
    private ReindexingEvent reindexingEvent;
    @Mock
    private RepoEvent repoEvent;
    @Mock
    private Path path;
    @Mock
    private ReindexingCoreProperties elasticsearchReindexProperties;

    @BeforeEach
    public void setUp()
    {
        processor = new ReindexEventPathAdderProcessor(pathsResolver, elasticsearchReindexProperties);
    }

    @Test
    public void testProcess()
    {
        ChildAssocMetaData parent = ChildAssocMetaData.createRootNode(1234L);
        when(elasticsearchReindexProperties.isPathIndexingEnabled()).thenReturn(true);
        when(reindexingEvent.getData()).thenReturn(data);
        when(data.getResource()).thenReturn(nodeResource);
        when(reindexingEvent.getPrimaryParent()).thenReturn(parent);
        when(reindexingEvent.getRepoEvent()).thenReturn(repoEvent);
        when(nodeResource.getNodeType()).thenReturn("cm:content");
        when(pathsResolver.findPrimaryPath(any(), any())).thenReturn(path);
        when(pathsResolver.findPaths(any(), any())).thenReturn(Set.of(path));

        ReindexingEvent result = processor.process(reindexingEvent);

        verify(pathsResolver).findPaths(eq(Set.of(parent)), any());

        assertThat(result.getPrimaryPath()).isEqualTo(path);
        assertThat(result.getAllPaths()).isEqualTo(Set.of(path));
        assertThat(result.getRepoEvent()).isEqualTo(repoEvent);
    }

    @Test
    public void testProcessDisabled()
    {
        when(elasticsearchReindexProperties.isPathIndexingEnabled()).thenReturn(false);
        when(reindexingEvent.getData()).thenReturn(data);
        when(data.getResource()).thenReturn(nodeResource);
        when(reindexingEvent.getRepoEvent()).thenReturn(repoEvent);
        ReindexingEvent result = processor.process(reindexingEvent);

        verify(pathsResolver, never()).findPaths(any(), any(), any());

        assertThat(result.getAllPaths()).isEmpty();
        assertThat(result.getRepoEvent()).isEqualTo(repoEvent);
    }

    @Test
    public void testProcessDeleted()
    {
        when(elasticsearchReindexProperties.isPathIndexingEnabled()).thenReturn(true);
        when(reindexingEvent.getData()).thenReturn(data);
        when(data.getResource()).thenReturn(nodeResource);
        when(reindexingEvent.getRepoEvent()).thenReturn(repoEvent);
        when(nodeResource.getNodeType()).thenReturn("sys:deleted");

        ReindexingEvent result = processor.process(reindexingEvent);

        verify(pathsResolver, never()).findPaths(any(), any(), any());

        assertThat(result.getAllPaths()).isEmpty();
        assertThat(result.getRepoEvent()).isEqualTo(repoEvent);
    }

    @Test
    public void testGetCategoryPathsFromNoProperties()
    {
        when(nodeResource.getProperties()).thenReturn(null);

        Set<Path> categoryPaths = processor.getCategoryPaths(nodeResource, reindexingEvent);

        assertThat(categoryPaths).isEmpty();
    }

    @Test
    public void testGetCategoryPathsWithoutParentAssoc()
    {
        assertThrows(IllegalStateException.class, () -> processor.getCategoryPaths(nodeResource, reindexingEvent));
    }

    @Test
    public void testGetCategoryPaths()
    {
        ChildAssocMetaData parent = ChildAssocMetaData.createRootNode(1234L);
        when(reindexingEvent.getPrimaryParent()).thenReturn(parent);

        Map<String, Object> categoryA = Map.of("protocol", "workspace", "identifier", "SpacesStore", "id", "56785678-5678-5678-5678-567856785678");
        Map<String, Object> categoryB = Map.of("protocol", "workspace", "identifier", "SpacesStore", "id", "9abc9abc-9abc-9abc-9abc-9abc9abc9abc");
        Serializable cmCategories = (Serializable) Set.of(categoryA, categoryB);
        Map<String, Serializable> properties = Map.of(CM_CATEGORIES, cmCategories);
        when(nodeResource.getProperties()).thenReturn(properties);

        Set<Path> categoryPaths = processor.getCategoryPaths(nodeResource, reindexingEvent);

        assertThat(categoryPaths).isEmpty();
    }
}
