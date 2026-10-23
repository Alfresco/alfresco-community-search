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
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.STANDARD_ANCESTOR;

import java.io.IOException;
import java.util.LinkedHashMap;
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
 * Cascades a rename to descendants by swapping the old path segment for the new one in each descendant's {@code PATH} / {@code UNPREFIXED_PATH} arrays.
 * <p>
 * Descendants are matched via a term query on {@code STANDARD_ANCESTOR} ∪ {@code CATEGORY_ANCESTOR}. The update runs as a single {@code _update_by_query} with a Painless script doing delimiter-bracketed replacement per entry — idempotent, and version conflicts are set to {@code proceed} so concurrent live-indexing writes don't abort the cascade.
 */
public class RenameCascadeService
{
    private static final Logger LOGGER = LoggerFactory.getLogger(RenameCascadeService.class);

    /** Painless: replace the delimited old segment with the delimited new segment in each PATH / UNPREFIXED_PATH entry. Delimiters prevent substring collisions with sibling segments. */
    static final String SCRIPT_SOURCE = "String oldP = '/' + params.oldPrefixed + '/';"
            + "String newP = '/' + params.newPrefixed + '/';"
            + "String oldU = '/' + params.oldUnprefixed + '/';"
            + "String newU = '/' + params.newUnprefixed + '/';"
            + "boolean changed = false;"
            + "if (ctx._source.PATH != null) {"
            + "  for (int i = 0; i < ctx._source.PATH.size(); i++) {"
            + "    String v = ctx._source.PATH[i];"
            + "    if (v != null && v.indexOf(oldP) >= 0) {"
            + "      ctx._source.PATH[i] = v.replace(oldP, newP);"
            + "      changed = true;"
            + "    }"
            + "  }"
            + "}"
            + "if (ctx._source.UNPREFIXED_PATH != null) {"
            + "  for (int i = 0; i < ctx._source.UNPREFIXED_PATH.size(); i++) {"
            + "    String v = ctx._source.UNPREFIXED_PATH[i];"
            + "    if (v != null && v.indexOf(oldU) >= 0) {"
            + "      ctx._source.UNPREFIXED_PATH[i] = v.replace(oldU, newU);"
            + "      changed = true;"
            + "    }"
            + "  }"
            + "}"
            + "if (!changed) { ctx.op = 'noop'; }";

    private final OpenSearchClient openSearchClient;

    private final ElasticsearchProperties elasticsearchProperties;

    private final CascadeProperties cascadeProperties;

    public RenameCascadeService(OpenSearchClient openSearchClient,
            ElasticsearchProperties elasticsearchProperties,
            CascadeProperties cascadeProperties)
    {
        this.openSearchClient = openSearchClient;
        this.elasticsearchProperties = elasticsearchProperties;
        this.cascadeProperties = cascadeProperties;
    }

    /**
     * Apply the rename cascade for one source. Skipped (info log) when the descriptor is incomplete or the rename is a no-op.
     *
     * @return number of descendant docs mutated, or 0 when skipped.
     */
    @SuppressWarnings({"PMD.CyclomaticComplexity", "PMD.GuardLogStatement"})
    public long apply(RenameCascadeSource source)
    {
        if (source == null || source.descendantUuid() == null
                || source.oldPrefixedSegment() == null || source.newPrefixedSegment() == null
                || source.oldUnprefixedSegment() == null || source.newUnprefixedSegment() == null)
        {
            LOGGER.info("Cascade RENAME skipped (incomplete descriptor): nodeRef={}", source == null ? null : source.nodeRef());
            return 0L;
        }
        if (source.isNoop())
        {
            LOGGER.debug("Cascade RENAME noop (segments unchanged): nodeRef={}", source.nodeRef());
            return 0L;
        }

        Map<String, JsonData> params = new LinkedHashMap<>();
        params.put("oldPrefixed", JsonData.of(source.oldPrefixedSegment()));
        params.put("newPrefixed", JsonData.of(source.newPrefixedSegment()));
        params.put("oldUnprefixed", JsonData.of(source.oldUnprefixedSegment()));
        params.put("newUnprefixed", JsonData.of(source.newUnprefixedSegment()));

        Script script = new Script.Builder()
                .inline(b -> b.lang(l -> l.builtin(Painless)).params(params).source(SCRIPT_SOURCE))
                .build();

        FieldValue uuidFv = FieldValue.of(source.descendantUuid());
        Query byStandardAncestor = QueryBuilders.term().field(STANDARD_ANCESTOR).value(uuidFv).build().toQuery();
        Query byCategoryAncestor = QueryBuilders.term().field(CATEGORY_ANCESTOR).value(uuidFv).build().toQuery();
        Query descendantsQuery = new Query.Builder()
                .bool(bb -> bb.should(byStandardAncestor).should(byCategoryAncestor).minimumShouldMatch("1"))
                .build();

        UpdateByQueryRequest request = new UpdateByQueryRequest.Builder()
                .index(elasticsearchProperties.getIndexName())
                .query(descendantsQuery)
                .script(script)
                .conflicts(Conflicts.Proceed)
                .refresh(cascadeProperties.isRefresh() ? Refresh.True : Refresh.False)
                .build();

        try
        {
            UpdateByQueryResponse response = openSearchClient.updateByQuery(request);
            long updated = response.updated() == null ? 0L : response.updated();
            LOGGER.info("Cascade RENAME applied: nodeRef={} uuid={} '{}'->'{}' updated={} noops={} failures={}",
                    source.nodeRef(), source.descendantUuid(),
                    source.oldPrefixedSegment(), source.newPrefixedSegment(),
                    updated, response.noops(),
                    response.failures() == null ? 0 : response.failures().size());
            return updated;
        }
        catch (IOException | RuntimeException ex)
        {
            LOGGER.warn("Cascade RENAME failed for nodeRef={} uuid={}: {}",
                    source.nodeRef(), source.descendantUuid(), ex.getMessage(), ex);
            return 0L;
        }
    }
}
