/*-
 * #%L
 * Alfresco Search Community :: OpenSearch Client :: Test
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
package org.alfresco.indexing.shared;

import static java.time.Duration.ofSeconds;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.CoreMatchers.equalTo;

import static org.alfresco.indexing.shared.util.ElasticsearchQueryUtils.ALL_DOCS_QUERY;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.Conflicts;
import org.opensearch.client.opensearch._types.OpType;
import org.opensearch.client.opensearch._types.Refresh;
import org.opensearch.client.opensearch._types.query_dsl.MatchAllQuery;
import org.opensearch.client.opensearch.core.CountRequest;
import org.opensearch.client.opensearch.core.DeleteByQueryRequest;
import org.opensearch.client.opensearch.core.GetRequest;
import org.opensearch.client.opensearch.core.GetResponse;
import org.opensearch.client.opensearch.core.IndexRequest;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.core.get.GetResult;
import org.opensearch.client.opensearch.core.search.Hit;
import org.opensearch.client.opensearch.core.search.TotalHits;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import org.alfresco.indexing.shared.model.ElasticSearchQueryDto;
import org.alfresco.indexing.shared.util.ElasticsearchQueryUtils;

@Slf4j
public abstract class ElasticsearchTestTemplate
{

    @Autowired
    private OpenSearchClient openSearchClient;

    @Value("${elasticsearch.indexName}")
    protected String index;

    // set to false in a subclass setup method to avoid index cleanup after each tests
    protected boolean shouldCleanIndex = true;

    @AfterEach
    public void cleanUpElasticsearchIndex()
    {
        if (shouldCleanIndex)
        {
            await().timeout(ofSeconds(30))
                    .pollDelay(Duration.ZERO)
                    .pollInterval(ofSeconds(1))
                    .until(() -> {
                        deleteAllSync();
                        return count();
                    }, equalTo(0L));
        }
    }

    protected long count()
    {
        try
        {
            var countRequest = new CountRequest.Builder().index(index).build();
            var response = openSearchClient.count(countRequest);
            return response.count();
        }
        catch (IOException e)
        {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    protected Optional<Map<String, Object>> getDoc(String id)
    {
        try
        {
            GetRequest getRequest = new GetRequest.Builder().index(index).id(id).build();
            var response = openSearchClient.get(getRequest, Map.class);
            return Optional.of(response)
                    .filter(GetResponse::found)
                    .map(GetResult::source);
        }
        catch (IOException e)
        {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    protected List<Map<String, Object>> searchDocs(ElasticSearchQueryDto elasticSearchQueryDto)
    {
        var response = searchByQuery(elasticSearchQueryDto);
        return response.hits().hits().stream()
                .map(Hit::source)
                .map(mapping -> (Map<String, Object>) mapping)
                .toList();
    }

    protected long getHitsNumberBy(ElasticSearchQueryDto elasticSearchQueryDto)
    {
        var response = searchByQuery(elasticSearchQueryDto);
        return Optional.ofNullable(response.hits().total())
                .map(TotalHits::value)
                .orElse(-1L);
    }

    protected long getHitsNumberByAllDocs()
    {
        return getHitsNumberBy(ALL_DOCS_QUERY);
    }

    @SuppressWarnings("unchecked")
    protected List<String> getDocFieldValues(ElasticSearchQueryDto elasticSearchQueryDto, String field)
    {
        var response = searchByQuery(elasticSearchQueryDto);
        return response.hits().hits().stream()
                .findFirst()
                .map(h -> (Map<String, Object>) h.source())
                .map(m -> (List<String>) m.get(field))
                .orElseGet(List::of);
    }

    protected void indexNodeSync(String id, Map<String, Object> source) throws IOException
    {
        var indexRequest = new IndexRequest.Builder<>().id(id)
                .index(this.index)
                .opType(OpType.Create)
                .document(source)
                .refresh(Refresh.True)
                .build();
        openSearchClient.index(indexRequest);
    }

    private SearchResponse<?> searchByQuery(ElasticSearchQueryDto elasticSearchQueryDto)
    {
        try
        {
            var query = ElasticsearchQueryUtils.toOpensearchQuery(elasticSearchQueryDto);
            var searchRequest = new SearchRequest.Builder()
                    .index(index)
                    .query(query)
                    .build();
            return openSearchClient.search(searchRequest, Object.class);
        }
        catch (IOException e)
        {
            throw new IllegalStateException(e);
        }
    }

    private void deleteAllSync()
    {
        try
        {
            openSearchClient.indices().refresh(r -> r.index(index));
            var request = new DeleteByQueryRequest.Builder()
                    .index(index)
                    .conflicts(Conflicts.Proceed)
                    .query(new MatchAllQuery.Builder().build().toQuery())
                    .refresh(Refresh.True)
                    .build();
            openSearchClient.deleteByQuery(request);
        }
        catch (IOException e)
        {
            // ignoring, it will be retried by the caller
        }
    }
}
