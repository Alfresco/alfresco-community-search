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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.core.MgetRequest;
import org.opensearch.client.opensearch.core.MgetResponse;
import org.opensearch.client.opensearch.core.get.GetResult;
import org.opensearch.client.opensearch.core.mget.MultiGetResponseItem;

import org.alfresco.indexing.shared.ElasticsearchProperties;

class SourceDocLookupTest
{
    private OpenSearchClient openSearchClient;
    private SourceDocLookup lookup;

    @BeforeEach
    void setUp()
    {
        openSearchClient = mock(OpenSearchClient.class);
        ElasticsearchProperties elasticsearchProperties = mock(ElasticsearchProperties.class);
        given(elasticsearchProperties.getIndexName()).willReturn("alfresco");
        lookup = new SourceDocLookup(openSearchClient, elasticsearchProperties);
    }

    @Test
    @DisplayName("empty input returns empty map without hitting Elasticsearch")
    void emptyInputSkipsMget()
    {
        assertThat(lookup.lookup(Collections.emptyList())).isEmpty();
        assertThat(lookup.lookup(null)).isEmpty();
        verifyNoInteractions(openSearchClient);
    }

    @Test
    @DisplayName("extracts terminal segments from PATH and UNPREFIXED_PATH arrays")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void extractsTerminalSegments() throws IOException
    {
        Map<String, Object> source = Map.of(
                "PATH", List.of("/cm:categoryRoot/cm:generalclassifiable/cm:parent/cm:oldName"),
                "UNPREFIXED_PATH", List.of("/categoryRoot/generalclassifiable/parent/oldName"));
        MgetResponse<Map> response = mockResponse("ref-1", source);
        given(openSearchClient.mget(any(MgetRequest.class), eq(Map.class))).willReturn(response);

        Map<String, SourceDocLookup.SourceDocSnapshot> result = lookup.lookup(List.of("ref-1"));

        assertThat(result).hasSize(1);
        assertThat(result.get("ref-1").prefixedSegment()).isEqualTo("cm:oldName");
        assertThat(result.get("ref-1").unprefixedSegment()).isEqualTo("oldName");
        assertThat(result.get("ref-1").type()).isNull();
    }

    @Test
    @DisplayName("extracts TYPE field for category-delete detection")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void extractsType() throws IOException
    {
        Map<String, Object> source = Map.of("TYPE", "cm:category");
        MgetResponse<Map> response = mockResponse("ref-cat", source);
        given(openSearchClient.mget(any(MgetRequest.class), eq(Map.class))).willReturn(response);

        Map<String, SourceDocLookup.SourceDocSnapshot> result = lookup.lookup(List.of("ref-cat"));

        assertThat(result).hasSize(1);
        assertThat(result.get("ref-cat").type()).isEqualTo("cm:category");
    }

    @Test
    @DisplayName("normalises TYPE when Elasticsearch returns it as a single-element list")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void extractsTypeFromList() throws IOException
    {
        Map<String, Object> source = Map.of("TYPE", List.of("cm:category"));
        MgetResponse<Map> response = mockResponse("ref-cat", source);
        given(openSearchClient.mget(any(MgetRequest.class), eq(Map.class))).willReturn(response);

        Map<String, SourceDocLookup.SourceDocSnapshot> result = lookup.lookup(List.of("ref-cat"));

        assertThat(result.get("ref-cat").type()).isEqualTo("cm:category");
    }

    @Test
    @DisplayName("extracts full PATH and UNPREFIXED_PATH arrays for category-delete cascade")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void extractsFullPathArrays() throws IOException
    {
        Map<String, Object> source = Map.of(
                "PATH", List.of(
                        "/cm:categoryRoot/cm:generalclassifiable/cm:catA/",
                        "/cm:categoryRoot/cm:generalclassifiable/cm:catB/"),
                "UNPREFIXED_PATH", List.of(
                        "/categoryRoot/generalclassifiable/catA/",
                        "/categoryRoot/generalclassifiable/catB/"));
        MgetResponse<Map> response = mockResponse("ref-cat", source);
        given(openSearchClient.mget(any(MgetRequest.class), eq(Map.class))).willReturn(response);

        SourceDocLookup.SourceDocSnapshot snapshot = lookup.lookup(List.of("ref-cat")).get("ref-cat");

        assertThat(snapshot.paths()).containsExactly(
                "/cm:categoryRoot/cm:generalclassifiable/cm:catA/",
                "/cm:categoryRoot/cm:generalclassifiable/cm:catB/");
        assertThat(snapshot.unprefixedPaths()).containsExactly(
                "/categoryRoot/generalclassifiable/catA/",
                "/categoryRoot/generalclassifiable/catB/");
    }

    @Test
    @DisplayName("returns empty map when Elasticsearch throws")
    void swallowsExceptions() throws IOException
    {
        given(openSearchClient.mget(any(MgetRequest.class), eq(Map.class))).willThrow(new IOException("boom"));

        assertThat(lookup.lookup(List.of("ref-1"))).isEmpty();
    }

    @Test
    @DisplayName("dedupes ids passed to mget")
    void dedupesIds() throws IOException
    {
        MgetResponse<Map> empty = emptyResponse();
        given(openSearchClient.mget(any(MgetRequest.class), eq(Map.class))).willReturn(empty);

        lookup.lookup(List.of("ref-1", "ref-1", "ref-2"));

        ArgumentCaptor<MgetRequest> captor = ArgumentCaptor.forClass(MgetRequest.class);
        verify(openSearchClient).mget(captor.capture(), eq(Map.class));
        assertThat(captor.getValue().ids()).containsExactly("ref-1", "ref-2");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static MgetResponse<Map> mockResponse(String id, Map<String, Object> source)
    {
        MgetResponse response = mock(MgetResponse.class);
        MultiGetResponseItem item = mock(MultiGetResponseItem.class);
        given(item.isResult()).willReturn(true);
        GetResult getResult = mock(GetResult.class);
        given(getResult.found()).willReturn(true);
        given(getResult.id()).willReturn(id);
        given(getResult.source()).willReturn(source);
        given(item.result()).willReturn(getResult);
        given(response.docs()).willReturn(List.of(item));
        return response;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static MgetResponse<Map> emptyResponse()
    {
        MgetResponse response = mock(MgetResponse.class);
        given(response.docs()).willReturn(Collections.emptyList());
        return response;
    }
}
