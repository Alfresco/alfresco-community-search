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

import static java.util.Arrays.asList;

import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.ALIVE;
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.METADATA_INDEXING_LAST_UPDATE;
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.REINDEXING_START_TIME;
import static org.alfresco.indexing.shared.utils.EsScriptUtils.reindexingUpdateScriptSource;
import static org.alfresco.indexing.shared.utils.EventUtils.getEventTimestamp;
import static org.alfresco.repo.event.v1.model.EventType.NODE_CREATED;
import static org.alfresco.repo.event.v1.model.EventType.NODE_DELETED;
import static org.alfresco.repo.event.v1.model.EventType.NODE_UPDATED;
import static org.alfresco.repo.event.v1.model.EventType.PERMISSION_UPDATED;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.opensearch.client.opensearch.core.BulkRequest;
import org.opensearch.client.opensearch.core.bulk.BulkOperation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.alfresco.indexing.shared.ElasticsearchProperties;
import org.alfresco.indexing.shared.extractor.EncodedFieldsExtractor;
import org.alfresco.indexing.shared.opensearch.request.AbstractElasticsearchRequestBuilder;
import org.alfresco.indexing.shared.utils.EsScriptUtils;
import org.alfresco.repo.event.v1.model.RepoEvent;

/**
 * Builds Elasticsearch bulk requests for the reindexing flow. It handles restoring documents marked as deleted (ALIVE=false) in Elasticsearch.
 */
public class ReindexingRequestBuilder extends AbstractElasticsearchRequestBuilder
{
    private static final Logger LOGGER = LoggerFactory.getLogger(ReindexingRequestBuilder.class);

    public ReindexingRequestBuilder(ElasticsearchProperties elasticsearchProperties, EncodedFieldsExtractor encodedFieldsExtractor)
    {
        super(elasticsearchProperties, encodedFieldsExtractor);
    }

    public BulkRequest buildBulkRequest(List<RepoEvent> events, long reindexingStartTime)
    {
        return new BulkRequest.Builder().operations(events.stream()
                .map(event -> createReindexingRequest(event, reindexingStartTime))
                .flatMap(Collection::stream)
                .toList())
                .build();
    }

    private List<BulkOperation> createReindexingRequest(RepoEvent event, long reindexingStartTime)
    {
        String eventType = event.getType();
        if (NODE_CREATED.getType().equals(eventType) || NODE_UPDATED.getType().equals(eventType) || PERMISSION_UPDATED.getType().equals(eventType))
        {
            List<BulkOperation> result = new ArrayList<>();
            result.add(createReindexingMetadataUpsertRequest(event, reindexingStartTime));
            createContentUpsertRequest(event).ifPresent(result::add);
            return result;
        }
        else if (NODE_DELETED.getType().equals(eventType))
        {
            return asList(createDeleteRequest(event));
        }
        else
        {
            LOGGER.warn("Not supported eventType found in event '{}'", event);
            return List.of();
        }
    }

    private BulkOperation createReindexingMetadataUpsertRequest(RepoEvent event, long reindexingStartTime)
    {
        var encodedFields = encodedFieldsExtractor.extract(event);
        encodedFields.put(ALIVE, true);
        encodedFields.put(METADATA_INDEXING_LAST_UPDATE, getEventTimestamp(event));
        encodedFields.put(REINDEXING_START_TIME, reindexingStartTime);
        var fieldsMapping = encodedFields.getEncodedMapping();
        var script = EsScriptUtils.buildInlineScript(fieldsMapping, reindexingUpdateScriptSource(fieldsMapping.keySet()));
        return createUpsertOperation(getIdFromEvent(event), fieldsMapping, script);
    }
}
