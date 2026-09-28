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
package org.alfresco.reindexing.writers;

import static java.util.Optional.ofNullable;
import static java.util.function.Predicate.not;
import static java.util.stream.Collectors.toSet;

import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.CATEGORY_ANCESTOR;
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.CM_CATEGORIES;
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.CM_CATEGORY_ROOT;
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.PARENT;
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.PATH;
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.PATH_INDEXING_LAST_UPDATE;
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.PRIMARY_HIERARCHY;
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.PRIMARY_PARENT;
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.REINDEXING_START_TIME;
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.STANDARD_ANCESTOR;
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.UNPREFIXED_PATH;
import static org.alfresco.indexing.shared.utils.EventUtils.getEventTimestamp;
import static org.alfresco.indexing.shared.utils.ResourceTools.categoriesForNode;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.item.ItemStreamException;
import org.springframework.batch.infrastructure.item.ItemStreamWriter;
import org.springframework.beans.factory.annotation.Autowired;

import org.alfresco.database.connector.PrefixUriMapper;
import org.alfresco.database.connector.model.ChildAssocMetaData;
import org.alfresco.database.connector.model.Path;
import org.alfresco.elasticsearch.shared.translator.AlfrescoQualifiedNameTranslator;
import org.alfresco.indexing.shared.ElasticsearchProperties;
import org.alfresco.indexing.shared.model.BulkItemFailure;
import org.alfresco.indexing.shared.model.BulkRequestDto;
import org.alfresco.indexing.shared.model.NodeResourceDto;
import org.alfresco.metrics.MetricsConstants;
import org.alfresco.metrics.ReindexingMetrics;
import org.alfresco.reindexing.config.PrefixNamespaceConfig;
import org.alfresco.reindexing.config.ReindexingCoreProperties;
import org.alfresco.reindexing.model.ReindexingEvent;
import org.alfresco.reindexing.opensearch.ElasticSearchUpdateOperations;
import org.alfresco.repo.event.v1.model.EventData;
import org.alfresco.repo.event.v1.model.NodeResource;
import org.alfresco.repo.event.v1.model.RepoEvent;

@SuppressWarnings("PMD.CouplingBetweenObjects")
public class ElasticsearchPathWriter implements ItemStreamWriter<ReindexingEvent>
{

    private static final Logger LOGGER = LoggerFactory.getLogger(ElasticsearchPathWriter.class);
    private static final long DEFAULT_REINDEXING_START_TIME = Long.MAX_VALUE;

    private final ElasticsearchProperties elasticsearchProperties;
    private final ReindexingCoreProperties elasticsearchReindexProperties;
    private final ElasticSearchUpdateOperations elasticSearchUpdateOperations;
    private final PrefixNamespaceConfig prefixNamespaceConfig;
    /** Optional. Nullable in tests; wired in production via SharedBatchProcessingConfig. */
    private final ReindexingMetrics reindexingMetrics;

    /** Optional. Nullable in tests; wired in production via SharedBatchProcessingConfig. */
    private final ChunkWriteMetricsAccumulator writeMetricsAccumulator;
    /** Optional. When null the writer only logs a WARN on per-item failure and moves on. */
    private final PathBulkItemFailureListener failureListener;
    private Map<String, Long> counters;

    private ExecutionContext executionContext;

    // Autowired so Spring picks this ctor deterministically when the class is registered as a bean
    // directly (e.g. ElasticsearchPathWriterIT lists it in @SpringBootTest.classes). Production wiring
    // goes through SharedBatchProcessingConfig, which calls the 6-arg ctor explicitly.
    @Autowired
    public ElasticsearchPathWriter(ElasticsearchProperties elasticsearchProperties,
            ReindexingCoreProperties elasticsearchReindexProperties,
            PrefixNamespaceConfig prefixNamespaceConfig,
            ElasticSearchUpdateOperations elasticSearchUpdateOperations,
            ChunkWriteMetricsAccumulator writeMetricsAccumulator)
    {
        this(elasticsearchProperties, elasticsearchReindexProperties, prefixNamespaceConfig,
                elasticSearchUpdateOperations, writeMetricsAccumulator, null);
    }

    public ElasticsearchPathWriter(ElasticsearchProperties elasticsearchProperties,
            ReindexingCoreProperties elasticsearchReindexProperties,
            PrefixNamespaceConfig prefixNamespaceConfig,
            ElasticSearchUpdateOperations elasticSearchUpdateOperations,
            ChunkWriteMetricsAccumulator writeMetricsAccumulator,
            PathBulkItemFailureListener failureListener)
    {
        this(elasticsearchProperties, elasticsearchReindexProperties, prefixNamespaceConfig,
                elasticSearchUpdateOperations, writeMetricsAccumulator, failureListener, null);
    }

    public ElasticsearchPathWriter(ElasticsearchProperties elasticsearchProperties,
            ReindexingCoreProperties elasticsearchReindexProperties,
            PrefixNamespaceConfig prefixNamespaceConfig,
            ElasticSearchUpdateOperations elasticSearchUpdateOperations,
            ChunkWriteMetricsAccumulator writeMetricsAccumulator,
            PathBulkItemFailureListener failureListener,
            ReindexingMetrics reindexingMetrics)
    {
        this.reindexingMetrics = reindexingMetrics;
        this.elasticsearchProperties = elasticsearchProperties;
        this.elasticsearchReindexProperties = elasticsearchReindexProperties;
        this.prefixNamespaceConfig = prefixNamespaceConfig;
        this.elasticSearchUpdateOperations = elasticSearchUpdateOperations;
        this.writeMetricsAccumulator = writeMetricsAccumulator;
        this.failureListener = failureListener;
    }

    @Override
    public void write(Chunk<? extends ReindexingEvent> chunk) throws Exception
    {
        List<? extends ReindexingEvent> items = List.copyOf(chunk.getItems());
        BulkRequestDto result = elasticSearchUpdateOperations.updateElasticSearchWithBulkRequests(
                buildRequestList(items),
                elasticsearchReindexProperties.getWriterRetryCount(),
                elasticsearchReindexProperties.getWriterRetryDelay());

        // Counters reflect the state of the index after the retry loop: successes include items that
        // needed a retry to land, failures count only items still missing from the index.
        long finalFailedCount = result.getFailuresCount();
        long indexedCount = result.getNumberOfActions() - finalFailedCount;
        this.counters = Map.of("INDEX_COUNTER", indexedCount, "FAILED_COUNTER", finalFailedCount);

        if (writeMetricsAccumulator != null)
        {
            writeMetricsAccumulator.recordCount(MetricsConstants.PATH, indexedCount, finalFailedCount);
        }

        if (finalFailedCount > 0)
        {
            LOGGER.warn("Path write: {} items failed in ES bulk request after internal retries. ", finalFailedCount);
            dispatchFailures(items, result.getFailures());
        }
    }

    /**
     * Correlate per-item bulk failures back to the source {@link ReindexingEvent} in the chunk and hand each one off to the optional listener. Same shape as the metadata writer's dispatch (one dedupe pass by nodeRef, one guarded call per unique item).
     */
    private void dispatchFailures(List<? extends ReindexingEvent> items, List<BulkItemFailure> failures)
    {
        if (failureListener == null || failures.isEmpty())
        {
            return;
        }
        Map<String, ReindexingEvent> byNodeRef = new HashMap<>();
        for (ReindexingEvent item : items)
        {
            String nodeRef = extractNodeRef(item);
            if (nodeRef != null)
            {
                byNodeRef.putIfAbsent(nodeRef, item);
            }
        }
        Set<String> alreadyDispatched = new HashSet<>();
        for (BulkItemFailure failure : failures)
        {
            String nodeRef = failure.documentId();
            if (nodeRef == null || !alreadyDispatched.add(nodeRef))
            {
                continue;
            }
            ReindexingEvent event = byNodeRef.get(nodeRef);
            Long dbId = event == null ? null : event.getNodeDbId();
            Long commitTimeMs = extractCommitTimeMs(event);
            try
            {
                failureListener.onPathBulkItemFailure(dbId, nodeRef, commitTimeMs, failure.errorType(), failure.errorReason());
            }
            catch (RuntimeException ex)
            {
                // Isolate the listener — a broken DLQ (or metrics sink) must not fail the writer.
                LOGGER.warn("PathBulkItemFailureListener threw for nodeRef={}: {}", nodeRef, ex.getMessage(), ex);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static String extractNodeRef(ReindexingEvent item)
    {
        RepoEvent<EventData<NodeResource>> repoEvent = item.getRepoEvent();
        if (repoEvent == null || repoEvent.getData() == null)
        {
            return null;
        }
        NodeResource resource = repoEvent.getData().getResource();
        return resource == null ? null : resource.getId();
    }

    private static Long extractCommitTimeMs(ReindexingEvent item)
    {
        if (item == null || item.getTime() == null)
        {
            return null;
        }
        return item.getTime().toInstant().toEpochMilli();
    }

    private List<NodeResourceDto> buildRequestList(List<? extends ReindexingEvent> items)
    {
        return items
                .stream()
                .filter(reindexingEvent -> reindexingEvent.getAllPaths() != null)
                .map(this::createNodeResource)
                .collect(Collectors.toList());
    }

    private NodeResourceDto createNodeResource(ReindexingEvent reindexingEvent)
    {
        NodeResource nodeResource = (NodeResource) reindexingEvent.getData().getResource();
        Set<Path> paths = reindexingEvent.getAllPaths();

        // Resolve categories once — shared by PARENT, STANDARD_ANCESTOR, and cm:categories.
        // rawCategoryIds: cm:categories DB property (may include stale UUIDs of deleted categories).
        // validCategories: rawCategoryIds ∩ category paths reaching CM_CATEGORY_ROOT (live only).
        Set<String> rawCategoryIds = categoriesForNode(nodeResource);
        Set<String> validCategories = resolveValidCategories(paths, rawCategoryIds);

        Map<String, Object> fields = new HashMap<>();
        fields.put(REINDEXING_START_TIME, executionContext.getLong(REINDEXING_START_TIME, DEFAULT_REINDEXING_START_TIME));
        fields.put(PATH_INDEXING_LAST_UPDATE, getEventTimestamp(reindexingEvent));

        addPathFields(fields, paths);
        addParentAndAncestorFields(fields, paths, rawCategoryIds, validCategories);
        addCategoryFields(fields, paths, validCategories);
        addPrimaryHierarchy(buildPrimaryHierarchy(reindexingEvent), fields);

        NodeResourceDto dto = new NodeResourceDto();
        dto.setResourceId(nodeResource.getId());
        dto.setIndexName(elasticsearchProperties.getIndexName());
        dto.setFields(fields);
        return dto;
    }

    private void addPathFields(Map<String, Object> fields, Set<Path> paths)
    {
        paths.forEach(this::reportUnmappedElements);
        fields.put(PATH, paths.stream()
                .map(path -> path.asString(prefixNamespaceConfig.prefixUriMapper()))
                .collect(toSet()));
        fields.put(UNPREFIXED_PATH, paths.stream()
                .map(Path::asStringNoPrefix)
                .collect(toSet()));
    }

    /**
     * An element whose namespace has no mapping is written as a bare name, which is indistinguishable from an element that legitimately has no prefix. Only a missing mapping is reported; a mapping to the empty prefix is correct.
     */
    private void reportUnmappedElements(Path path)
    {
        PrefixUriMapper prefixUriMapper = prefixNamespaceConfig.prefixUriMapper();
        path.getNodeMetadata().stream()
                .filter(element -> prefixUriMapper.getUriMapping(element.getUri()) == null)
                .forEach(element -> {
                    LOGGER.warn("No prefix mapping for namespace {}; path element {} is indexed without its prefix",
                            element.getUri(), element.getName());
                    if (reindexingMetrics != null)
                    {
                        reindexingMetrics.reindexUnresolvedNamespace(element.getUri());
                    }
                });
    }

    private Set<String> resolveValidCategories(Set<Path> paths, Set<String> rawCategoryIds)
    {
        // Resolved category UUIDs: paths that actually reach CM_CATEGORY_ROOT.
        // A deleted category has no parent associations left, so its path never reaches
        // CM_CATEGORY_ROOT and is therefore excluded here.
        Set<String> resolvedCategoryIds = paths.stream()
                .filter(this::isCategoryPath)
                .map(path -> path.getNodeMetadata().getLast().getParentUuid())
                .filter(Objects::nonNull)
                .collect(toSet());

        // Valid categories = intersection of raw DB property and resolved paths.
        // Intersection with rawCategoryIds prevents false positives for cm:category nodes
        // whose own path passes through CM_CATEGORY_ROOT but carry no cm:categories property.
        // Intersection with resolvedCategoryIds drops stale refs to deleted categories.
        return rawCategoryIds.stream()
                .filter(resolvedCategoryIds::contains)
                .collect(toSet());
    }

    private void addParentAndAncestorFields(Map<String, Object> fields, Set<Path> paths,
            Set<String> rawCategoryIds, Set<String> validCategories)
    {
        // PARENT: take last UUID from all paths, strip any raw category UUID (avoids stale partial paths from
        // deleted/archived categories bleeding in as standard parents), then add back valid categories only.
        Set<String> parents = Stream.concat(
                paths.stream()
                        .map(Path::getNodeMetadata)
                        .filter(not(Collection::isEmpty))
                        .map(nodeMetadata -> nodeMetadata.getLast().getParentUuid())
                        .filter(uuid -> !rawCategoryIds.contains(uuid)),
                validCategories.stream()).collect(toSet());
        fields.put(PARENT, parents);

        // STANDARD_ANCESTOR: exclude raw category UUIDs so that stale partial paths from deleted categories
        // do not pollute the standard ancestor list.
        Set<String> standardAncestors = paths.stream()
                .filter(not(this::isCategoryPath))
                .flatMap(this::extractAncestorsFromPath)
                .filter(uuid -> !rawCategoryIds.contains(uuid))
                .collect(toSet());
        fields.put(STANDARD_ANCESTOR, standardAncestors);
    }

    private void addCategoryFields(Map<String, Object> fields, Set<Path> paths, Set<String> validCategories)
    {
        Set<String> categoryAncestors = paths.stream()
                .filter(this::isCategoryPath)
                .flatMap(this::extractAncestorsFromPath)
                .collect(toSet());
        fields.put(CATEGORY_ANCESTOR, categoryAncestors);
        fields.put(AlfrescoQualifiedNameTranslator.encode(CM_CATEGORIES), validCategories);
    }

    private List<String> buildPrimaryHierarchy(ReindexingEvent reindexingEvent)
    {
        List<String> primaryHierarchy = reindexingEvent.getPrimaryPath()
                .getNodeMetadata()
                .stream()
                .map(ChildAssocMetaData::getParentUuid)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        Collections.reverse(primaryHierarchy);
        return primaryHierarchy;
    }

    private boolean isCategoryPath(Path path)
    {
        return path.asString(prefixNamespaceConfig.prefixUriMapper()).contains(CM_CATEGORY_ROOT);
    }

    private Stream<String> extractAncestorsFromPath(Path path)
    {
        return path.getNodeMetadata().stream().map(ChildAssocMetaData::getParentUuid);
    }

    @Override
    public void open(ExecutionContext executionContext) throws ItemStreamException
    {
        this.executionContext = executionContext;
    }

    @Override
    public void update(ExecutionContext executionContext) throws ItemStreamException
    {
        // No intermediate state to persist between chunks
    }

    @Override
    public void close() throws ItemStreamException
    {
        if (counters != null)
        {
            LOGGER.info("Total number of updated nodes with Path (includes retried items) {} ", counters.getOrDefault("INDEX_COUNTER", 0L));
            LOGGER.info("Total number of nodes with failed Path update (includes retried items) {} ", counters.getOrDefault("FAILED_COUNTER", 0L));
            this.counters = null;
        }
    }

    /**
     * Add the primary hierarchy and primary parent to the list of fields.
     *
     * @param primaryHierarchy
     *            The primary hierarchy (may be null or empty).
     * @param fields
     *            The fields to update.
     */
    private void addPrimaryHierarchy(List<String> primaryHierarchy, Map<String, Object> fields)
    {
        ofNullable(primaryHierarchy)
                .filter(not(Collection::isEmpty))
                .ifPresent(hierarchy -> {
                    fields.put(PRIMARY_HIERARCHY, hierarchy);
                    hierarchy.stream().findFirst().ifPresent(primaryParent -> fields.put(PRIMARY_PARENT, primaryParent));
                });
    }
}
