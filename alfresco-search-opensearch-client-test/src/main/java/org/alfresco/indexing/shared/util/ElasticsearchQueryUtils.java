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
package org.alfresco.indexing.shared.util;

import java.util.Map;

import lombok.experimental.UtilityClass;
import org.opensearch.client.opensearch._types.FieldValue;
import org.opensearch.client.opensearch._types.query_dsl.BoolQuery;
import org.opensearch.client.opensearch._types.query_dsl.MatchQuery;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.opensearch._types.query_dsl.QueryBuilders;
import org.opensearch.client.opensearch._types.query_dsl.TermQuery;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import org.alfresco.indexing.shared.model.ElasticSearchCondition;
import org.alfresco.indexing.shared.model.ElasticSearchQueryCriterion;
import org.alfresco.indexing.shared.model.ElasticSearchQueryDto;
import org.alfresco.indexing.shared.model.ElasticSearchQueryGroup;

@UtilityClass
public class ElasticsearchQueryUtils
{
    public static final ElasticSearchQueryDto ALL_DOCS_QUERY = ElasticSearchQueryDto.builder().allQuery(true).build();

    public static Query toOpensearchQuery(ElasticSearchQueryDto elasticSearchQueryDto)
    {
        if (StringUtils.hasLength(elasticSearchQueryDto.getQueryString()))
        {
            if (CollectionUtils.isEmpty(elasticSearchQueryDto.getQueryStringFields()))
            {
                return new Query.Builder()
                        .queryString(QueryBuilders
                                .queryString().query(elasticSearchQueryDto.getQueryString())
                                .build())
                        .build();
            }
            return new Query.Builder()
                    .queryString(QueryBuilders
                            .queryString().query(elasticSearchQueryDto.getQueryString())
                            .fields(elasticSearchQueryDto.getQueryStringFields())
                            .build())
                    .build();

        }
        return buildQuery(elasticSearchQueryDto);
    }

    private static Query buildQuery(ElasticSearchQueryDto dto)
    {
        if (dto.isAllQuery())
        {
            return QueryBuilders
                    .matchAll()
                    .build().toQuery();
        }
        if (!CollectionUtils.isEmpty(dto.getQueryAttributes()))
        {
            Map.Entry<String, Object> searchAttribute = dto.getQueryAttributes()
                    .entrySet()
                    .stream()
                    .findFirst()
                    .orElseThrow();

            return QueryBuilders.match()
                    .field(searchAttribute.getKey())
                    .query(FieldValue.of(String.valueOf(searchAttribute.getValue())))
                    .build()
                    .toQuery();
        }

        if (!CollectionUtils.isEmpty(dto.getTermQueryAttributes()))
        {
            Map.Entry<String, Object> termAttribute = dto.getTermQueryAttributes()
                    .entrySet()
                    .stream()
                    .findFirst()
                    .orElseThrow();
            return QueryBuilders.term()
                    .field(termAttribute.getKey())
                    .value(FieldValue.of(String.valueOf(termAttribute.getValue())))
                    .build()
                    .toQuery();
        }

        if (dto.getQueryGroup() != null)
        {
            return booleanQuery(dto.getQueryGroup()).toQuery();
        }

        throw new IllegalArgumentException("No query or query attributes provided");
    }

    private static BoolQuery booleanQuery(ElasticSearchQueryGroup queryGroup)
    {
        BoolQuery.Builder boolQuery = QueryBuilders.bool();
        queryGroup.getCriteria()
                .forEach((criteria) -> {
                    Query query = getQueryBuilder(criteria);
                    if (criteria.getCondition() == ElasticSearchCondition.MUST)
                    {
                        boolQuery.must(query);
                    }
                    else if (criteria.getCondition() == ElasticSearchCondition.MUST_NOT)
                    {
                        boolQuery.mustNot(query);
                    }
                });
        return boolQuery.build();
    }

    private static Query getQueryBuilder(ElasticSearchQueryCriterion elasticSearchQueryCriterion)
    {
        return switch (elasticSearchQueryCriterion.getQueryType())
        {
        case MATCH -> new MatchQuery.Builder().field(elasticSearchQueryCriterion.getKey())
                .query(FieldValue.of(elasticSearchQueryCriterion.getValue()))
                .build()
                .toQuery();
        case TERM -> new TermQuery.Builder().field(elasticSearchQueryCriterion.getKey())
                .value(FieldValue.of(elasticSearchQueryCriterion.getValue()))
                .build()
                .toQuery();
        case EXISTS -> QueryBuilders.exists()
                .field(elasticSearchQueryCriterion.getKey())
                .build()
                .toQuery();
        default -> throw new IllegalArgumentException(
                "Unsupported query type: " + elasticSearchQueryCriterion.getQueryType());
        };
    }
}
