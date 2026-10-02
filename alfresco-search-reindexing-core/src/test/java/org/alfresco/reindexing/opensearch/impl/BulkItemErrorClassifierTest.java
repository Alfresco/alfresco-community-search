/*-
 * #%L
 * Alfresco Search Community :: Reindexing Core
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
package org.alfresco.reindexing.opensearch.impl;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.opensearch.client.opensearch._types.ErrorCause;
import org.opensearch.client.opensearch.core.bulk.BulkResponseItem;
import org.opensearch.client.opensearch.core.bulk.OperationType;

/**
 * Truth table for {@link BulkItemErrorClassifier}. Polarity is "retry unless known permanent" — only mapping errors and script/argument bugs are terminal; every other error (including races and unknowns) gets another round.
 */
class BulkItemErrorClassifierTest
{
    @ParameterizedTest(name = "type {0} → terminal")
    @CsvSource({
            "mapper_parsing_exception",
            "document_parsing_exception",
            "document_mapping_exception",
            "strict_dynamic_mapping_exception",
            "illegal_argument_exception"})
    @DisplayName("known permanent error types are terminal")
    void terminalErrorTypes(String errorType)
    {
        assertThat(BulkItemErrorClassifier.isRetryable(item(500, errorType))).isFalse();
        assertThat(BulkItemErrorClassifier.isRetryable(item(400, errorType))).isFalse();
    }

    @ParameterizedTest(name = "type {0} → retryable")
    @CsvSource({
            "es_rejected_execution_exception",
            "cluster_block_exception",
            "circuit_breaking_exception",
            "unavailable_shards_exception",
            "node_disconnected_exception",
            "receive_timeout_transport_exception",
            "document_missing_exception",
            "version_conflict_engine_exception",
            "some_unknown_future_exception"})
    @DisplayName("everything not in the terminal set — including transients, races, and unknowns — is retryable")
    void retryableErrorTypes(String errorType)
    {
        assertThat(BulkItemErrorClassifier.isRetryable(item(429, errorType))).isTrue();
        assertThat(BulkItemErrorClassifier.isRetryable(item(503, errorType))).isTrue();
        assertThat(BulkItemErrorClassifier.isRetryable(item(200, errorType))).isTrue();
    }

    private static BulkResponseItem item(int status, String errorType)
    {
        return new BulkResponseItem.Builder()
                .operationType(OperationType.Update)
                .id("some-id")
                .index("some-index")
                .status(status)
                .error(new ErrorCause.Builder().type(errorType).reason("test").build())
                .build();
    }
}
