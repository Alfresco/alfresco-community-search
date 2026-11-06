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
import static org.alfresco.indexing.shared.utils.DeletionTypeChecker.isArchiveDelete;
import static org.alfresco.indexing.shared.utils.EsScriptUtils.reindexingUpdateScriptSource;
import static org.alfresco.indexing.shared.utils.EventUtils.getEventTimestamp;
import static org.alfresco.repo.event.v1.model.EventType.NODE_CREATED;
import static org.alfresco.repo.event.v1.model.EventType.NODE_DELETED;
import static org.alfresco.repo.event.v1.model.EventType.NODE_UPDATED;
import static org.alfresco.repo.event.v1.model.EventType.PERMISSION_UPDATED;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.opensearch.client.opensearch._types.Script;
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
 * Builds Elasticsearch bulk requests for the reindexing flow: upserts live and restored documents (ALIVE=true), soft-deletes archived documents while keeping their metadata and content (ALIVE=false), and strips purged or permanently deleted documents to a minimal tombstone.
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
            return createReindexingUpsertRequests(event, reindexingStartTime, true);
        }
        else if (NODE_DELETED.getType().equals(eventType))
        {
            // Archive (trashcan) deletes are backfilled with their full metadata + content but marked
            // ALIVE=false, so the node stays searchable through the deleted-nodes scope. Purge and legacy
            // deletes are reduced to a minimal tombstone with their properties stripped.
            if (isArchiveDelete(event))
            {
                return createReindexingUpsertRequests(event, reindexingStartTime, false);
            }
            return asList(createDeleteRequest(event));
        }
        else
        {
            LOGGER.warn("Not supported eventType found in event '{}'", event);
            return List.of();
        }
    }

    private List<BulkOperation> createReindexingUpsertRequests(RepoEvent event, long reindexingStartTime, boolean alive)
    {
        List<BulkOperation> result = new ArrayList<>();
        result.add(createReindexingMetadataUpsertRequest(event, reindexingStartTime, alive));
        createContentUpsertRequest(event).ifPresent(result::add);
        return result;
    }

    private BulkOperation createReindexingMetadataUpsertRequest(RepoEvent event, long reindexingStartTime, boolean alive)
    {
        var fieldsMapping = buildMetadataFields(event, reindexingStartTime, alive);
        return buildReindexUpsertOperation(event, fieldsMapping);
    }

    /**
     * Build the metadata field set for a node: the encoded node properties plus the reindex housekeeping fields (ALIVE, the metadata last-update timestamp and the reindexing start time used by the conflict-resolution script). Exposed so the merged writer can fold these into a single scripted upsert alongside the path fields.
     */
    public Map<String, Object> buildMetadataFields(RepoEvent event, long reindexingStartTime)
    {
        return buildMetadataFields(event, reindexingStartTime, true);
    }

    /**
     * Build the metadata field set for a node, flagging the document as live ({@code alive=true}) or soft-deleted ({@code alive=false}). Archived (trashcan) nodes are backfilled as soft-deleted so they remain searchable through the deleted-nodes scope.
     */
    public Map<String, Object> buildMetadataFields(RepoEvent event, long reindexingStartTime, boolean alive)
    {
        var encodedFields = encodedFieldsExtractor.extract(event);
        encodedFields.put(ALIVE, alive);
        encodedFields.put(METADATA_INDEXING_LAST_UPDATE, getEventTimestamp(event));
        encodedFields.put(REINDEXING_START_TIME, reindexingStartTime);
        return encodedFields.getEncodedMapping();
    }

    /**
     * Build a reindex scripted-upsert operation for the given fields. The script assigns every supplied field, guarded by the reindex conflict-resolution condition (noop when live indexing has touched the document since the reindex started). The upsert seeds the same fields when the document does not yet exist, so the operation never fails with {@code document_missing_exception}. Used both for the metadata-only op and, in merged mode, for the combined metadata+path field set.
     */
    public BulkOperation buildReindexUpsertOperation(RepoEvent event, Map<String, Object> fieldsMapping)
    {
        Script script = EsScriptUtils.buildInlineScript(fieldsMapping, reindexingUpdateScriptSource(fieldsMapping.keySet()));
        return createUpsertOperation(getIdFromEvent(event), fieldsMapping, script);
    }

    /**
     * Build the optional content transform-status upsert operation for a node (empty when the node has no content). Exposed so the merged writer can keep issuing it as its own bulk operation.
     */
    public Optional<BulkOperation> buildContentUpsertOperation(RepoEvent event)
    {
        return createContentUpsertRequest(event);
    }

    /**
     * Build the delete (soft-delete) operation for a node. Exposed so the merged writer, which replaces the metadata writer, can still handle {@code NODE_DELETED} events.
     */
    public BulkOperation buildDeleteOperation(RepoEvent event)
    {
        return createDeleteRequest(event);
    }
}
