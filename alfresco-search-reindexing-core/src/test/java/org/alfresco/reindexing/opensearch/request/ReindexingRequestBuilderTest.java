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

import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.CM_CONTENT_TR_STATUS;

import java.io.Serializable;
import java.io.StringWriter;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opensearch.client.json.JsonpSerializable;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.client.opensearch.core.BulkRequest;
import org.opensearch.client.opensearch.core.bulk.BulkOperation;

import org.alfresco.elasticsearch.shared.filter.ContentIndexingFilter;
import org.alfresco.indexing.shared.ElasticsearchProperties;
import org.alfresco.indexing.shared.TransformStatus;
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
    private static final String EVENT_TIME = "2026-01-01T00:00:00Z";
    private static final long REINDEX_START = 9_999_999_999_999L;

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

    @Test
    void shouldUpsertMetadataAndContentForArchiveDelete()
    {
        // given an archive (trashcan) delete: NODE_DELETED carrying metadata + content, isPermanentlyDeleted=false
        RepoEvent<EventData<NodeResource>> archiveDelete = deleteEvent(false);

        // when
        BulkRequest bulkRequest = builder.buildBulkRequest(List.of(archiveDelete), 1000L);

        // then metadata upsert (ALIVE=false) + content upsert
        assertThat(bulkRequest.operations()).hasSize(2);
    }

    @Test
    void shouldWipeDocumentForPurgeDelete()
    {
        // given a purge delete: NODE_DELETED, isPermanentlyDeleted=true
        RepoEvent<EventData<NodeResource>> purgeDelete = deleteEvent(true);

        // when
        BulkRequest bulkRequest = builder.buildBulkRequest(List.of(purgeDelete), 1000L);

        // then single wipe operation, content upsert not applicable
        assertThat(bulkRequest.operations()).hasSize(1);
    }

    /**
     * Builds a {@code NODE_DELETED} event carrying content and a transform status, flagged as archive ({@code permanentlyDeleted=false}) or purge ({@code permanentlyDeleted=true}).
     */
    @Test
    void shouldClearContentAndTransformFieldsForExcludedNode()
    {
        var filteringBuilder = new ReindexingRequestBuilder(elasticsearchProperties, encodedFieldsExtractor,
                new ContentIndexingFilter(List.of("application/pdf"), 0L));

        BulkRequest bulkRequest = filteringBuilder.buildBulkRequest(List.of(contentEvent("application/pdf")), REINDEX_START);

        assertThat(exclusionScript(bulkRequest))
                .contains("ctx._source.remove('cm%3Acontent')")
                .contains("ctx._source.remove('cm%3Acontent%2Etr_status')")
                .contains("ctx._source.remove('cm%3Acontent%2Etr_ex')");
    }

    @Test
    void shouldStoreTheEventTimestampButDecideOnTheReindexStartTime()
    {
        var filteringBuilder = new ReindexingRequestBuilder(elasticsearchProperties, encodedFieldsExtractor,
                new ContentIndexingFilter(List.of("application/pdf"), 0L));
        long eventTimestamp = ZonedDateTime.parse(EVENT_TIME).toInstant().toEpochMilli();

        BulkRequest bulkRequest = filteringBuilder.buildBulkRequest(List.of(contentEvent("application/pdf")), REINDEX_START);
        String exclusion = serializeOperation(exclusionOperation(bulkRequest));

        // Storing the reindex start time would lock out later transform replies; deciding on the event timestamp would
        // skip nodes whose indexed content is newer than their own event, such as after a database restore.
        assertThat(exclusion)
                .contains("\"CONTENT_INDEXING_LAST_UPDATE\":" + eventTimestamp)
                .contains("\"CONTENT_EXCLUSION_CUTOFF\":" + REINDEX_START)
                .contains("params.CONTENT_EXCLUSION_CUTOFF");
    }

    @Test
    void shouldUpsertContentForIndexableNode()
    {
        BulkRequest bulkRequest = builder.buildBulkRequest(List.of(contentEvent("text/plain")), REINDEX_START);

        assertThat(bulkRequest.operations()).noneMatch(op -> scriptSource(op).contains("remove('cm%3Acontent')"));
    }

    @Test
    void shouldExcludeContentOverTheSizeLimit()
    {
        var filteringBuilder = new ReindexingRequestBuilder(elasticsearchProperties, encodedFieldsExtractor,
                new ContentIndexingFilter(List.of(), 10L));

        BulkRequest bulkRequest = filteringBuilder.buildBulkRequest(List.of(contentEvent("text/plain")), REINDEX_START);

        assertThat(exclusionScript(bulkRequest)).contains("ctx._source.remove('cm%3Acontent')");
    }

    private static String exclusionScript(BulkRequest bulkRequest)
    {
        return scriptSource(exclusionOperation(bulkRequest));
    }

    private static BulkOperation exclusionOperation(BulkRequest bulkRequest)
    {
        return bulkRequest.operations().stream()
                .filter(op -> scriptSource(op).contains("remove('cm%3Acontent')"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no content-exclusion operation was built"));
    }

    private static String scriptSource(BulkOperation operation)
    {
        return serializeOperation(operation);
    }

    private static String serializeOperation(BulkOperation operation)
    {
        var mapper = new JacksonJsonpMapper();
        var result = new StringBuilder(write(mapper, operation));
        operation.update()._serializables().forEachRemaining(payload -> {
            if (payload instanceof JsonpSerializable serializable)
            {
                result.append(write(mapper, serializable));
            }
        });
        return result.toString();
    }

    private static String write(JacksonJsonpMapper mapper, JsonpSerializable serializable)
    {
        var writer = new StringWriter();
        try (var generator = mapper.jsonProvider().createGenerator(writer))
        {
            serializable.serialize(generator, mapper);
        }
        return writer.toString();
    }

    /** A NODE_UPDATED event for a node that is already indexed and carries a transform status. */
    private static RepoEvent<EventData<NodeResource>> contentEvent(String mimeType)
    {
        Map<String, Serializable> properties = new java.util.HashMap<>();
        properties.put(CM_CONTENT_TR_STATUS, TransformStatus.NO_TRANSFORM.name());
        NodeResource resource = NodeResource.builder()
                .setId("node")
                .setContent(new ContentInfo(mimeType, 12L, "UTF-8"))
                .setProperties(properties)
                .build();
        return RepoEvent.<EventData<NodeResource>> builder()
                .setType(EventType.NODE_UPDATED.getType())
                .setData(EventData.<NodeResource> builder().setResource(resource).setResourceBefore(resource).build())
                .setId("event")
                .setTime(ZonedDateTime.parse(EVENT_TIME))
                .build();
    }

    private static RepoEvent<EventData<NodeResource>> deleteEvent(boolean permanentlyDeleted)
    {
        Map<String, Serializable> properties = new java.util.HashMap<>();
        properties.put(CM_CONTENT_TR_STATUS, TransformStatus.NO_TRANSFORM.name());
        NodeResource resource = NodeResource.builder()
                .setId("node")
                .setContent(new ContentInfo("application/pdf", 12L, "UTF-8"))
                .setProperties(properties)
                .setIsPermanentlyDeleted(permanentlyDeleted)
                .build();
        return RepoEvent.<EventData<NodeResource>> builder()
                .setType(EventType.NODE_DELETED.getType())
                .setData(EventData.<NodeResource> builder().setResource(resource).build())
                .setId("event")
                .setTime(ZonedDateTime.parse("2026-01-01T00:00:00Z"))
                .build();
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
