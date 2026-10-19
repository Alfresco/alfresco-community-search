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

package org.alfresco.reindexing.opensearch.impl;

import static org.alfresco.indexing.shared.utils.DeletionTypeChecker.isArchiveDelete;
import static org.alfresco.indexing.shared.utils.EsScriptUtils.reindexingUpdateScriptSource;
import static org.alfresco.repo.event.v1.model.EventType.NODE_CREATED;
import static org.alfresco.repo.event.v1.model.EventType.NODE_DELETED;
import static org.alfresco.repo.event.v1.model.EventType.NODE_UPDATED;
import static org.alfresco.repo.event.v1.model.EventType.PERMISSION_UPDATED;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.ErrorCause;
import org.opensearch.client.opensearch._types.FieldValue;
import org.opensearch.client.opensearch._types.OpType;
import org.opensearch.client.opensearch._types.OpenSearchException;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.opensearch._types.query_dsl.QueryBuilders;
import org.opensearch.client.opensearch.core.BulkRequest;
import org.opensearch.client.opensearch.core.BulkResponse;
import org.opensearch.client.opensearch.core.IndexRequest;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.core.UpdateRequest;
import org.opensearch.client.opensearch.core.bulk.BulkOperation;
import org.opensearch.client.opensearch.core.bulk.BulkResponseItem;
import org.opensearch.client.opensearch.core.bulk.UpdateOperation;
import org.opensearch.client.opensearch.core.search.Hit;

import org.alfresco.indexing.shared.ElasticsearchProperties;
import org.alfresco.indexing.shared.model.BulkItemFailure;
import org.alfresco.indexing.shared.model.BulkRequestDto;
import org.alfresco.indexing.shared.model.NodeResourceDto;
import org.alfresco.indexing.shared.utils.EsScriptUtils;
import org.alfresco.reindexing.model.ReindexingEvent;
import org.alfresco.reindexing.opensearch.ElasticSearchUpdateOperations;
import org.alfresco.reindexing.opensearch.request.ReindexingRequestBuilder;
import org.alfresco.reindexing.writers.FailureSummary;
import org.alfresco.reindexing.writers.ReindexPathFieldsBuilder;
import org.alfresco.repo.event.v1.model.RepoEvent;

@Slf4j
@SuppressWarnings({"PMD.CouplingBetweenObjects", "PMD.GodClass"})
public class ElasticSearchUpdateOperationsImpl implements ElasticSearchUpdateOperations
{
    private final OpenSearchClient openSearchClient;

    private final ElasticsearchProperties elasticsearchProperties;

    private final ReindexingRequestBuilder reindexingRequestBuilder;

    /**
     * When true the path bulk is built as a scripted upsert so it no longer fails with {@code document_missing_exception} if the metadata document is not yet present.
     */
    private final boolean pathUpsertEnabled;

    /**
     * Builds the path field set. Only required for {@link #getMergedBulkRequest}; nullable for the metadata/path-only entry points (and their unit tests).
     */
    private final ReindexPathFieldsBuilder reindexPathFieldsBuilder;

    /**
     * Aggregates terminal bulk-item failures by writer + error type for the end-of-step failure summary. Nullable (defaults to a disabled instance) so existing constructors and unit tests are unaffected.
     */
    private final FailureSummary failureSummary;

    public ElasticSearchUpdateOperationsImpl(OpenSearchClient openSearchClient,
            ElasticsearchProperties elasticsearchProperties, ReindexingRequestBuilder reindexingRequestBuilder)
    {
        this(openSearchClient, elasticsearchProperties, reindexingRequestBuilder, false, null);
    }

    public ElasticSearchUpdateOperationsImpl(OpenSearchClient openSearchClient,
            ElasticsearchProperties elasticsearchProperties, ReindexingRequestBuilder reindexingRequestBuilder,
            boolean pathUpsertEnabled, ReindexPathFieldsBuilder reindexPathFieldsBuilder)
    {
        this(openSearchClient, elasticsearchProperties, reindexingRequestBuilder, pathUpsertEnabled,
                reindexPathFieldsBuilder, null);
    }

    public ElasticSearchUpdateOperationsImpl(OpenSearchClient openSearchClient,
            ElasticsearchProperties elasticsearchProperties, ReindexingRequestBuilder reindexingRequestBuilder,
            boolean pathUpsertEnabled, ReindexPathFieldsBuilder reindexPathFieldsBuilder, FailureSummary failureSummary)
    {
        this.openSearchClient = openSearchClient;
        this.elasticsearchProperties = elasticsearchProperties;
        this.reindexingRequestBuilder = reindexingRequestBuilder;
        this.pathUpsertEnabled = pathUpsertEnabled;
        this.reindexPathFieldsBuilder = reindexPathFieldsBuilder;
        this.failureSummary = failureSummary != null ? failureSummary : new FailureSummary(false);
    }

    @Override
    public BulkRequestDto updateElasticSearchWithBulkRequests(List<NodeResourceDto> nodeResourceDtos, int retryCount,
            long retryDelay) throws IOException, InterruptedException
    {
        Map<String, UpdateRequest> updateRequestMap = buildRequestMap(nodeResourceDtos);
        return write(updateRequestMap, retryCount, retryDelay);
    }

    @Override
    public void indexNode(String id, String index, Map<String, Object> source) throws IOException
    {
        IndexRequest indexRequest = new IndexRequest.Builder().id(id)
                .index(index)
                .opType(OpType.Create)
                .document(source)
                .build();
        openSearchClient.index(indexRequest);
    }

    @Override
    public Map<String, Object> getDescriptorFromElasticsearch(String descriptorType) throws Exception
    {
        SearchRequest searchRequest = new SearchRequest.Builder().index(elasticsearchProperties.getIndexName())
                .query(new Query.Builder().match(QueryBuilders.match().field("TYPE")
                        .query(FieldValue.of(descriptorType)).build()).build())
                .build();

        SearchResponse<Map> searchResponse;
        try
        {
            searchResponse = openSearchClient.search(searchRequest, Map.class);
        }
        catch (OpenSearchException e)
        {
            // First-run race: the index does not exist yet. The caller treats
            // null as "no descriptor", which triggers a write that auto-creates
            // the index. Anything else (mapping errors, auth) still propagates.
            if (e.getMessage() != null && e.getMessage().contains("index_not_found_exception"))
            {
                return null;
            }
            throw e;
        }

        List<Map<String, Object>> results = new ArrayList<>();
        for (Hit<Map> m : searchResponse.hits().hits())
        {
            results.add(m.source());
        }

        if (results.size() > 1)
        {
            throw new RuntimeException(
                    "Elasticsearch index '" + elasticsearchProperties.getIndexName() + "' includes " + results.size()
                            + " documents from TYPE " + descriptorType + " but it's expected to include only 1. "
                            + "Review the index in order to fix this redundancy.");
        }

        return results.size() == 1 ? results.get(0) : null;
    }

    @Override
    public BulkRequestDto getBulkRequest(List<RepoEvent> repoEvents, long reindexingStartTime, int retryCount,
            long retryDelay) throws IOException, InterruptedException
    {
        BulkRequestDto dto = new BulkRequestDto();
        BulkRequest initialRequest = reindexingRequestBuilder.buildBulkRequest(repoEvents, reindexingStartTime);
        List<BulkOperation> ops = initialRequest.operations();
        dto.setNumberOfActions(ops.size());
        if (ops.isEmpty())
        {
            return dto;
        }
        if (log.isTraceEnabled())
        {
            log.trace("Sending metadata bulk request containing {} actions", ops.size());
        }
        writeWithRetry(dto, ops, retryCount, retryDelay, "metadata");
        return dto;
    }

    @Override
    @SuppressWarnings("rawtypes")
    public BulkRequestDto getMergedBulkRequest(List<ReindexingEvent> events, long reindexingStartTime, int retryCount,
            long retryDelay) throws IOException, InterruptedException
    {
        if (reindexPathFieldsBuilder == null)
        {
            throw new IllegalStateException(
                    "Merged reindex writer requires a ReindexPathFieldsBuilder; none was configured.");
        }
        BulkRequestDto dto = new BulkRequestDto();
        List<BulkOperation> ops = new ArrayList<>();
        int nodeCount = 0;
        for (ReindexingEvent event : events)
        {
            RepoEvent repoEvent = event.getRepoEvent();
            String eventType = repoEvent.getType();
            if (isMetadataUpsertType(eventType))
            {
                // One scripted upsert carrying BOTH metadata and path fields. Both writers already
                // targeted the same document id under the same (metadata-timestamp) guard, so folding
                // the field sets is behaviour-preserving; putting the path fields last mirrors the
                // sequential order (path writer ran after metadata) for any overlapping keys.
                ops.add(reindexingRequestBuilder.buildReindexUpsertOperation(repoEvent,
                        buildCombinedFields(event, reindexingStartTime, true)));
                reindexingRequestBuilder.buildContentUpsertOperation(repoEvent).ifPresent(ops::add);
                nodeCount++;
            }
            else if (NODE_DELETED.getType().equals(eventType))
            {
                if (isArchiveDelete(repoEvent))
                {
                    // Archive (trashcan) delete: keep the document with its metadata + content but
                    // mark it soft-deleted (ALIVE=false), mirroring the non-merged writer path.
                    ops.add(reindexingRequestBuilder.buildReindexUpsertOperation(repoEvent,
                            buildCombinedFields(event, reindexingStartTime, false)));
                    reindexingRequestBuilder.buildContentUpsertOperation(repoEvent).ifPresent(ops::add);
                }
                else
                {
                    ops.add(reindexingRequestBuilder.buildDeleteOperation(repoEvent));
                }
                nodeCount++;
            }
            else
            {
                log.warn("Not supported eventType found in event '{}'", repoEvent);
            }
        }
        dto.setNumberOfActions(nodeCount);
        if (ops.isEmpty())
        {
            return dto;
        }
        if (log.isTraceEnabled())
        {
            log.trace("Sending merged metadata+path bulk request containing {} actions for {} nodes", ops.size(), nodeCount);
        }
        writeWithRetry(dto, ops, retryCount, retryDelay, "merged");
        return dto;
    }

    private static boolean isMetadataUpsertType(String eventType)
    {
        return NODE_CREATED.getType().equals(eventType)
                || NODE_UPDATED.getType().equals(eventType)
                || PERMISSION_UPDATED.getType().equals(eventType);
    }

    /**
     * Fold a node's metadata fields (live or soft-deleted, per {@code alive}) and, when available, its path fields into a single field set for a combined scripted upsert.
     */
    @SuppressWarnings("rawtypes")
    private Map<String, Object> buildCombinedFields(ReindexingEvent event, long reindexingStartTime, boolean alive)
    {
        RepoEvent repoEvent = event.getRepoEvent();
        Map<String, Object> combinedFields = new HashMap<>(
                reindexingRequestBuilder.buildMetadataFields(repoEvent, reindexingStartTime, alive));
        if (event.getAllPaths() != null)
        {
            combinedFields.putAll(reindexPathFieldsBuilder.buildFields(event, reindexingStartTime));
        }
        return combinedFields;
    }

    private Map<String, UpdateRequest> buildRequestMap(List<NodeResourceDto> items)
    {
        return items.stream()
                .filter(nodeResource -> nodeResource.getResourceId() != null)
                .collect(Collectors.toMap(this::getNodeResourceId, this::createUpdateRequest));
    }

    private UpdateRequest createUpdateRequest(NodeResourceDto nodeResourceDto)
    {
        var script = EsScriptUtils.buildInlineScript(nodeResourceDto.getFields(), reindexingUpdateScriptSource(nodeResourceDto.getFields().keySet()));
        var builder = new UpdateRequest.Builder<>().index(nodeResourceDto.getIndexName())
                .id(nodeResourceDto.getResourceId())
                .script(script);
        if (pathUpsertEnabled)
        {
            // Seed the doc with the path fields on create (upsert) so an update against a
            // not-yet-created document does not fail with document_missing_exception, and let ES
            // retry version conflicts (metadata + path racing on the same doc) in-place via
            // retryOnConflict instead of consuming a full writer retry round. Note the update path
            // still uses reindexingUpdateScriptSource (guarded by the metadata timestamp), i.e. the
            // pre-existing reindex path-update behaviour; only the upsert/retryOnConflict is added here.
            builder.upsert(nodeResourceDto.getFields())
                    .retryOnConflict(elasticsearchProperties.getRetryOnConflict());
        }
        return builder.build();
    }

    /**
     * Path-update bulk. Turns the input into {@link UpdateOperation}s and hands off to the shared retry loop.
     */
    private BulkRequestDto write(Map<String, UpdateRequest> updateRequestMap, int retryCount, long retryDelay)
            throws IOException, InterruptedException
    {
        BulkRequestDto dto = new BulkRequestDto();
        dto.setNumberOfActions(updateRequestMap.size());
        if (updateRequestMap.isEmpty())
        {
            return dto;
        }
        List<BulkOperation> ops = updateRequestMap.values().stream()
                .map(this::toBulkOperation)
                .toList();
        writeWithRetry(dto, ops, retryCount, retryDelay, "path");
        return dto;
    }

    private BulkOperation toBulkOperation(UpdateRequest request)
    {
        var builder = new UpdateOperation.Builder<>()
                .id(request.id())
                .index(request.index())
                .script(request.script());
        if (request.upsert() != null)
        {
            builder.upsert(request.upsert());
        }
        if (request.retryOnConflict() != null)
        {
            builder.retryOnConflict(request.retryOnConflict());
        }
        return builder.build()._toBulkOperation();
    }

    /**
     * Shared write-with-retry loop. Each round sends the pending operations, splits the response into successes, retryable failures (see {@link BulkItemErrorClassifier}) and terminal failures. Terminal errors are recorded on the DTO immediately and never retried. Retryable errors are re-sent until they succeed, become terminal, or the round budget runs out.
     * <p>
     * Retries are position-based: the ES bulk API returns response items in the same order as the request, so we correlate a failed item back to its operation by index. Same-id operations (e.g. a metadata upsert plus a content upsert for the same node) must be tracked independently — id-keying would collapse them.
     * <p>
     * On return: {@link BulkRequestDto#getFailuresCount()} counts the items that still failed on the final attempt (terminal + retryable-exhausted). {@link BulkRequestDto#getInitialFailuresCount()} counts everything that failed on the very first attempt — retained for callers that want to distinguish retry activity from ultimate failure in their metrics.
     */
    private void writeWithRetry(BulkRequestDto dto, List<BulkOperation> initialOps, int retryCount,
            long retryDelay, String label) throws IOException, InterruptedException
    {
        List<BulkOperation> pending = initialOps;
        int firstRoundErrorsCount = 0;
        boolean firstRound = true;

        for (int round = 0; round <= retryCount; round++)
        {
            if (round > 0)
            {
                Thread.sleep(retryDelay);
            }
            if (log.isTraceEnabled())
            {
                log.trace("{} bulk round {} sending {} actions", label, round, pending.size());
            }
            BulkResponse response = openSearchClient.bulk(new BulkRequest.Builder().operations(pending).build());
            List<BulkResponseItem> items = response.items();

            List<BulkOperation> nextPending = new ArrayList<>();
            int roundErrors = 0;
            for (int i = 0; i < items.size(); i++)
            {
                BulkResponseItem item = items.get(i);
                if (item.error() == null)
                {
                    continue;
                }
                roundErrors++;
                if (round < retryCount && BulkItemErrorClassifier.isRetryable(item))
                {
                    nextPending.add(pending.get(i));
                    log.warn("Node {} {} bulk transient failure (round {}, retryable): {}.",
                            item.id(), label, round, item.error().reason());
                }
                else
                {
                    recordFailure(dto, item, label, round);
                }
            }
            if (firstRound)
            {
                firstRoundErrorsCount = roundErrors;
                firstRound = false;
            }
            if (nextPending.isEmpty())
            {
                break;
            }
            pending = nextPending;
        }
        dto.setFailuresCount(dto.getFailures().size());
        dto.setInitialFailuresCount(firstRoundErrorsCount);
    }

    private void recordFailure(BulkRequestDto dto, BulkResponseItem item, String label, int round)
    {
        ErrorCause errorCause = item.error();
        log.warn("Node {} {} bulk failure (round {}, terminal): {}.",
                item.id(), label, round, errorCause.reason());
        if (log.isDebugEnabled())
        {
            log.debug("{} bulk error cause: {}", label, errorCause.toJsonString());
        }
        failureSummary.record(label, errorCause.type());
        dto.addFailure(new BulkItemFailure(item.id(), errorCause.type(), errorCause.reason()));
    }

    private String getNodeResourceId(NodeResourceDto nodeResource)
    {
        return nodeResource.getResourceId();
    }
}
