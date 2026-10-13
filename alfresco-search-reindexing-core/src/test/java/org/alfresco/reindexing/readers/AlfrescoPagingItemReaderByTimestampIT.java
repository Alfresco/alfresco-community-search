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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.infrastructure.item.ItemStreamReader;

import org.alfresco.database.connector.AlfrescoMetadataRepository;
import org.alfresco.database.connector.NodeParams;
import org.alfresco.database.connector.NodeParams.Paging;
import org.alfresco.database.connector.NodeParams.Range;
import org.alfresco.database.connector.model.AlfrescoNode;

/**
 * Integration test to verify timestamp-based keyset pagination. This test mocks the database and ensures: 1. OFFSET is always 0 (cursor-based pagination) 2. Results are consistent and complete 3. The ID cursor advances correctly
 */
@ExtendWith(MockitoExtension.class)
class AlfrescoPagingItemReaderByTimestampIT
{
    private static final Map<Long, Long> NODE_ID_TO_TIMESTAMP = createTestData();

    @Mock
    private AlfrescoMetadataRepository metadataRepository;

    private static Map<Long, Long> createTestData()
    {
        Map<Long, Long> data = new HashMap<>();
        for (long i = 1; i <= 100; i++)
        {
            data.put(i, 1000L + i * 10);
        }
        return data;
    }

    @SuppressWarnings("null")
    @BeforeEach
    void setUp()
    {
        when(metadataRepository.getAlfrescoNodes(any())).thenAnswer(invocationOnMock -> {
            final NodeParams params = invocationOnMock.getArgument(0, NodeParams.class);
            final Range timestamps = params.getTimestampRange().orElseThrow();
            final Paging paging = params.getPaging().orElseThrow();

            assertThat(paging.getPage()).as("OFFSET must always be 0 for keyset pagination")
                    .isZero();

            Long minId = params.getIdRange().map(Range::getBegin).orElse(0L);

            return NODE_ID_TO_TIMESTAMP.entrySet()
                    .stream()
                    .filter(entry -> timestamps.contains(entry.getValue()))
                    .filter(entry -> entry.getKey() >= minId)
                    .sorted(Map.Entry.comparingByKey())
                    .limit(paging.getPageSize())
                    .map(entry -> {
                        final AlfrescoNode node = new AlfrescoNode();
                        node.setId(entry.getKey());
                        node.setTimestamp(entry.getValue());
                        return node;
                    }).toList();
        });
    }

    @SuppressWarnings("null")
    @Test
    void shouldReadAllNodesUsingTimestampRangeWithKeysetPagination() throws Exception
    {
        // Use millisecond range that covers all test data (test data spans 1010 to 2010 ms)
        long fromTs = 1010L;
        long toTs = 2011L;

        LocalDateTime from = Instant.ofEpochMilli(fromTs).atZone(ZoneOffset.UTC).toLocalDateTime();
        LocalDateTime to = Instant.ofEpochMilli(toTs).atZone(ZoneOffset.UTC).toLocalDateTime();

        // Use small page size to force multiple pages and verify pagination works
        ItemStreamReader<AlfrescoNode> reader = AlfrescoPagingItemReaderBuilder
                .forTimestampRange(from, to)
                .name("testTimestampReader")
                .metadataRepository(metadataRepository)
                .pageSize(10)
                .build();

        // Verify correct reader type is created
        assertThat(reader).isInstanceOf(AlfrescoPagingItemReaderByTimestamp.class);

        // Read all nodes
        List<AlfrescoNode> allNodes = new ArrayList<>();
        AlfrescoNode node;
        while ((node = reader.read()) != null)
        {
            allNodes.add(node);
        }

        // Should have read nodes from the test data
        assertThat(allNodes).isNotEmpty().hasSize(100);

        // Verify nodes are ordered by ID (this is how cursor pagination works)
        List<Long> ids = allNodes.stream().map(AlfrescoNode::getId).toList();
        assertThat(ids).isSorted();

        // Verify all nodes are within the timestamp range
        allNodes.forEach(n -> {
            assertThat(n.getTimestamp()).isGreaterThanOrEqualTo(fromTs);
            assertThat(n.getTimestamp()).isLessThan(toTs);
        });

        // Verify no duplicates (cursor pagination should prevent this)
        assertThat(ids).doesNotHaveDuplicates();
    }

    @Test
    void shouldHandleSmallTimestampRange() throws Exception
    {
        // Query for timestamp range 1100-1500 (nodes 10-50)
        long fromTs = 1100L;
        long toTs = 1500L;

        LocalDateTime from = Instant.ofEpochMilli(fromTs).atZone(ZoneOffset.UTC).toLocalDateTime();
        LocalDateTime to = Instant.ofEpochMilli(toTs).atZone(ZoneOffset.UTC).toLocalDateTime();

        ItemStreamReader<AlfrescoNode> reader = AlfrescoPagingItemReaderBuilder
                .forTimestampRange(from, to)
                .name("testNarrowRangeReader")
                .metadataRepository(metadataRepository)
                .pageSize(5)
                .build();

        List<AlfrescoNode> nodes = new ArrayList<>();
        AlfrescoNode node;
        while ((node = reader.read()) != null)
        {
            nodes.add(node);
        }

        // Verify all results are within range
        nodes.forEach(n -> {
            assertThat(n.getTimestamp()).isGreaterThanOrEqualTo(fromTs);
            assertThat(n.getTimestamp()).isLessThan(toTs);
        });

        assertThat(nodes).isNotEmpty();
    }

    @Test
    void shouldReadMultiplePagesCorrectly() throws Exception
    {
        // Verify that pagination works correctly with ID cursor advancement
        long fromTs = 1050L; // Covers nodes 5-100
        long toTs = 2100L;

        LocalDateTime from = Instant.ofEpochMilli(fromTs).atZone(ZoneOffset.UTC).toLocalDateTime();
        LocalDateTime to = Instant.ofEpochMilli(toTs).atZone(ZoneOffset.UTC).toLocalDateTime();

        // Small page size forces multiple pages
        ItemStreamReader<AlfrescoNode> reader = AlfrescoPagingItemReaderBuilder
                .forTimestampRange(from, to)
                .name("multiPageReader")
                .metadataRepository(metadataRepository)
                .pageSize(7)
                .build();

        // Read first page
        List<AlfrescoNode> firstPage = new ArrayList<>();
        for (int i = 0; i < 7; i++)
        {
            AlfrescoNode node = reader.read();
            if (node != null)
            {
                firstPage.add(node);
            }
        }

        assertThat(firstPage).hasSize(7);
        assertThat(firstPage.get(0).getId()).isEqualTo(5L);
        assertThat(firstPage.get(6).getId()).isEqualTo(11L);

        // Read second page - should continue from ID 12
        List<AlfrescoNode> secondPage = new ArrayList<>();
        for (int i = 0; i < 7; i++)
        {
            AlfrescoNode node = reader.read();
            if (node != null)
            {
                secondPage.add(node);
            }
        }

        assertThat(secondPage).hasSize(7);
        assertThat(secondPage.get(0).getId()).isEqualTo(12L);
    }
}
