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
package org.alfresco.reindexing;

import static java.util.Collections.emptySet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import static org.alfresco.database.connector.ParentChildAssociationOrdinality.PRIMARY;
import static org.alfresco.database.connector.ParentChildAssociationOrdinality.SECONDARY;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import org.alfresco.database.connector.AlfrescoMetadataRepository;
import org.alfresco.database.connector.ChildAssocParams;
import org.alfresco.database.connector.model.ChildAssocMetaData;
import org.alfresco.database.connector.model.Path;
import org.alfresco.reindexing.model.ArchivedOriginalParentAssoc;

/** Unit tests for {@link StandardPathsResolver}. */
@RunWith(MockitoJUnitRunner.class)
public class StandardPathsResolverTest
{
    private static final ChildAssocMetaData ASSOC_0 = new ChildAssocMetaData(1, 0, "uri", "name");
    private static final ChildAssocMetaData ASSOC_1 = new ChildAssocMetaData(2, 1, "uri", "name");
    private static final ChildAssocMetaData CYCLE_ASSOC = new ChildAssocMetaData(0, 2, "uri", "name");
    private static final String CM_URI = "http://www.alfresco.org/model/content/1.0";
    private static final long ARCHIVED_NODE_ID = 10L;
    private static final long ORIGINAL_PARENT_ID = 5L;
    private static final String ORIGINAL_PARENT_UUID = "4f94a1a5-a227-44d7-94a1-a5a22744d740";
    private static final ArchivedOriginalParentAssoc ORIGINAL = new ArchivedOriginalParentAssoc(ORIGINAL_PARENT_UUID, CM_URI, "test.txt");

    @InjectMocks
    StandardPathsResolver standardPathsResolver;
    @Mock
    AlfrescoMetadataRepository metadataRepository;

    @Test
    public void testFindPaths_noParents_noPaths()
    {
        Set<ChildAssocMetaData> parentAssociations = Set.of();

        Set<Path> paths = standardPathsResolver.findPaths(parentAssociations, null);

        assertEquals("Unexpected paths returned", emptySet(), paths);
    }

    @Test
    public void testFindPaths_rootNode_onePath()
    {
        // The store root just has a single assoc with the parent id set to null.
        ChildAssocMetaData rootNodeAssoc = new ChildAssocMetaData();
        rootNodeAssoc.setChildId(12L);
        Set<ChildAssocMetaData> parentAssociations = Set.of(rootNodeAssoc);

        Set<Path> paths = standardPathsResolver.findPaths(parentAssociations, null);

        assertEquals("The root node should have an empty path", Set.of(new Path()), paths);
    }

    @Test
    public void testFindPaths_twoParents_twoPaths()
    {
        Set<ChildAssocMetaData> parentAssociations = Set.of(ASSOC_0, ASSOC_1);

        Set<Path> paths = standardPathsResolver.findPaths(parentAssociations, null);

        Path expected0 = new Path(List.of(ASSOC_0));
        Path expected1 = new Path(List.of(ASSOC_1));
        assertEquals("Unexpected paths returned", Set.of(expected0, expected1), paths);
    }

    @Test
    public void testFindPaths_chainOfTwoNodes_oneLongPath()
    {
        Set<ChildAssocMetaData> parentAssociations = Set.of(ASSOC_1);
        when(metadataRepository.getChildAssocMetaData(new ChildAssocParams(1L))).thenReturn(Set.of(ASSOC_0));

        Set<Path> paths = standardPathsResolver.findPaths(parentAssociations, null);

        Path expected = new Path(List.of(ASSOC_1, ASSOC_0));
        assertEquals("Unexpected paths returned", Set.of(expected), paths);
    }

    @Test
    public void testFindPaths_cycleOfNodes_longestPath()
    {
        Set<ChildAssocMetaData> parentAssociations = Set.of(ASSOC_1);
        when(metadataRepository.getChildAssocMetaData(new ChildAssocParams(1L))).thenReturn(Set.of(ASSOC_0));
        when(metadataRepository.getChildAssocMetaData(new ChildAssocParams(2L))).thenReturn(Set.of(ASSOC_1));
        when(metadataRepository.getChildAssocMetaData(new ChildAssocParams(0L))).thenReturn(Set.of(CYCLE_ASSOC));

        Set<Path> paths = standardPathsResolver.findPaths(parentAssociations, null);

        Path expected = new Path(List.of(ASSOC_1, ASSOC_0, CYCLE_ASSOC));
        assertEquals("Unexpected paths returned", Set.of(expected), paths);
    }

    @Test
    public void findPrimaryPath_nodeWithTwoParents_onlyPrimaryReturned()
    {
        // Node 1 has primary parent 0 and secondary parent 3.
        ChildAssocMetaData secondaryParentAssoc = new ChildAssocMetaData(1L, 3L, "uri", "name");
        lenient().when(metadataRepository.getChildAssocMetaData(new ChildAssocParams(Set.of(PRIMARY), 1L, null))).thenReturn(Set.of(ASSOC_0));
        lenient().when(metadataRepository.getChildAssocMetaData(new ChildAssocParams(Set.of(SECONDARY), 1L, null))).thenReturn(Set.of(secondaryParentAssoc));
        lenient().when(metadataRepository.getChildAssocMetaData(new ChildAssocParams(Set.of(PRIMARY, SECONDARY), 1L, null))).thenReturn(Set.of(ASSOC_0, secondaryParentAssoc));

        // When getting the primary parent we just get the path to node 0.
        Path path = standardPathsResolver.findPrimaryPath(ASSOC_1, null).orElseThrow();
        Path expected = new Path(List.of(ASSOC_1, ASSOC_0));
        assertEquals("Unexpected paths returned", expected, path);

        // When getting all paths then we get the path to node 0 and the path to node 3.
        Set<Path> allPaths = standardPathsResolver.findPaths(Set.of(ASSOC_1), null);
        Set<Path> expectedPaths = Set.of(new Path(List.of(ASSOC_1, ASSOC_0)), new Path(List.of(ASSOC_1, secondaryParentAssoc)));
        assertEquals("Unexpected paths returned", expectedPaths, allPaths);
    }

    @Test
    public void findPrimaryPath_deletedParent_empty()
    {
        ChildAssocMetaData assocToDeletedParent = new ChildAssocMetaData(2L, -1L, "uri", "name");

        assertFalse(standardPathsResolver.findPrimaryPath(assocToDeletedParent, null).isPresent());
    }

    @Test
    public void findOriginalParentAssociation_parentFound_associationCarriesOriginalName()
    {
        when(metadataRepository.getDBIdFromNodeRef(ORIGINAL_PARENT_UUID)).thenReturn(ORIGINAL_PARENT_ID);

        ChildAssocMetaData association = standardPathsResolver.findOriginalParentAssociation(ORIGINAL, ARCHIVED_NODE_ID).orElseThrow();

        assertEquals(Long.valueOf(ARCHIVED_NODE_ID), association.getChildId());
        assertEquals(Long.valueOf(ORIGINAL_PARENT_ID), association.getParentId());
        assertEquals(ORIGINAL_PARENT_UUID, association.getParentUuid());
        assertEquals(CM_URI, association.getUri());
        assertEquals("test.txt", association.getName());
    }

    @Test
    public void findOriginalParentAssociation_pathsHangUnderOriginalParent()
    {
        ChildAssocMetaData parentAssoc = new ChildAssocMetaData(ORIGINAL_PARENT_ID, 1L, "http://www.alfresco.org/model/application/1.0", "company_home");
        when(metadataRepository.getDBIdFromNodeRef(ORIGINAL_PARENT_UUID)).thenReturn(ORIGINAL_PARENT_ID);
        when(metadataRepository.getChildAssocMetaData(new ChildAssocParams(Set.of(PRIMARY), ORIGINAL_PARENT_ID, null))).thenReturn(Set.of(parentAssoc));
        when(metadataRepository.getChildAssocMetaData(new ChildAssocParams(ORIGINAL_PARENT_ID))).thenReturn(Set.of(parentAssoc));

        ChildAssocMetaData association = standardPathsResolver.findOriginalParentAssociation(ORIGINAL, ARCHIVED_NODE_ID).orElseThrow();
        Path primaryPath = standardPathsResolver.findPrimaryPath(association, null).orElseThrow();
        Set<Path> allPaths = standardPathsResolver.findPaths(Set.of(association), null);

        Path expected = new Path(List.of(association, parentAssoc));
        assertEquals("Unexpected primary path", expected, primaryPath);
        assertEquals("Unexpected paths", Set.of(expected), allPaths);
    }

    @Test
    public void findOriginalParentAssociation_parentNotFound_empty()
    {
        when(metadataRepository.getDBIdFromNodeRef(ORIGINAL_PARENT_UUID)).thenReturn(-1L);

        assertFalse(standardPathsResolver.findOriginalParentAssociation(ORIGINAL, ARCHIVED_NODE_ID).isPresent());
    }

    @Test
    public void findOriginalParentAssociation_parentIdNull_empty()
    {
        when(metadataRepository.getDBIdFromNodeRef(ORIGINAL_PARENT_UUID)).thenReturn(null);

        assertFalse(standardPathsResolver.findOriginalParentAssociation(ORIGINAL, ARCHIVED_NODE_ID).isPresent());
    }

    @Test
    public void testFindPaths_dBIdNull_returnEmptyPath()
    {
        // A null id used to reach traverse() and come back as the store root path "/".
        String parentNodeRef = "unknown-node";
        when(metadataRepository.getDBIdFromNodeRef(parentNodeRef)).thenReturn(null);

        assertEquals("Empty paths returned", Set.of(), standardPathsResolver.findPaths(parentNodeRef, ASSOC_1, null));
    }

    @Test
    public void testFindPaths_dBIdNotFound_returnEmptyPath()
    {
        String parentNodeRef = "invalid-node";
        when(metadataRepository.getDBIdFromNodeRef(parentNodeRef)).thenReturn(-1L);
        Set<Path> paths = standardPathsResolver.findPaths(parentNodeRef, ASSOC_1, null);
        Set<Path> expectedPath = new HashSet<>();
        assertEquals("Empty paths returned", expectedPath, paths);
    }
}
