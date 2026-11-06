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
import static org.mockito.Mockito.lenient;

import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.CM_CONTENT_TR_STATUS;

import java.io.Serializable;
import java.io.StringWriter;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opensearch.client.json.JsonpSerializable;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.client.opensearch.core.bulk.BulkOperation;

import org.alfresco.elasticsearch.shared.filter.ContentIndexingFilter;
import org.alfresco.indexing.shared.ElasticsearchProperties;
import org.alfresco.indexing.shared.TransformStatus;
import org.alfresco.indexing.shared.extractor.EncodedFieldsExtractor;
import org.alfresco.repo.event.v1.model.ContentInfo;
import org.alfresco.repo.event.v1.model.EventData;
import org.alfresco.repo.event.v1.model.EventType;
import org.alfresco.repo.event.v1.model.NodeResource;
import org.alfresco.repo.event.v1.model.RepoEvent;

/**
 * Covers the one-argument {@link ReindexingRequestBuilder#buildContentUpsertOperation(RepoEvent)}, which is kept for binary and source compatibility.
 */
@ExtendWith(MockitoExtension.class)
class ReindexingRequestBuilderContentUpsertTest
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
        builder = new ReindexingRequestBuilder(elasticsearchProperties, encodedFieldsExtractor);
    }

    @Test
    void returnsEmptyWhenNodeHasNoTransformStatus()
    {
        assertThat(builder.buildContentUpsertOperation(contentEvent("text/plain", null))).isEmpty();
    }

    @Test
    void upsertsTransformStatusAndEventTimestamp()
    {
        long eventTimestamp = ZonedDateTime.parse(EVENT_TIME).toInstant().toEpochMilli();

        var operation = builder.buildContentUpsertOperation(contentEvent("text/plain", TransformStatus.NO_TRANSFORM));

        assertThat(operation).isPresent();
        assertThat(serialize(operation.get()))
                .contains("NO_TRANSFORM")
                .contains("\"CONTENT_INDEXING_LAST_UPDATE\":" + eventTimestamp)
                .doesNotContain("CONTENT_EXCLUSION_CUTOFF");
    }

    @Test
    void doesNotApplyContentExclusion()
    {
        // Excluded content still gets the transform-status upsert here. Exclusion is applied by the two-argument overload.
        var filteringBuilder = new ReindexingRequestBuilder(elasticsearchProperties, encodedFieldsExtractor,
                new ContentIndexingFilter(List.of("application/pdf"), 0L));

        var operation = filteringBuilder.buildContentUpsertOperation(contentEvent("application/pdf", TransformStatus.NO_TRANSFORM));

        assertThat(operation).isPresent();
        assertThat(serialize(operation.get())).doesNotContain("CONTENT_EXCLUSION_CUTOFF");
    }

    @Test
    void reindexClearsExcludedNodeWithCutoffFromReindexStart()
    {
        long eventTimestamp = ZonedDateTime.parse(EVENT_TIME).toInstant().toEpochMilli();
        var filteringBuilder = new ReindexingRequestBuilder(elasticsearchProperties, encodedFieldsExtractor,
                new ContentIndexingFilter(List.of("application/pdf"), 0L));

        var operation = filteringBuilder.buildContentUpsertOperation(contentEvent("application/pdf", TransformStatus.NO_TRANSFORM),
                REINDEX_START);

        assertThat(operation).isPresent();
        assertThat(serialize(operation.get()))
                .contains("remove('cm%3Acontent')")
                .contains("\"CONTENT_EXCLUSION_CUTOFF\":" + REINDEX_START)
                .contains("\"CONTENT_INDEXING_LAST_UPDATE\":" + eventTimestamp);
    }

    @Test
    void reindexClearsExcludedNodeWithoutTransformStatus()
    {
        var filteringBuilder = new ReindexingRequestBuilder(elasticsearchProperties, encodedFieldsExtractor,
                new ContentIndexingFilter(List.of("application/pdf"), 0L));

        var operation = filteringBuilder.buildContentUpsertOperation(contentEvent("application/pdf", null), REINDEX_START);

        assertThat(operation).isPresent();
        assertThat(serialize(operation.get())).contains("remove('cm%3Acontent')");
    }

    @Test
    void reindexUpsertsIndexableNodeWithTransformStatus()
    {
        var operation = builder.buildContentUpsertOperation(contentEvent("text/plain", TransformStatus.NO_TRANSFORM), REINDEX_START);

        assertThat(operation).isPresent();
        assertThat(serialize(operation.get())).doesNotContain("CONTENT_EXCLUSION_CUTOFF");
    }

    @Test
    void reindexReturnsEmptyForIndexableNodeWithoutTransformStatus()
    {
        assertThat(builder.buildContentUpsertOperation(contentEvent("text/plain", null), REINDEX_START)).isEmpty();
    }

    private static String serialize(BulkOperation operation)
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

    private static RepoEvent<EventData<NodeResource>> contentEvent(String mimeType, TransformStatus transformStatus)
    {
        Map<String, Serializable> properties = new HashMap<>();
        if (transformStatus != null)
        {
            properties.put(CM_CONTENT_TR_STATUS, transformStatus.name());
        }
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
}
