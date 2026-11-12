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
package org.alfresco.indexing.shared.opensearch.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.StringWriter;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.opensearch.client.json.JsonpSerializable;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.client.opensearch._types.ErrorCause;
import org.opensearch.client.opensearch.core.BulkRequest;
import org.opensearch.client.opensearch.core.BulkResponse;
import org.opensearch.client.opensearch.core.bulk.BulkOperation;
import org.opensearch.client.opensearch.core.bulk.BulkResponseItem;
import org.opensearch.client.opensearch.core.bulk.OperationType;

import org.alfresco.indexing.shared.ElasticsearchProperties;
import org.alfresco.indexing.shared.exception.ContentClearException;
import org.alfresco.indexing.shared.opensearch.ElasticsearchContentModifyPort;
import org.alfresco.indexing.shared.opensearch.command.ContentClearCmd;
import org.alfresco.indexing.shared.opensearch.request.ContentRequestBuilder;

@DisplayName("Excluded content clear")
class OpensearchContentModifierClearTest
{
    private static final String INDEX = "test-index";
    private static final long CONTENT_TIMESTAMP = 1000L;
    private static final long CONFLICT_CUTOFF = 5000L;

    private ElasticsearchProperties properties;
    private OpensearchUpdater opensearchUpdater;
    private OpensearchContentModifier modifier;

    @BeforeEach
    void setUp()
    {
        properties = mock(ElasticsearchProperties.class);
        when(properties.getIndexName()).thenReturn(INDEX);
        when(properties.getRetryOnConflict()).thenReturn(5);
        opensearchUpdater = mock(OpensearchUpdater.class);
        modifier = new OpensearchContentModifier(new ContentRequestBuilder(properties), opensearchUpdater);
    }

    @Test
    @DisplayName("no excluded nodes sends nothing")
    void emptyClearSendsNothing()
    {
        modifier.clearContents(List.of());

        verifyNoInteractions(opensearchUpdater);
    }

    @Test
    @DisplayName("clears for several nodes are sent in one bulk request")
    void clearsAreSentInOneBulkRequest()
    {
        when(opensearchUpdater.sendBulk(any(BulkRequest.class), anyString()))
                .thenReturn(bulkResponse(false, successItem("a"), successItem("b")));

        modifier.clearContents(List.of(
                new ContentClearCmd("a", CONTENT_TIMESTAMP, CONFLICT_CUTOFF),
                new ContentClearCmd("b", CONTENT_TIMESTAMP, CONFLICT_CUTOFF)));

        ArgumentCaptor<BulkRequest> captor = ArgumentCaptor.forClass(BulkRequest.class);
        verify(opensearchUpdater).sendBulk(captor.capture(), anyString());
        assertThat(captor.getValue().operations()).hasSize(2);
    }

    @Test
    @DisplayName("a failed item fails the whole clear instead of being swallowed")
    void failedItemFailsTheClear()
    {
        when(opensearchUpdater.sendBulk(any(BulkRequest.class), anyString()))
                .thenReturn(bulkResponse(true, successItem("a"), failedItem("b")));

        assertThatThrownBy(() -> modifier.clearContents(List.of(
                new ContentClearCmd("a", CONTENT_TIMESTAMP, CONFLICT_CUTOFF),
                new ContentClearCmd("b", CONTENT_TIMESTAMP, CONFLICT_CUTOFF))))
                        .isInstanceOf(ContentClearException.class)
                        .hasMessage("Failed to clear excluded content for 1 of 2 nodes: b (mapper_parsing_exception: boom)");
    }

    @Test
    @DisplayName("the failure message lists at most five failed nodes")
    void failureMessageIsBounded()
    {
        when(opensearchUpdater.sendBulk(any(BulkRequest.class), anyString()))
                .thenReturn(bulkResponse(true, failedItem("n1"), failedItem("n2"), failedItem("n3"),
                        failedItem("n4"), failedItem("n5"), failedItem("n6")));
        var clears = List.of("n1", "n2", "n3", "n4", "n5", "n6").stream()
                .map(id -> new ContentClearCmd(id, CONTENT_TIMESTAMP, CONFLICT_CUTOFF))
                .toList();

        assertThatThrownBy(() -> modifier.clearContents(clears))
                .isInstanceOf(ContentClearException.class)
                .hasMessageStartingWith("Failed to clear excluded content for 6 of 6 nodes: n1 (")
                .hasMessageContaining("n5 (mapper_parsing_exception: boom)")
                .hasMessageNotContaining("n6")
                .hasMessageEndingWith(", ...");
    }

    @Test
    @DisplayName("a failure to send the clear is reported as a content clear failure")
    void sendFailureFailsTheClear()
    {
        var cause = new RuntimeException("connection refused");
        when(opensearchUpdater.sendBulk(any(BulkRequest.class), anyString())).thenThrow(cause);

        assertThatThrownBy(() -> modifier.clearContents(List.of(new ContentClearCmd("a", CONTENT_TIMESTAMP, CONFLICT_CUTOFF))))
                .isInstanceOf(ContentClearException.class)
                .hasCause(cause);
    }

    @Test
    @DisplayName("a port that does not implement clearing fails with the non-skippable content clear failure")
    void defaultClearFailsWithContentClearException()
    {
        ElasticsearchContentModifyPort portWithoutClear = contentIndexCmd -> {};

        assertThatThrownBy(() -> portWithoutClear.clearContents(List.of(new ContentClearCmd("a", CONTENT_TIMESTAMP, CONFLICT_CUTOFF))))
                .isInstanceOf(ContentClearException.class);
    }

    @Test
    @DisplayName("each clear is a scripted update that stores the event timestamp and carries the reindex cutoff")
    void bulkOperationCarriesTimestampAndCutoff()
    {
        BulkOperation operation = new ContentRequestBuilder(properties)
                .createExclusionBulkOperation(new ContentClearCmd("node", CONTENT_TIMESTAMP, CONFLICT_CUTOFF));

        assertThat(operation.update().index()).isEqualTo(INDEX);
        assertThat(operation.update().id()).isEqualTo("node");
        assertThat(serialize(operation))
                .contains("\"CONTENT_INDEXING_LAST_UPDATE\":" + CONTENT_TIMESTAMP)
                .contains("\"CONTENT_EXCLUSION_CUTOFF\":" + CONFLICT_CUTOFF)
                .contains("remove('cm%3Acontent')");
    }

    @Test
    @DisplayName("the clear runs its script on a missing document, so the script decides whether to create one rather than the upsert leaving a timestamp-only stub")
    void bulkOperationUsesScriptedUpsert()
    {
        BulkOperation operation = new ContentRequestBuilder(properties)
                .createExclusionBulkOperation(new ContentClearCmd("node", CONTENT_TIMESTAMP, CONFLICT_CUTOFF));

        assertThat(serialize(operation)).contains("\"scripted_upsert\":true");
    }

    private static BulkResponse bulkResponse(boolean errors, BulkResponseItem... items)
    {
        return new BulkResponse.Builder()
                .errors(errors)
                .took(1L)
                .items(List.of(items))
                .build();
    }

    private static BulkResponseItem successItem(String id)
    {
        return new BulkResponseItem.Builder()
                .operationType(OperationType.Update)
                .id(id)
                .index(INDEX)
                .status(200)
                .build();
    }

    private static BulkResponseItem failedItem(String id)
    {
        return new BulkResponseItem.Builder()
                .operationType(OperationType.Update)
                .id(id)
                .index(INDEX)
                .status(500)
                .error(new ErrorCause.Builder().type("mapper_parsing_exception").reason("boom").build())
                .build();
    }

    private static String serialize(BulkOperation operation)
    {
        var mapper = new JacksonJsonpMapper();
        var result = new StringBuilder(write(mapper, operation));
        operation.update()._serializables().forEachRemaining(payload -> {
            if (payload instanceof JsonpSerializable serializable)
            {
                result.append(write(mapper, serializable));
            }
        });
        return result.toString();
    }

    private static String write(JacksonJsonpMapper mapper, JsonpSerializable serializable)
    {
        var writer = new StringWriter();
        try (var generator = mapper.jsonProvider().createGenerator(writer))
        {
            serializable.serialize(generator, mapper);
        }
        return writer.toString();
    }
}
