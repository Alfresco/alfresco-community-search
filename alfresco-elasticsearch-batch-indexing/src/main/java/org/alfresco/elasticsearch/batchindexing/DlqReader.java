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
package org.alfresco.elasticsearch.batchindexing;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.OpenSearchException;
import org.opensearch.client.opensearch._types.SortOrder;
import org.opensearch.client.opensearch._types.mapping.FieldType;
import org.opensearch.client.opensearch.core.MgetRequest;
import org.opensearch.client.opensearch.core.MgetResponse;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.core.mget.MultiGetResponseItem;
import org.opensearch.client.opensearch.core.search.HitsMetadata;
import org.opensearch.client.opensearch.core.search.TotalHits;
import org.opensearch.client.opensearch.core.search.TotalHitsRelation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.alfresco.elasticsearch.batchindexing.model.DeadLetterDocument;

/**
 * Queries the dead-letter index for the {@code reindexDlq} drain. Unlike {@link DlqWriter}, a failed scan throws: reporting "nothing to drain" when the index is merely unreachable would silently drop the queue.
 * <p>
 * Only {@link DlqDrainService} calls this, so the query methods stay package-private.
 */
public class DlqReader
{
    private static final Logger LOGGER = LoggerFactory.getLogger(DlqReader.class);

    /** JSON field names on {@link DeadLetterDocument} used in queries/sorts (must match its {@code @JsonProperty} names). */
    private static final String FIELD_DB_ID = "dbId";
    private static final String FIELD_FAILURE_COUNT = "failureCount";
    private static final String FIELD_SOURCE_COMMIT_TIME_MS = "sourceCommitTimeMs";

    private final OpenSearchClient openSearchClient;
    private final String indexName;

    public DlqReader(OpenSearchClient openSearchClient, String indexName)
    {
        this.openSearchClient = openSearchClient;
        this.indexName = indexName;
    }

    /**
     * One scanned DLQ record together with its Elasticsearch document id (the id is {@code String.valueOf(dbId)} for node-level records, {@code window:<from>-<to>} for window-level ones — see {@link DeadLetterDocument}).
     */
    public record DlqEntry(String documentId, DeadLetterDocument document)
    {}

    /**
     * Bounded scan of drainable (node-level) DLQ records, oldest-tried first. Not a scroll — a single sized search, matching the scale a recovery run is expected to operate at.
     * <p>
     * Only records with a {@code dbId} are returned; window-level records ({@code window:<from>-<to>}) can't be drained by id and are excluded so they never consume the {@code maxItems} budget (see {@link #countWindowLevelEntries()} for visibility). Results are sorted by {@code failureCount} then {@code sourceCommitTimeMs} ascending: entries picked on a previous run that failed again have a higher {@code failureCount} and therefore rotate to the back, so repeated runs work through the whole queue instead of re-picking the same head. {@code unmapped_type} keeps the sort valid on an empty/freshly-created index.
     *
     * @param maxItems
     *            cap on the number of documents fetched; a warning is logged if the cap is hit, since more entries may remain
     * @return the scanned entries, empty if the DLQ index doesn't exist yet
     */
    List<DlqEntry> scanEntries(int maxItems)
    {
        try
        {
            SearchRequest request = new SearchRequest.Builder()
                    .index(indexName)
                    .size(maxItems)
                    .query(q -> q.exists(e -> e.field(FIELD_DB_ID)))
                    .sort(so -> so.field(f -> f.field(FIELD_FAILURE_COUNT).order(SortOrder.Asc).unmappedType(FieldType.Long)))
                    .sort(so -> so.field(f -> f.field(FIELD_SOURCE_COMMIT_TIME_MS).order(SortOrder.Asc).unmappedType(FieldType.Long)))
                    .build();
            SearchResponse<DeadLetterDocument> response = openSearchClient.search(request, DeadLetterDocument.class);

            if (scanMayHaveMoreEntries(response.hits(), maxItems))
            {
                LOGGER.warn("DLQ scan for index '{}' hit the maxItems cap ({}) — more entries may remain undrained this run.",
                        indexName, maxItems);
            }

            return response.hits().hits().stream()
                    .filter(hit -> hit.source() != null)
                    .map(hit -> new DlqEntry(hit.id(), hit.source()))
                    .toList();
        }
        catch (OpenSearchException e)
        {
            if (e.status() == 404)
            {
                LOGGER.debug("DLQ index '{}' not found — nothing to scan.", indexName);
                return List.of();
            }
            throw new DlqScanException("Failed to scan DLQ index '" + indexName + "'", e);
        }
        catch (Exception e)
        {
            throw new DlqScanException("Failed to scan DLQ index '" + indexName + "'", e);
        }
    }

    /**
     * Best-effort count of window-level records ({@code dbId} absent) that {@link #scanEntries} excludes. Purely for operator visibility — never throws, so a failed count can't abort a drain. Returns 0 when the index is missing or the count can't be obtained.
     */
    long countWindowLevelEntries()
    {
        try
        {
            return openSearchClient.count(c -> c.index(indexName)
                    .query(q -> q.bool(b -> b.mustNot(mn -> mn.exists(e -> e.field(FIELD_DB_ID)))))).count();
        }
        catch (Exception e)
        {
            if (LOGGER.isDebugEnabled())
            {
                LOGGER.debug("Could not count window-level DLQ entries in '{}': {}", indexName, e.getMessage());
            }
            return 0L;
        }
    }

    /**
     * Fetch specific DLQ documents by id (mget). Used by the drain's cleanup step to compare each drained entry's current {@code failureCount} against its pre-run snapshot — an exact per-id lookup, independent of scan order or the {@code maxItems} cap (a re-scan could miss a still-failing entry that sorted out of the capped window). Missing ids are simply absent from the result.
     */
    Map<String, DeadLetterDocument> readByIds(Collection<String> documentIds)
    {
        if (documentIds == null || documentIds.isEmpty())
        {
            return Map.of();
        }
        return mget(documentIds.stream().distinct().toList())
                .map(DlqReader::foundDocumentsById)
                .orElseGet(Map::of);
    }

    /** Empty when the index doesn't exist yet; throws on any other failure. */
    private Optional<MgetResponse<DeadLetterDocument>> mget(List<String> ids)
    {
        try
        {
            return Optional.of(openSearchClient.mget(
                    new MgetRequest.Builder().index(indexName).ids(ids).build(),
                    DeadLetterDocument.class));
        }
        catch (OpenSearchException e)
        {
            if (e.status() == 404)
            {
                return Optional.empty();
            }
            throw new DlqScanException("Failed to read DLQ entries by id from '" + indexName + "'", e);
        }
        catch (Exception e)
        {
            throw new DlqScanException("Failed to read DLQ entries by id from '" + indexName + "'", e);
        }
    }

    /** Skips misses and error items, so ids that are absent simply don't appear in the result. */
    private static Map<String, DeadLetterDocument> foundDocumentsById(MgetResponse<DeadLetterDocument> response)
    {
        Map<String, DeadLetterDocument> result = new HashMap<>();
        for (MultiGetResponseItem<DeadLetterDocument> item : response.docs())
        {
            if (item == null || !item.isResult())
            {
                continue;
            }
            var got = item.result();
            if (got != null && got.found() && got.source() != null)
            {
                result.put(got.id(), got.source());
            }
        }
        return result;
    }

    /**
     * Whether the scan likely left entries behind. Only true when the cap was reached <em>and</em> the reported total exceeds it — a page that returns exactly {@code maxItems} out of exactly {@code maxItems} total has drained everything. When the total is unavailable, or is a lower-bound ({@code Gte}, i.e. capped by {@code track_total_hits}), we assume more may remain.
     */
    static boolean scanMayHaveMoreEntries(HitsMetadata<?> hits, int maxItems)
    {
        if (hits.hits().size() < maxItems)
        {
            return false;
        }
        TotalHits total = hits.total();
        return total == null
                || total.relation() == TotalHitsRelation.Gte
                || total.value() > maxItems;
    }

    /** Thrown when a DLQ scan fails outright — unlike writes, a failed scan must not look like "nothing to drain". */
    public static class DlqScanException extends RuntimeException
    {
        public DlqScanException(String message, Throwable cause)
        {
            super(message, cause);
        }
    }
}
