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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
class AlfrescoPagingItemReaderByTimestampTest
{
    @Mock
    private AlfrescoMetadataRepository metadataRepository;

    // Simulate nodes with timestamps and IDs
    // IDs: 30-49, 60-91 (gap from 50-59)
    // Timestamps vary to simulate realistic data
    private static final Map<Long, Long> NODE_ID_TO_TIMESTAMP = createTestData();

    private static Map<Long, Long> createTestData()
    {
        Map<Long, Long> data = new HashMap<>();
        // Nodes 30-39 have timestamps 1000-1090
        for (long i = 30; i <= 39; i++)
        {
            data.put(i, 1000L + (i - 30) * 10);
        }
        // Nodes 40-49 have timestamps 1100-1190
        for (long i = 40; i <= 49; i++)
        {
            data.put(i, 1100L + (i - 40) * 10);
        }
        // Nodes 60-69 have timestamps 1200-1290 (gap in IDs but continuous timestamps)
        for (long i = 60; i <= 69; i++)
        {
            data.put(i, 1200L + (i - 60) * 10);
        }
        // Nodes 70-79 have timestamps 1300-1390
        for (long i = 70; i <= 79; i++)
        {
            data.put(i, 1300L + (i - 70) * 10);
        }
        // Nodes 80-91 have timestamps 1400-1510
        for (long i = 80; i <= 91; i++)
        {
            data.put(i, 1400L + (i - 80) * 10);
        }
        return data;
    }

    @SuppressWarnings("null")
    @BeforeEach
    void setUpMetadataRepositoryMock()
    {
        lenient().when(metadataRepository.getAlfrescoNodes(any())).thenAnswer((Answer<List<AlfrescoNode>>) invocationOnMock -> {
            final NodeParams params = invocationOnMock.getArgument(0, NodeParams.class);
            final Range timestamps = params.getTimestampRange().orElseThrow();
            final Paging paging = params.getPaging().orElseThrow();

            // Verify OFFSET is always 0 (critical for performance)
            assertThat(paging.getPage()).isZero();

            // Get ID range if present (used for keyset pagination)
            Long minId = params.getIdRange().map(Range::getBegin).orElse(0L);

            return NODE_ID_TO_TIMESTAMP.entrySet()
                    .stream()
                    // Filter by timestamp range
                    .filter(entry -> timestamps.contains(entry.getValue()))
                    // Filter by ID range (keyset pagination: ID >= minId)
                    .filter(entry -> entry.getKey() >= minId)
                    // Sort by ID (this is what the database does)
                    .sorted(Map.Entry.comparingByKey())
                    // Apply page size limit
                    .limit(paging.getPageSize())
                    .map(entry -> {
                        final AlfrescoNode node = new AlfrescoNode();
                        node.setId(entry.getKey());
                        node.setTimestamp(entry.getValue());
                        return node;
                    }).toList();
        });
    }

    @Test
    void shouldReturnCorrectResultsWithSmallPageSize() throws Exception
    {
        // Query for timestamps 1000-1150, which covers nodes 30-44
        AlfrescoPagingItemReaderByTimestamp r = givenReader(1000L, 1150L, 5);

        List<Long> ids = readFully(r);

        // Should return nodes 30-44 (15 nodes total)
        assertThat(ids).containsExactly(30L, 31L, 32L, 33L, 34L, 35L, 36L, 37L, 38L, 39L, 40L, 41L, 42L, 43L, 44L);
    }

    @Test
    void shouldHandleGapInNodeIds() throws Exception
    {
        // Query for timestamps 1150-1250, which covers nodes 45-49 and 60-64
        AlfrescoPagingItemReaderByTimestamp r = givenReader(1150L, 1250L, 10);

        List<Long> ids = readFully(r);

        // Should correctly skip the gap from 50-59
        assertThat(ids).containsExactly(45L, 46L, 47L, 48L, 49L, 60L, 61L, 62L, 63L, 64L);
    }

    @Test
    void shouldHandleLargePageSize() throws Exception
    {
        // Query for all timestamps with page size larger than result set
        AlfrescoPagingItemReaderByTimestamp r = givenReader(1000L, 2000L, 100);

        List<Long> ids = readFully(r);

        // Should return all nodes in a single page
        assertThat(ids).hasSize(52); // Total nodes: 30-49 (20) + 60-91 (32) = 52 nodes
    }

    @Test
    void shouldHandleEmptyResultSet() throws Exception
    {
        // Query for timestamp range with no nodes
        AlfrescoPagingItemReaderByTimestamp r = givenReader(5000L, 6000L, 10);

        List<Long> ids = readFully(r);

        assertThat(ids).isEmpty();
    }

    @Test
    void shouldUseCursorPagination() throws Exception
    {
        // This test verifies that cursor-based pagination is used correctly
        AlfrescoPagingItemReaderByTimestamp r = givenReader(1000L, 1500L, 10);

        // Read first page
        List<AlfrescoNode> firstPage = new ArrayList<>();
        for (int i = 0; i < 10; i++)
        {
            AlfrescoNode node = r.read();
            if (node == null)
            {
                break;
            }
            firstPage.add(node);
        }

        assertThat(firstPage).hasSize(10);
        assertThat(firstPage.get(0).getId()).isEqualTo(30L);
        assertThat(firstPage.get(9).getId()).isEqualTo(39L);

        // Read second page - should use cursor from last ID (39)
        List<AlfrescoNode> secondPage = new ArrayList<>();
        for (int i = 0; i < 10; i++)
        {
            AlfrescoNode node = r.read();
            if (node == null)
            {
                break;
            }
            secondPage.add(node);
        }

        assertThat(secondPage).hasSize(10);
        // Should continue from ID 40 (after cursor 39)
        assertThat(secondPage.get(0).getId()).isEqualTo(40L);
        assertThat(secondPage.get(9).getId()).isEqualTo(49L);
    }

    @Test
    void shouldHandlePartialLastPage() throws Exception
    {
        // Query for timestamps that result in a partial last page
        AlfrescoPagingItemReaderByTimestamp r = givenReader(1000L, 1075L, 10);

        List<Long> ids = readFully(r);

        // Should return 8 nodes (30-37, timestamps 1000-1070)
        assertThat(ids).containsExactly(30L, 31L, 32L, 33L, 34L, 35L, 36L, 37L);
    }

    @Test
    void shouldStopOnPageReadFailure()
    {
        // Create a mock that fails immediately
        AlfrescoMetadataRepository failingRepository = mock(AlfrescoMetadataRepository.class);
        when(failingRepository.getAlfrescoNodes(any())).thenThrow(
                new RuntimeException("Simulated database connection failure"));

        AlfrescoPagingItemReaderByTimestamp reader = new AlfrescoPagingItemReaderByTimestamp(
                failingRepository, 1000L, 1500L);
        reader.setPageSize(10);

        // Attempt to read should propagate the database failure (reader stops on failure)
        assertThatThrownBy(reader::read).isInstanceOf(RuntimeException.class)
                .hasMessageContaining("database connection failure");
    }

    List<Long> readFully(ItemReader<AlfrescoNode> reader)
    {
        List<Long> allIds = new ArrayList<>();

        try
        {
            AlfrescoNode node = reader.read();
            while (node != null)
            {
                allIds.add(node.getId());
                node = reader.read();
            }
        }
        catch (Exception e)
        {
            throw new AssertionError("Failed to read all nodes", e);
        }

        return allIds;
    }

    private AlfrescoPagingItemReaderByTimestamp givenReader(long fromTs, long toTs, int pageSize)
    {
        AlfrescoPagingItemReaderByTimestamp reader = new AlfrescoPagingItemReaderByTimestamp(metadataRepository, fromTs, toTs);
        reader.setPageSize(pageSize);
        return reader;
    }
}
