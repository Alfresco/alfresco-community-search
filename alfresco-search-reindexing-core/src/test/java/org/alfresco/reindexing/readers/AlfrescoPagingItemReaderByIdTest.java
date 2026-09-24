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
package org.alfresco.reindexing.readers;

import static java.util.stream.LongStream.concat;
import static java.util.stream.LongStream.rangeClosed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;
import org.springframework.batch.infrastructure.item.ItemReader;

import org.alfresco.database.connector.AlfrescoMetadataRepository;
import org.alfresco.database.connector.NodeParams;
import org.alfresco.database.connector.NodeParams.Paging;
import org.alfresco.database.connector.NodeParams.Range;
import org.alfresco.database.connector.model.AlfrescoNode;

@ExtendWith(MockitoExtension.class)
class AlfrescoPagingItemReaderByIdTest
{
    private static final List<Long> EXISTING_NODE_IDS = concat(rangeClosed(30, 49), rangeClosed(60, 91))
            .boxed()
            .collect(Collectors.toUnmodifiableList());

    @Mock
    private AlfrescoMetadataRepository metadataRepository;

    private void setUpMetadataRepositoryMock()
    {
        when(metadataRepository.getAlfrescoNodes(any())).thenAnswer((Answer<List<AlfrescoNode>>) invocationOnMock -> {
            final NodeParams params = invocationOnMock.getArgument(0, NodeParams.class);
            final Range ids = params.getIdRange().orElseThrow();
            final Paging paging = params.getPaging().orElseThrow();

            return EXISTING_NODE_IDS
                    .stream()
                    .filter(ids::contains)
                    .skip((long) paging.getPage() * paging.getPageSize())
                    .limit(paging.getPageSize())
                    .map(id -> {
                        final AlfrescoNode node = new AlfrescoNode();
                        node.setId(id);
                        return node;
                    }).collect(Collectors.toUnmodifiableList());
        });
    }

    @Test
    public void shouldReturnCorrectResultsWhenTheLastPageIsEmpty() throws Exception
    {
        setUpMetadataRepositoryMock();
        // 54 is a non-existing id from the gap. It's here to simulate that the last page
        // <50, 54) is empty
        AlfrescoPagingItemReaderById r = givenReader(10, 54, 10);

        List<Long> ids = readFully(r);

        assertThat(ids).containsExactly(30L, 31L, 32L, 33L, 34L, 35L, 36L, 37L, 38L, 39L, 40L, 41L, 42L, 43L, 44L, 45L, 46L, 47L, 48L, 49L);
    }

    @Test
    public void shouldReturnCorrectResultsWhenTheLastPageIsPartiallyFilled() throws Exception
    {
        setUpMetadataRepositoryMock();
        AlfrescoPagingItemReaderById r = givenReader(33, 53, 10);

        List<Long> ids = readFully(r);
        System.err.println(ids);

        assertThat(ids).containsExactly(33L, 34L, 35L, 36L, 37L, 38L, 39L, 40L, 41L, 42L, 43L, 44L, 45L, 46L, 47L, 48L, 49L);
    }

    @Test
    public void shouldReturnCorrectResultsWhenThereIsAGapLargerThanAPage() throws Exception
    {
        setUpMetadataRepositoryMock();
        AlfrescoPagingItemReaderById r = givenReader(45, 65, 3);

        List<Long> ids = readFully(r);
        System.err.println(ids);

        assertThat(ids).containsExactly(45L, 46L, 47L, 48L, 49L, 60L, 61L, 62L, 63L, 64L);
    }

    private List<Long> readFully(ItemReader<AlfrescoNode> reader) throws Exception
    {
        List<Long> allIds = new ArrayList<>();

        AlfrescoNode node;
        while ((node = reader.read()) != null)
        {
            allIds.add(node.getId());
        }

        return allIds;
    }

    private AlfrescoPagingItemReaderById givenReader(long from, long to, int pageSize)
    {
        AlfrescoPagingItemReaderById reader = new AlfrescoPagingItemReaderById(metadataRepository, from, to);
        reader.setPageSize(pageSize);
        return reader;
    }

    @Test
    @DisplayName("Tests that the initial NodeParams are correctly prepared when there are no previous results (first page to read)")
    public void testGetParams_NoPreviousResults()
    {
        long rangeStart = 1;
        long rangeEnd = 10;
        AlfrescoPagingItemReaderById reader = new AlfrescoPagingItemReaderById(metadataRepository, rangeStart, rangeEnd);
        NodeParams nodeParams = reader.getParams();

        assertThat(nodeParams).isNotNull();
        assertThat(nodeParams.getIdRange()).isPresent();
        assertThat(nodeParams.getIdRange().get().getBegin()).isEqualTo(rangeStart);
        assertThat(nodeParams.getIdRange().get().getEnd()).isEqualTo(rangeEnd);
        assertThat(nodeParams.getPaging()).isPresent();
    }

    @Test
    @DisplayName("Tests that the NodeParams are correctly prepared based on the last read Node ID when there are missing IDs in the range")
    public void testGetParams_WithPreviousResults()
    {
        long rangeEnd = 65;
        setUpMetadataRepositoryMock();
        AlfrescoPagingItemReaderById r = givenReader(45, rangeEnd, 4);

        verifyNodeParams(r);

        r.doReadPage(); // Read the third page (63, 64)
        NodeParams nodeParams = r.getParams();
        assertThat(nodeParams.getIdRange().get().getBegin()).isEqualTo(rangeEnd);
    }

    @Test
    @DisplayName("Tests the pagination logic when the last page has same number of results as the page size")
    public void testGetParams_LastPageSameAsPageSize()
    {
        long rangeEnd = 67;
        setUpMetadataRepositoryMock();
        AlfrescoPagingItemReaderById r = givenReader(45, rangeEnd, 4);

        verifyNodeParams(r);

        r.doReadPage(); // Read the third page (63, 64, 65, 66)
        NodeParams nodeParams = r.getParams();
        assertThat(nodeParams.getIdRange().get().getBegin()).isEqualTo(rangeEnd);
    }

    @Test
    @DisplayName("Tests the pagination logic when the last page has one result")
    public void testGetParams_LastPageHasOneResult()
    {
        long rangeEnd = 64;
        setUpMetadataRepositoryMock();
        AlfrescoPagingItemReaderById r = givenReader(45, rangeEnd, 4);

        verifyNodeParams(r);

        r.doReadPage(); // Read the third page (63)
        NodeParams nodeParams = r.getParams();
        assertThat(nodeParams.getIdRange().get().getBegin()).isEqualTo(rangeEnd);
    }

    private void verifyNodeParams(AlfrescoPagingItemReaderById r)
    {
        r.doReadPage(); // Read the first page (45, 46, 47, 48)
        NodeParams nodeParams = r.getParams();
        assertThat(nodeParams.getIdRange().get().getBegin()).isEqualTo(49);

        r.doReadPage(); // Read the second page (49, 60, 61, 62). No nodes are available between 50 and 59.
        nodeParams = r.getParams();
        assertThat(nodeParams.getIdRange().get().getBegin()).isEqualTo(63);
    }

    @Test
    @DisplayName("Tests the pagination logic the last read Node ID falls into a gap in the series")
    public void testGetParams_MiddleOfMissingNodeIDs()
    {
        long rangeEnd = 60;
        setUpMetadataRepositoryMock();
        AlfrescoPagingItemReaderById r = givenReader(45, rangeEnd, 4);

        r.doReadPage(); // Read the first page (45, 46, 47, 48)
        NodeParams nodeParams = r.getParams();
        assertThat(nodeParams.getIdRange().get().getBegin()).isEqualTo(49);

        r.doReadPage(); // Read the second page (49), No nodes are available between 50 and 59.
        nodeParams = r.getParams();
        assertThat(nodeParams.getIdRange().get().getBegin()).isEqualTo(50);
    }

    @ParameterizedTest
    @ValueSource(longs = {1001, 1002, 1003, 1004})
    @DisplayName("Tests that more nodes are needed to read")
    public void testNeedMoreNodes_MiddlePages(long lastId)
    {
        long rangeEnd = 1005;
        AlfrescoNode node = new AlfrescoNode();
        node.setId(lastId);
        List<AlfrescoNode> lastNodes = List.of(node);
        when(metadataRepository.getAlfrescoNodes(any())).thenAnswer((Answer<List<AlfrescoNode>>) invocationOnMock -> {
            return lastNodes;
        });

        AlfrescoPagingItemReaderById r = givenReader(1001, rangeEnd, 4);
        r.doReadPage(); // Read First Page

        boolean actual = r.needMoreNodes();
        NodeParams nodeParams = r.getParams();

        assertThat(actual).isTrue();
        assertThat(nodeParams).isNotNull();
        assertThat(nodeParams.getIdRange()).isPresent();
        assertThat(nodeParams.getIdRange().get().getBegin()).isEqualTo(lastId + 1);
        assertThat(nodeParams.getIdRange().get().getEnd()).isEqualTo(rangeEnd);
    }

    @Test
    @DisplayName("Tests that first page is always read")
    public void testNeedMoreNodes_FirstPage()
    {
        long rangeStart = 1001;
        long rangeEnd = 1005;
        AlfrescoPagingItemReaderById r = givenReader(rangeStart, rangeEnd, 4);
        // Do not read the first page.

        boolean actual = r.needMoreNodes();
        NodeParams nodeParams = r.getParams();

        assertThat(actual).isTrue();
        assertThat(nodeParams).isNotNull();
        assertThat(nodeParams.getIdRange()).isPresent();
        assertThat(nodeParams.getIdRange().get().getBegin()).isEqualTo(rangeStart);
        assertThat(nodeParams.getIdRange().get().getEnd()).isEqualTo(rangeEnd);
    }

    @Test
    @DisplayName("Tests that no more nodes are needed to read")
    public void testNeedMoreNodes_LastPage()
    {
        long rangeEnd = 1005;
        AlfrescoNode node = new AlfrescoNode();
        node.setId(rangeEnd);
        List<AlfrescoNode> lastNodes = List.of(node);
        when(metadataRepository.getAlfrescoNodes(any())).thenAnswer((Answer<List<AlfrescoNode>>) invocationOnMock -> {
            return lastNodes;
        });

        AlfrescoPagingItemReaderById r = givenReader(1001, rangeEnd, 4);
        r.doReadPage(); // Read First Page

        boolean actual = r.needMoreNodes();
        r.doReadPage(); // Prevent any further calls to the repository.

        assertThat(actual).isFalse();
        verify(metadataRepository, times(1)).getAlfrescoNodes(any());
    }

    @Test
    @DisplayName("Tests that no more pages to read when the last Node Id processed (the pagination is already over)")
    public void testDoReadPage_IndexOutOfBounds() throws Exception
    {
        long lastId = 50L;

        AlfrescoNode node = new AlfrescoNode();
        node.setId(lastId);
        List<AlfrescoNode> lastNodes = List.of(node);

        when(metadataRepository.getAlfrescoNodes(any())).thenAnswer((Answer<List<AlfrescoNode>>) invocationOnMock -> {
            return lastNodes;
        });
        AlfrescoPagingItemReaderById r = givenReader(45, lastId, 4);

        r.doReadPage(); // Read the first page.
        List<Long> ids = readFully(r);
        assertThat(ids).hasSize(1);
        assertThat(ids.get(0)).isEqualTo(lastId);

        r.doReadPage(); // Again Read the Node Ids when reading Node Ids is complete.
        ids = readFully(r);
        assertThat(ids).isEmpty(); // No Operations should be performed.
    }
}
