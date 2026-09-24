/*-
 * #%L
 * Alfresco Elasticsearch Connector :: Batch Indexing Module
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

import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.PATH;
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.TYPE;
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.UNPREFIXED_PATH;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.core.MgetRequest;
import org.opensearch.client.opensearch.core.MgetResponse;
import org.opensearch.client.opensearch.core.mget.MultiGetResponseItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.alfresco.indexing.shared.ElasticsearchProperties;

/**
 * Batched mget helper that snapshots cascade-relevant fields ({@code PATH}, {@code UNPREFIXED_PATH}, {@code TYPE}) from source docs <em>before</em> the downstream writers overwrite them. Missing docs are simply absent from the returned map.
 */
public class SourceDocLookup
{
    private static final Logger LOGGER = LoggerFactory.getLogger(SourceDocLookup.class);

    private static final String PATH_DELIMITER = "/";

    private final OpenSearchClient openSearchClient;

    private final ElasticsearchProperties elasticsearchProperties;

    public SourceDocLookup(OpenSearchClient openSearchClient, ElasticsearchProperties elasticsearchProperties)
    {
        this.openSearchClient = openSearchClient;
        this.elasticsearchProperties = elasticsearchProperties;
    }

    /**
     * Snapshot of a source doc's cascade-relevant fields. {@code *Segment} = terminal path segment (rename); {@code paths} / {@code unprefixedPaths} = full arrays (category-delete PATH pruning); {@code type} = pre-delete TYPE (category detection).
     */
    public record SourceDocSnapshot(String prefixedSegment, String unprefixedSegment, String type,
            List<String> paths, List<String> unprefixedPaths)
    {}

    /**
     * Batched mget by node ref. Missing / not-found docs are absent from the returned map.
     */
    public Map<String, SourceDocSnapshot> lookup(Collection<String> nodeRefs)
    {
        if (nodeRefs == null || nodeRefs.isEmpty())
        {
            return Collections.emptyMap();
        }
        List<String> ids = nodeRefs.stream().distinct().toList();
        MgetRequest request = new MgetRequest.Builder()
                .index(elasticsearchProperties.getIndexName())
                .ids(ids)
                .sourceIncludes(PATH, UNPREFIXED_PATH, TYPE)
                .build();

        MgetResponse<Map> response;
        try
        {
            @SuppressWarnings("rawtypes")
            MgetResponse<Map> raw = openSearchClient.mget(request, Map.class);
            response = raw;
        }
        catch (IOException | RuntimeException ex)
        {
            LOGGER.warn("Cascade source-doc mget failed for {} id(s): {}. Affected cascades (rename enrichment and category-delete detection) will be skipped for this batch.",
                    ids.size(), ex.getMessage());
            return Collections.emptyMap();
        }

        Map<String, SourceDocSnapshot> result = new HashMap<>();
        for (MultiGetResponseItem<Map> item : response.docs())
        {
            if (item == null || !item.isResult())
            {
                continue;
            }
            var getResult = item.result();
            if (getResult == null || !getResult.found() || getResult.source() == null)
            {
                continue;
            }
            SourceDocSnapshot snapshot = extractSnapshot(getResult.source());
            if (snapshot != null)
            {
                result.put(getResult.id(), snapshot);
            }
        }
        return result;
    }

    private SourceDocSnapshot extractSnapshot(Map<?, ?> source)
    {
        String prefixed = firstTerminalSegment(source.get(PATH));
        String unprefixed = firstTerminalSegment(source.get(UNPREFIXED_PATH));
        String type = firstString(source.get(TYPE));
        List<String> paths = asStringList(source.get(PATH));
        List<String> unprefixedPaths = asStringList(source.get(UNPREFIXED_PATH));
        if (prefixed == null && unprefixed == null && type == null && paths.isEmpty() && unprefixedPaths.isEmpty())
        {
            return null;
        }
        return new SourceDocSnapshot(prefixed, unprefixed, type, paths, unprefixedPaths);
    }

    /** Normalises a raw source field into a List of non-blank strings. */
    private static List<String> asStringList(Object raw)
    {
        if (raw == null)
        {
            return List.of();
        }
        if (raw instanceof String s)
        {
            return s.isBlank() ? List.of() : List.of(s);
        }
        if (raw instanceof List<?> list)
        {
            List<String> out = new ArrayList<>(list.size());
            for (Object entry : list)
            {
                if (entry instanceof String s && !s.isBlank())
                {
                    out.add(s);
                }
            }
            return out;
        }
        return List.of();
    }

    /** Normalises a raw source field that may deserialize as String or single-element List. */
    private static String firstString(Object raw)
    {
        if (raw == null)
        {
            return null;
        }
        if (raw instanceof String s)
        {
            return s.isBlank() ? null : s;
        }
        if (raw instanceof List<?> list)
        {
            for (Object entry : list)
            {
                if (entry instanceof String s && !s.isBlank())
                {
                    return s;
                }
            }
        }
        return null;
    }

    /** Returns the terminal segment of the first non-blank path entry. */
    private String firstTerminalSegment(Object rawField)
    {
        if (rawField == null)
        {
            return null;
        }
        if (rawField instanceof List<?> list)
        {
            for (Object entry : list)
            {
                String segment = terminalSegmentOf(entry);
                if (segment != null)
                {
                    return segment;
                }
            }
            return null;
        }
        return terminalSegmentOf(rawField);
    }

    private String terminalSegmentOf(Object entry)
    {
        if (!(entry instanceof String path) || path.isBlank())
        {
            return null;
        }
        int idx = path.lastIndexOf(PATH_DELIMITER);
        if (idx < 0)
        {
            return path;
        }
        String tail = path.substring(idx + 1);
        return tail.isBlank() ? null : tail;
    }
}
