/*-
 * #%L
 * Alfresco Elasticsearch Connector :: Batch Indexing Module
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
package org.alfresco.elasticsearch.batchindexing.readers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;

import org.alfresco.database.connector.AlfrescoMetadataRepository;
import org.alfresco.database.connector.NodeParams;
import org.alfresco.database.connector.model.AlfrescoNode;

@ExtendWith(MockitoExtension.class)
@DisplayName("AlfrescoExplicitIdSetItemReader")
@SuppressWarnings("PMD.SignatureDeclareThrowsException")
class AlfrescoExplicitIdSetItemReaderTest
{
    @Mock
    private AlfrescoMetadataRepository metadataRepository;

    private void mockRepositoryYieldingNodeForEveryRequestedId()
    {
        when(metadataRepository.getAlfrescoNodes(any())).thenAnswer((Answer<List<AlfrescoNode>>) invocation -> {
            NodeParams params = invocation.getArgument(0, NodeParams.class);
            Set<Long> ids = params.getIds().orElseThrow();
            return ids.stream()
                    .map(id -> {
                        AlfrescoNode node = new AlfrescoNode();
                        node.setId(id);
                        return node;
                    })
                    .collect(Collectors.toUnmodifiableList());
        });
    }

    @Test
    @DisplayName("yields exactly one node per requested id, across pages, regardless of input order or gaps")
    void yieldsNodeForEveryId() throws Exception
    {
        mockRepositoryYieldingNodeForEveryRequestedId();
        Set<Long> ids = Set.of(500L, 3L, 42L, 17L, 999L);
        AlfrescoExplicitIdSetItemReader reader = new AlfrescoExplicitIdSetItemReader(metadataRepository, ids);
        reader.setPageSize(2);

        assertThat(readFully(reader)).containsExactlyInAnyOrderElementsOf(ids);
    }

    @Test
    @DisplayName("a missing id (deleted from the DB) is skipped, not retried")
    void missingIdIsNotRetried() throws Exception
    {
        // Repository only ever returns a node for id 1 — id 2 no longer exists.
        when(metadataRepository.getAlfrescoNodes(any())).thenAnswer((Answer<List<AlfrescoNode>>) invocation -> {
            NodeParams params = invocation.getArgument(0, NodeParams.class);
            Set<Long> ids = params.getIds().orElseThrow();
            if (!ids.contains(1L))
            {
                return List.of();
            }
            AlfrescoNode node = new AlfrescoNode();
            node.setId(1L);
            return List.of(node);
        });
        AlfrescoExplicitIdSetItemReader reader = new AlfrescoExplicitIdSetItemReader(metadataRepository, Set.of(1L, 2L));
        reader.setPageSize(1);

        assertThat(readFully(reader)).containsExactly(1L);
        verify(metadataRepository, times(2)).getAlfrescoNodes(any());
    }

    @Test
    @DisplayName("empty id set reads nothing and never calls the repository")
    void emptyIdSetReadsNothing() throws Exception
    {
        AlfrescoExplicitIdSetItemReader reader = new AlfrescoExplicitIdSetItemReader(metadataRepository, Set.of());
        reader.setPageSize(10);

        assertThat(readFully(reader)).isEmpty();
        verify(metadataRepository, times(0)).getAlfrescoNodes(any());
    }

    @Test
    @DisplayName("a non-positive page size fails fast on read instead of looping forever")
    void nonPositivePageSizeFailsFast()
    {
        AlfrescoExplicitIdSetItemReader reader = new AlfrescoExplicitIdSetItemReader(metadataRepository, Set.of(1L, 2L));
        reader.setPageSize(0);

        assertThatThrownBy(reader::read)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pageSize");
    }

    private List<Long> readFully(AlfrescoExplicitIdSetItemReader reader) throws Exception
    {
        List<Long> ids = new ArrayList<>();
        AlfrescoNode node = reader.read();
        while (node != null)
        {
            ids.add(node.getId());
            node = reader.read();
        }
        return ids;
    }
}
