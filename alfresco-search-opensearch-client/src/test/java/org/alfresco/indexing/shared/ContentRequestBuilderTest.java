/*-
 * #%L
 * Alfresco Search Community :: OpenSearch Client
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.opensearch.client.opensearch.core.UpdateRequest;

import org.alfresco.indexing.shared.opensearch.command.ContentIndexCmd;
import org.alfresco.indexing.shared.opensearch.request.ContentRequestBuilder;

class ContentRequestBuilderTest
{
    private static final String INDEX_NAME = "test_index";
    private static final int RETRY_COUNT = 5;
    private static final String SCRIPT_TEMPLATE = "if (ctx._source.CONTENT_INDEXING_LAST_UPDATE != null && ctx._source.CONTENT_INDEXING_LAST_UPDATE > params.CONTENT_INDEXING_LAST_UPDATE) { ctx.op = 'noop'; } else { %s }";
    private static final String CONTENT_INDEXING_LAST_UPDATE = "CONTENT_INDEXING_LAST_UPDATE";
    private static final String ENCODED_CONTENT = "cm%3Acontent";
    private static final String ENCODED_TR_EX = "cm%3Acontent%2Etr_ex";
    private static final String ENCODED_TR_STATUS = "cm%3Acontent%2Etr_status";

    private final ElasticsearchProperties elasticsearchProperties = mock();
    private final ContentRequestBuilder requestBuilder = new ContentRequestBuilder(elasticsearchProperties);

    @BeforeEach
    void setUp()
    {
        when(elasticsearchProperties.getIndexName()).thenReturn(INDEX_NAME);
        when(elasticsearchProperties.getRetryOnConflict()).thenReturn(RETRY_COUNT);
    }

    @ParameterizedTest(name = "expected fields: {1}")
    @MethodSource("contentIndexCmdProvider")
    @DisplayName("should correctly calculate script with parameters map")
    void shouldCalculateScriptWithParameters(ContentIndexCmd contentIndexCmd, Map<String, Object> expectedUpdateMapping, Set<String> expectedRemoveMapping)
    {
        // when
        var updateRequest = requestBuilder.createUpsertRequest(contentIndexCmd);
        // then
        assertScriptCorrect(updateRequest, expectedUpdateMapping.keySet(), expectedRemoveMapping);
        Assertions.assertThat(updateRequest.upsert())
                .isEqualTo(expectedUpdateMapping);
    }

    public static Stream<Arguments> contentIndexCmdProvider()
    {
        return Stream.of(
                Arguments.of(
                        ContentIndexCmd.withTextScript("testNodeId", 1000L, "test content"),
                        Map.of(CONTENT_INDEXING_LAST_UPDATE, 1000L, ENCODED_CONTENT, "test content"),
                        Set.of(ENCODED_TR_EX, ENCODED_TR_STATUS)),
                Arguments.of(
                        ContentIndexCmd.withExceptionScript("testNodeId", 1000L, TransformStatus.TRANSFORM_FAILED, "test content error"),
                        Map.of(CONTENT_INDEXING_LAST_UPDATE, 1000L, ENCODED_TR_EX, "test content error", ENCODED_TR_STATUS, TransformStatus.TRANSFORM_FAILED.name()),
                        Set.of(ENCODED_CONTENT)));
    }

    private void assertScriptCorrect(UpdateRequest<?, ?> updateRequest, Set<String> toUpdate, Set<String> toRemove)
    {
        var updateParams = toUpdate.stream()
                .sorted()
                .map(f -> "ctx._source['%s'] = params['%s'];".formatted(f, f));
        var removeParams = toRemove.stream()
                .sorted()
                .map("ctx._source.remove('%s');"::formatted);
        var params = Stream.concat(updateParams, removeParams)
                .collect(Collectors.joining(" "));
        String expectedScript = SCRIPT_TEMPLATE.formatted(params);
        // comparing the expected script value with the inline.source
        assertThat(updateRequest.script())
                .isNotNull()
                .extracting(s -> s.inline().source())
                .isEqualTo(expectedScript);
    }

    @Test
    void shouldSetupBasicProperties()
    {
        String testNodeId = "test123";

        var contentIndexCmd = ContentIndexCmd.withTextScript(testNodeId, 1000L, "test");

        UpdateRequest<?, ?> updateRequest = requestBuilder.createUpsertRequest(contentIndexCmd);

        assertEquals(INDEX_NAME, updateRequest.index());
        assertEquals(RETRY_COUNT, updateRequest.retryOnConflict());
        assertEquals(testNodeId, updateRequest.id());
        assertNull(updateRequest.refresh());
    }
}
