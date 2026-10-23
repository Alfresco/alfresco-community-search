/*-
 * #%L
 * Alfresco Search Community :: Batch Indexing
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
package org.alfresco.elasticsearch.batchindexing.cascade;

import static org.opensearch.client.opensearch._types.BuiltinScriptLanguage.Painless;

import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.CATEGORY_ANCESTOR;
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.PARENT;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.opensearch.client.json.JsonData;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.Conflicts;
import org.opensearch.client.opensearch._types.FieldValue;
import org.opensearch.client.opensearch._types.Refresh;
import org.opensearch.client.opensearch._types.Script;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.opensearch._types.query_dsl.QueryBuilders;
import org.opensearch.client.opensearch.core.UpdateByQueryRequest;
import org.opensearch.client.opensearch.core.UpdateByQueryResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.alfresco.elasticsearch.batchindexing.config.CascadeProperties;
import org.alfresco.indexing.shared.ElasticsearchProperties;

/**
 * Cascades a category delete by pruning the deleted UUID from every referring document's {@link #CATEGORIES_FIELD} ({@code cm%3Acategories}), {@code CATEGORY_ANCESTOR} and {@code PARENT} arrays, and stripping {@code PATH} / {@code UNPREFIXED_PATH} entries that contain any of the deleted category's paths, via a single {@code _update_by_query}. Mirrors live indexing's {@code PathUpdaterProcessor#removeDeletedCategoryFromChildNode}.
 * <p>
 * Idempotent; version conflicts are set to {@code proceed} so concurrent live-indexing writes don't abort the cascade.
 * <p>
 * <b>Fidelity gap</b>: live indexing recomputes {@code CATEGORY_ANCESTOR} from surviving categories, dropping upstream uuids exclusive to the deleted category's chain. We don't — options considered were either O(referring-docs &times; surviving-cats) mgets or only partially correct. Stale uuids are corrected by the next full reindex and do not affect query correctness.
 */
public class CategoryDeleteCascadeService
{
    private static final Logger LOGGER = LoggerFactory.getLogger(CategoryDeleteCascadeService.class);

    /** URL-encoded name of the raw category property as stored in Elasticsearch. */
    static final String CATEGORIES_FIELD = "cm%3Acategories";

    static final String SCRIPT_SOURCE = "String catId = params.catId;"
            + "boolean changed = false;"
            + "if (ctx._source.CATEGORY_ANCESTOR != null && ctx._source.CATEGORY_ANCESTOR.contains(catId)) {"
            + "  ctx._source.CATEGORY_ANCESTOR.removeIf(v -> catId.equals(v));"
            + "  changed = true;"
            + "}"
            + "if (ctx._source.PARENT != null && ctx._source.PARENT.contains(catId)) {"
            + "  ctx._source.PARENT.removeIf(v -> catId.equals(v));"
            + "  changed = true;"
            + "}"
            + "if (ctx._source['" + CATEGORIES_FIELD + "'] != null && ctx._source['" + CATEGORIES_FIELD + "'].contains(catId)) {"
            + "  ctx._source['" + CATEGORIES_FIELD + "'].removeIf(v -> catId.equals(v));"
            + "  changed = true;"
            + "}"
            + "if (ctx._source.PATH != null && params.catPaths != null && !params.catPaths.isEmpty()) {"
            + "  int before = ctx._source.PATH.size();"
            + "  ctx._source.PATH.removeIf(p -> {"
            + "    if (p == null) return false;"
            + "    for (int i = 0; i < params.catPaths.size(); i++) {"
            + "      String cp = params.catPaths.get(i);"
            + "      if (cp != null && p.contains(cp)) return true;"
            + "    }"
            + "    return false;"
            + "  });"
            + "  if (ctx._source.PATH.size() != before) changed = true;"
            + "}"
            + "if (ctx._source.UNPREFIXED_PATH != null && params.catUnprefixedPaths != null && !params.catUnprefixedPaths.isEmpty()) {"
            + "  int before = ctx._source.UNPREFIXED_PATH.size();"
            + "  ctx._source.UNPREFIXED_PATH.removeIf(p -> {"
            + "    if (p == null) return false;"
            + "    for (int i = 0; i < params.catUnprefixedPaths.size(); i++) {"
            + "      String cp = params.catUnprefixedPaths.get(i);"
            + "      if (cp != null && p.contains(cp)) return true;"
            + "    }"
            + "    return false;"
            + "  });"
            + "  if (ctx._source.UNPREFIXED_PATH.size() != before) changed = true;"
            + "}"
            + "if (!changed) { ctx.op = 'noop'; }";

    private final OpenSearchClient openSearchClient;

    private final ElasticsearchProperties elasticsearchProperties;

    private final CascadeProperties cascadeProperties;

    public CategoryDeleteCascadeService(OpenSearchClient openSearchClient,
            ElasticsearchProperties elasticsearchProperties,
            CascadeProperties cascadeProperties)
    {
        this.openSearchClient = openSearchClient;
        this.elasticsearchProperties = elasticsearchProperties;
        this.cascadeProperties = cascadeProperties;
    }

    /**
     * Apply the category-delete cascade for one source.
     *
     * @return number of referring docs mutated, or 0 when skipped.
     */
    public long apply(CategoryDeleteCascadeSource source)
    {
        if (source == null || source.descendantUuid() == null)
        {
            LOGGER.info("Cascade CATEGORY_DELETE skipped (missing uuid): nodeRef={}",
                    source == null ? null : source.nodeRef());
            return 0L;
        }

        Map<String, JsonData> params = new LinkedHashMap<>();
        params.put("catId", JsonData.of(source.descendantUuid()));
        List<String> catPaths = source.deletedCategoryPaths() == null ? List.of() : source.deletedCategoryPaths();
        List<String> catUnprefixedPaths = source.deletedCategoryUnprefixedPaths() == null ? List.of() : source.deletedCategoryUnprefixedPaths();
        params.put("catPaths", JsonData.of(catPaths));
        params.put("catUnprefixedPaths", JsonData.of(catUnprefixedPaths));

        Script script = new Script.Builder()
                .inline(b -> b.lang(l -> l.builtin(Painless)).params(params).source(SCRIPT_SOURCE))
                .build();

        FieldValue uuidFv = FieldValue.of(source.descendantUuid());
        Query byCategoryAncestor = QueryBuilders.term().field(CATEGORY_ANCESTOR).value(uuidFv).build().toQuery();
        Query byCategoriesField = QueryBuilders.term().field(CATEGORIES_FIELD).value(uuidFv).build().toQuery();
        Query byParent = QueryBuilders.term().field(PARENT).value(uuidFv).build().toQuery();
        Query referrersQuery = new Query.Builder()
                .bool(bb -> bb.should(byCategoryAncestor).should(byCategoriesField).should(byParent).minimumShouldMatch("1"))
                .build();

        UpdateByQueryRequest request = new UpdateByQueryRequest.Builder()
                .index(elasticsearchProperties.getIndexName())
                .query(referrersQuery)
                .script(script)
                .conflicts(Conflicts.Proceed)
                .refresh(cascadeProperties.isRefresh() ? Refresh.True : Refresh.False)
                .build();

        try
        {
            UpdateByQueryResponse response = openSearchClient.updateByQuery(request);
            long updated = response.updated() == null ? 0L : response.updated();
            LOGGER.info("Cascade CATEGORY_DELETE applied: nodeRef={} uuid={} updated={} noops={} failures={}",
                    source.nodeRef(), source.descendantUuid(), updated, response.noops(),
                    response.failures() == null ? 0 : response.failures().size());
            return updated;
        }
        catch (IOException | RuntimeException ex)
        {
            LOGGER.warn("Cascade CATEGORY_DELETE failed for nodeRef={} uuid={}: {}",
                    source.nodeRef(), source.descendantUuid(), ex.getMessage(), ex);
            return 0L;
        }
    }
}
