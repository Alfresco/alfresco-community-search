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
import static org.mockito.Mockito.verify;

import java.io.IOException;

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

class RenameCascadeServiceTest
{
    private OpenSearchClient openSearchClient;
    private CascadeProperties cascadeProperties;
    private RenameCascadeService service;

    @BeforeEach
    void setUp()
    {
        openSearchClient = mock(OpenSearchClient.class);
        ElasticsearchProperties elasticsearchProperties = mock(ElasticsearchProperties.class);
        given(elasticsearchProperties.getIndexName()).willReturn("alfresco");
        cascadeProperties = new CascadeProperties();
        cascadeProperties.setRefresh(true);
        service = new RenameCascadeService(openSearchClient, elasticsearchProperties, cascadeProperties);
    }

    @Test
    @DisplayName("apply issues a refresh=true update_by_query with the old/new segment params")
    void appliesRenameCascade() throws IOException
    {
        UpdateByQueryResponse response = mock(UpdateByQueryResponse.class);
        given(response.updated()).willReturn(7L);
        given(openSearchClient.updateByQuery(any(UpdateByQueryRequest.class))).willReturn(response);

        long updated = service.apply(new RenameCascadeSource(
                "workspace://SpacesStore/uuid-1", "uuid-1",
                "cm:old", "cm:new", "old", "new"));

        assertThat(updated).isEqualTo(7L);
        ArgumentCaptor<UpdateByQueryRequest> captor = ArgumentCaptor.forClass(UpdateByQueryRequest.class);
        verify(openSearchClient).updateByQuery(captor.capture());
        UpdateByQueryRequest req = captor.getValue();
        assertThat(req.index()).containsExactly("alfresco");
        assertThat(req.refresh()).isEqualTo(Refresh.True);
        assertThat(req.script()).isNotNull();
        assertThat(req.query()).isNotNull();
    }

    @Test
    @DisplayName("apply is a noop when the descriptor is a Phase-1 skeleton")
    void skipsSkeleton() throws IOException
    {
        long updated = service.apply(CascadeTestHelper.skeleton("workspace://SpacesStore/x", "new"));

        assertThat(updated).isZero();
        verify(openSearchClient, org.mockito.Mockito.never()).updateByQuery(any(UpdateByQueryRequest.class));
    }

    @Test
    @DisplayName("apply is a noop when old segments equal new segments")
    void skipsNoop() throws IOException
    {
        long updated = service.apply(new RenameCascadeSource(
                "workspace://SpacesStore/uuid-1", "uuid-1", "cm:x", "cm:x", "x", "x"));

        assertThat(updated).isZero();
        verify(openSearchClient, org.mockito.Mockito.never()).updateByQuery(any(UpdateByQueryRequest.class));
    }

    @Test
    @DisplayName("apply returns 0 and does not propagate when Elasticsearch throws")
    void swallowsExceptions() throws IOException
    {
        given(openSearchClient.updateByQuery(any(UpdateByQueryRequest.class)))
                .willThrow(new IOException("boom"));

        long updated = service.apply(new RenameCascadeSource(
                "workspace://SpacesStore/uuid-1", "uuid-1", "cm:old", "cm:new", "old", "new"));

        assertThat(updated).isZero();
    }

    @Test
    @DisplayName("apply honours refresh=false when configured")
    void honoursRefreshFalse() throws IOException
    {
        cascadeProperties.setRefresh(false);
        UpdateByQueryResponse response = mock(UpdateByQueryResponse.class);
        given(response.updated()).willReturn(1L);
        given(openSearchClient.updateByQuery(any(UpdateByQueryRequest.class))).willReturn(response);

        service.apply(new RenameCascadeSource(
                "workspace://SpacesStore/uuid-1", "uuid-1", "cm:old", "cm:new", "old", "new"));

        ArgumentCaptor<UpdateByQueryRequest> captor = ArgumentCaptor.forClass(UpdateByQueryRequest.class);
        verify(openSearchClient).updateByQuery(captor.capture());
        assertThat(captor.getValue().refresh()).isEqualTo(Refresh.False);
    }

    @Test
    @DisplayName("script source references both PATH and UNPREFIXED_PATH")
    void scriptTouchesBothPathFields()
    {
        assertThat(RenameCascadeService.SCRIPT_SOURCE).contains("ctx._source.PATH");
        assertThat(RenameCascadeService.SCRIPT_SOURCE).contains("ctx._source.UNPREFIXED_PATH");
        assertThat(RenameCascadeService.SCRIPT_SOURCE).contains("params.oldPrefixed");
        assertThat(RenameCascadeService.SCRIPT_SOURCE).contains("params.newUnprefixed");
    }
}
