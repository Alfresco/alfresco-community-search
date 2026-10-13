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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.alfresco.database.connector.PrefixUriMapper;
import org.alfresco.database.connector.model.ChildAssocMetaData;
import org.alfresco.database.connector.model.Path;
import org.alfresco.elasticsearch.shared.translator.AlfrescoQualifiedNameTranslator;
import org.alfresco.indexing.shared.ElasticsearchProperties;
import org.alfresco.indexing.shared.model.NodeResourceDto;
import org.alfresco.metrics.ReindexingMetrics;
import org.alfresco.reindexing.config.PrefixNamespaceConfig;
import org.alfresco.reindexing.model.ReindexingEvent;
import org.alfresco.repo.event.v1.model.NodeResource;

/**
 * Builds the path/ancestry field set for a reindexed node. Extracted from {@link ElasticsearchPathWriter} so the same computation can be reused by the merged metadata+path writer, which folds these fields into a single scripted upsert (see {@code alfresco.reindex.writersMergedEnabled}). The reindexing start time is supplied by the caller so this class stays free of Spring Batch execution-context state.
 */
@SuppressWarnings("PMD.CouplingBetweenObjects")
public class ReindexPathFieldsBuilder
{
    private static final Logger LOGGER = LoggerFactory.getLogger(ReindexPathFieldsBuilder.class);

    private final ElasticsearchProperties elasticsearchProperties;
    private final PrefixNamespaceConfig prefixNamespaceConfig;
    /** Optional. Nullable in tests; wired in production so unresolved namespaces are counted. */
    private final ReindexingMetrics reindexingMetrics;

    public ReindexPathFieldsBuilder(ElasticsearchProperties elasticsearchProperties, PrefixNamespaceConfig prefixNamespaceConfig)
    {
        this(elasticsearchProperties, prefixNamespaceConfig, null);
    }

    public ReindexPathFieldsBuilder(ElasticsearchProperties elasticsearchProperties, PrefixNamespaceConfig prefixNamespaceConfig,
            ReindexingMetrics reindexingMetrics)
    {
        this.elasticsearchProperties = elasticsearchProperties;
        this.prefixNamespaceConfig = prefixNamespaceConfig;
        this.reindexingMetrics = reindexingMetrics;
    }

    /**
     * Build the {@link NodeResourceDto} path-update requests for every event in the chunk that carries paths. Events without paths (e.g. deletes) are skipped, matching the pre-existing path-writer behaviour.
     */
    public List<NodeResourceDto> buildRequestList(List<? extends ReindexingEvent> items, long reindexingStartTime)
    {
        return items
                .stream()
                .filter(reindexingEvent -> reindexingEvent.getAllPaths() != null)
                .map(event -> buildNodeResource(event, reindexingStartTime))
                .collect(Collectors.toList());
    }

    /**
     * Build just the path/ancestry field map for a single event. Used by the merged writer, which merges these fields into the metadata upsert instead of issuing a separate path bulk.
     */
    public Map<String, Object> buildFields(ReindexingEvent reindexingEvent, long reindexingStartTime)
    {
        return buildNodeResource(reindexingEvent, reindexingStartTime).getFields();
    }

    public NodeResourceDto buildNodeResource(ReindexingEvent reindexingEvent, long reindexingStartTime)
    {
        NodeResource nodeResource = reindexingEvent.getData().getResource();
        Set<Path> paths = reindexingEvent.getAllPaths();

        // Resolve categories once — shared by PARENT, STANDARD_ANCESTOR, and cm:categories.
        // rawCategoryIds: cm:categories DB property (may include stale UUIDs of deleted categories).
        // validCategories: rawCategoryIds ∩ category paths reaching CM_CATEGORY_ROOT (live only).
        Set<String> rawCategoryIds = categoriesForNode(nodeResource);
        Set<String> validCategories = resolveValidCategories(paths, rawCategoryIds);

        Map<String, Object> fields = new HashMap<>();
        fields.put(REINDEXING_START_TIME, reindexingStartTime);
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
     * <p>
     * Known false positive: the database stores the empty namespace as the literal {@code .empty}, and the prefix map is keyed on {@code ""}, so every node in the empty namespace warns here. The indexed path is unaffected, because a bare name is what an empty prefix produces anyway.
     * <p>
     * Mapping {@code .empty} before the lookup would silence it, but only for URIs. A localname of {@code .empty} is a real file name and must be left alone.
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
