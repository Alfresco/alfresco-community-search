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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.Refresh;
import org.opensearch.client.opensearch.core.UpdateByQueryRequest;
import org.opensearch.client.opensearch.core.UpdateByQueryResponse;

import org.alfresco.elasticsearch.batchindexing.config.CascadeProperties;
import org.alfresco.indexing.shared.ElasticsearchProperties;

class CategoryDeleteCascadeServiceTest
{
    private OpenSearchClient openSearchClient;
    private CategoryDeleteCascadeService service;

    @BeforeEach
    void setUp()
    {
        openSearchClient = mock(OpenSearchClient.class);
        ElasticsearchProperties elasticsearchProperties = mock(ElasticsearchProperties.class);
        given(elasticsearchProperties.getIndexName()).willReturn("alfresco");
        CascadeProperties cascadeProperties = new CascadeProperties();
        service = new CategoryDeleteCascadeService(openSearchClient, elasticsearchProperties, cascadeProperties);
    }

    @Test
    @DisplayName("apply issues update_by_query targeting the deleted category uuid")
    void appliesCategoryDeleteCascade() throws IOException
    {
        UpdateByQueryResponse response = mock(UpdateByQueryResponse.class);
        given(response.updated()).willReturn(3L);
        given(openSearchClient.updateByQuery(any(UpdateByQueryRequest.class))).willReturn(response);

        long updated = service.apply(CascadeTestHelper.categoryDelete("workspace://SpacesStore/uuid-cat", "uuid-cat"));

        assertThat(updated).isEqualTo(3L);
        ArgumentCaptor<UpdateByQueryRequest> captor = ArgumentCaptor.forClass(UpdateByQueryRequest.class);
        verify(openSearchClient).updateByQuery(captor.capture());
        UpdateByQueryRequest req = captor.getValue();
        assertThat(req.index()).containsExactly("alfresco");
        assertThat(req.refresh()).isEqualTo(Refresh.True);
        assertThat(req.script()).isNotNull();
    }

    @Test
    @DisplayName("apply skips when descendantUuid is missing")
    void skipsMissingUuid() throws IOException
    {
        long updated = service.apply(CascadeTestHelper.categoryDelete("workspace://SpacesStore/x", null));

        assertThat(updated).isZero();
        verify(openSearchClient, never()).updateByQuery(any(UpdateByQueryRequest.class));
    }

    @Test
    @DisplayName("apply swallows IO exceptions and returns 0")
    void swallowsExceptions() throws IOException
    {
        given(openSearchClient.updateByQuery(any(UpdateByQueryRequest.class))).willThrow(new IOException("boom"));

        long updated = service.apply(CascadeTestHelper.categoryDelete("workspace://SpacesStore/uuid-cat", "uuid-cat"));

        assertThat(updated).isZero();
    }

    @Test
    @DisplayName("script prunes CATEGORY_ANCESTOR, PARENT, cm%3Acategories, PATH and UNPREFIXED_PATH")
    void scriptTouchesBothFields()
    {
        assertThat(CategoryDeleteCascadeService.SCRIPT_SOURCE).contains("CATEGORY_ANCESTOR");
        assertThat(CategoryDeleteCascadeService.SCRIPT_SOURCE).contains("ctx._source.PARENT");
        assertThat(CategoryDeleteCascadeService.SCRIPT_SOURCE).contains(CategoryDeleteCascadeService.CATEGORIES_FIELD);
        assertThat(CategoryDeleteCascadeService.SCRIPT_SOURCE).contains("ctx._source.PATH");
        assertThat(CategoryDeleteCascadeService.SCRIPT_SOURCE).contains("ctx._source.UNPREFIXED_PATH");
        assertThat(CategoryDeleteCascadeService.SCRIPT_SOURCE).contains("params.catPaths");
        assertThat(CategoryDeleteCascadeService.SCRIPT_SOURCE).contains("params.catUnprefixedPaths");
        assertThat(CategoryDeleteCascadeService.SCRIPT_SOURCE).contains("removeIf");
        assertThat(CategoryDeleteCascadeService.SCRIPT_SOURCE).contains("params.catId");
    }

    @Test
    @DisplayName("apply passes category PATH snapshots into script params")
    void appliesPathParams() throws IOException
    {
        UpdateByQueryResponse response = mock(UpdateByQueryResponse.class);
        given(response.updated()).willReturn(1L);
        given(openSearchClient.updateByQuery(any(UpdateByQueryRequest.class))).willReturn(response);

        service.apply(new CategoryDeleteCascadeSource(
                "workspace://SpacesStore/uuid-cat", "uuid-cat",
                List.of("/cm:categoryRoot/cm:generalclassifiable/cm:catA/"),
                List.of("/categoryRoot/generalclassifiable/catA/")));

        ArgumentCaptor<UpdateByQueryRequest> captor = ArgumentCaptor.forClass(UpdateByQueryRequest.class);
        verify(openSearchClient).updateByQuery(captor.capture());
        var params = captor.getValue().script().inline().params();
        assertThat(params).containsKeys("catId", "catPaths", "catUnprefixedPaths");
        assertThat(params.get("catPaths").toString()).contains("cm:catA");
        assertThat(params.get("catUnprefixedPaths").toString()).contains("catA");
    }
}
