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
package org.alfresco.reindexing.opensearch.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

import java.time.ZonedDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opensearch.client.opensearch.core.BulkRequest;

import org.alfresco.indexing.shared.ElasticsearchProperties;
import org.alfresco.indexing.shared.extractor.EncodedFields;
import org.alfresco.indexing.shared.extractor.EncodedFieldsExtractor;
import org.alfresco.repo.event.v1.model.ContentInfo;
import org.alfresco.repo.event.v1.model.EventData;
import org.alfresco.repo.event.v1.model.EventType;
import org.alfresco.repo.event.v1.model.NodeResource;
import org.alfresco.repo.event.v1.model.RepoEvent;

@ExtendWith(MockitoExtension.class)
class ReindexingRequestBuilderTest
{
    @Mock
    private ElasticsearchProperties elasticsearchProperties;
    @Mock
    private EncodedFieldsExtractor encodedFieldsExtractor;
    private ReindexingRequestBuilder builder;

    @BeforeEach
    void setUp()
    {
        lenient().when(elasticsearchProperties.getIndexName()).thenReturn("test_index");
        lenient().when(elasticsearchProperties.getRetryOnConflict()).thenReturn(5);
        lenient().when(encodedFieldsExtractor.extract(any())).thenReturn(new EncodedFields());
        builder = new ReindexingRequestBuilder(elasticsearchProperties, encodedFieldsExtractor);
    }

    @Test
    void shouldBuildReindexingBulkRequestForMixedEvents()
    {
        // given
        // simulate mixed event types covering all reindexing paths
        List<RepoEvent> repoEventList = List.of(
                event(EventType.NODE_CREATED, true, true),
                event(EventType.NODE_UPDATED, true, true),
                event(EventType.NODE_DELETED, false, true),
                event(EventType.PERMISSION_UPDATED, false, true));

        // when
        // build bulk request using the reindexing builder with reindexingStartTime
        BulkRequest bulkRequest = builder.buildBulkRequest(repoEventList, 1000L);

        // then
        // one operation per event as content upserts skipped because mock has no content status
        assertThat(bulkRequest.operations()).hasSize(4);
    }

    private static RepoEvent<EventData<NodeResource>> event(EventType type, boolean hasContent, boolean hasMetadata)
    {
        NodeResource.Builder resource = NodeResource.builder().setId("node");
        if (hasContent)
        {
            resource.setContent(new ContentInfo("application/pdf", 12L, "UTF-8"));
        }
        if (hasMetadata)
        {
            resource.setProperties(new java.util.HashMap<>());
        }
        NodeResource node = resource.build();
        EventData.Builder<NodeResource> data = EventData.<NodeResource> builder().setResource(node);
        if (type == EventType.NODE_UPDATED)
        {
            data.setResourceBefore(node);
        }
        return RepoEvent.<EventData<NodeResource>> builder()
                .setType(type.getType())
                .setData(data.build())
                .setId("event")
                .setTime(ZonedDateTime.parse("2026-01-01T00:00:00Z"))
                .build();
    }
}
