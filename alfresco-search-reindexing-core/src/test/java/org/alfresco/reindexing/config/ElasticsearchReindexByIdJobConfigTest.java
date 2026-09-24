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
package org.alfresco.reindexing.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.LongStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;
import org.springframework.batch.infrastructure.item.ItemReader;
import org.springframework.beans.factory.ObjectProvider;

import org.alfresco.database.connector.AlfrescoMetadataRepository;
import org.alfresco.database.connector.NodeParams;
import org.alfresco.database.connector.NodeParams.Paging;
import org.alfresco.database.connector.NodeParams.Range;
import org.alfresco.database.connector.model.AlfrescoNode;
import org.alfresco.reindexing.readers.PageReadFailureListener;

@ExtendWith(MockitoExtension.class)
@DisplayName("ElasticsearchReindexByIdJobConfig")
class ElasticsearchReindexByIdJobConfigTest
{
    @Mock
    private AlfrescoMetadataRepository metadataRepository;

    @Test
    @DisplayName("reindexByIdItemReader reads exactly the configured fromId..toId range")
    void readsConfiguredIdRange() throws Exception
    {
        List<Long> existingIds = LongStream.rangeClosed(10, 19).boxed().toList();
        when(metadataRepository.getAlfrescoNodes(any())).thenAnswer((Answer<List<AlfrescoNode>>) invocation -> {
            NodeParams params = invocation.getArgument(0, NodeParams.class);
            Range idRange = params.getIdRange().orElseThrow();
            Paging paging = params.getPaging().orElseThrow();
            return existingIds.stream()
                    .filter(idRange::contains)
                    .skip((long) paging.getPage() * paging.getPageSize())
                    .limit(paging.getPageSize())
                    .map(id -> {
                        AlfrescoNode node = new AlfrescoNode();
                        node.setId(id);
                        return node;
                    })
                    .toList();
        });

        ReindexingCoreProperties properties = properties("reindexByIds", 10L, 20L);
        properties.setPageSize(4);
        ElasticsearchReindexByIdJobConfig config = new ElasticsearchReindexByIdJobConfig(properties, new StandaloneReindexNodeRole());

        ItemReader<AlfrescoNode> reader = config.reindexByIdItemReader(metadataRepository, providerMock());

        List<Long> readIds = new ArrayList<>();
        AlfrescoNode node = reader.read();
        while (node != null)
        {
            readIds.add(node.getId());
            node = reader.read();
        }

        assertThat(readIds).containsExactlyElementsOf(existingIds);
    }

    @ParameterizedTest(name = "fromId={0}, toId={1}")
    @CsvSource({"20, 10", "20, 20", "-1, 10"})
    @DisplayName("reindexByIdItemReader rejects a range that can never read anything")
    void rejectsInvalidIdBounds(long fromId, long toId)
    {
        ElasticsearchReindexByIdJobConfig config = new ElasticsearchReindexByIdJobConfig(properties("reindexByIds", fromId, toId), new StandaloneReindexNodeRole());

        assertThatThrownBy(() -> config.reindexByIdItemReader(metadataRepository, providerMock()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fromId=" + fromId)
                .hasMessageContaining("toId=" + toId);
    }

    @Test
    @DisplayName("bounds are only checked for a reindexByIds run — other jobs leave them at their defaults")
    void ignoresIdBoundsForOtherJobs()
    {
        ReindexingCoreProperties properties = properties("reindexByDate", 0L, 0L);
        properties.setPageSize(10);
        ElasticsearchReindexByIdJobConfig config = new ElasticsearchReindexByIdJobConfig(properties, new StandaloneReindexNodeRole());

        assertThat(config.reindexByIdItemReader(metadataRepository, providerMock())).isNotNull();
    }

    private static ReindexingCoreProperties properties(String jobName, long fromId, long toId)
    {
        ReindexingCoreProperties properties = new ReindexingCoreProperties();
        properties.setJobName(jobName);
        properties.setFromId(fromId);
        properties.setToId(toId);
        return properties;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<PageReadFailureListener> providerMock()
    {
        return mock(ObjectProvider.class);
    }
}
