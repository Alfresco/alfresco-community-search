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
package org.alfresco.elasticsearch.batchindexing.writers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

import java.time.ZonedDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.infrastructure.item.Chunk;

import org.alfresco.elasticsearch.batchindexing.domain.transform.ContentTransformClient;
import org.alfresco.elasticsearch.batchindexing.domain.transform.ContentTransformFailure;
import org.alfresco.elasticsearch.batchindexing.domain.transform.ContentTransformResult;
import org.alfresco.indexing.shared.opensearch.ElasticsearchContentModifyPort;
import org.alfresco.indexing.shared.opensearch.command.ContentIndexCmd;
import org.alfresco.reindexing.domain.transform.ContentTransformSupportService;
import org.alfresco.reindexing.model.ReindexingEvent;
import org.alfresco.repo.event.v1.model.ContentInfo;
import org.alfresco.repo.event.v1.model.EventData;
import org.alfresco.repo.event.v1.model.NodeResource;
import org.alfresco.repo.event.v1.model.RepoEvent;

@ExtendWith(MockitoExtension.class)
class ContentTransformDirectWriterTest
{
    private static final String STORE_REF = "workspace://SpacesStore/";
    private static final String RESOURCE_UUID = "d1c1a67e-5c3f-4a2b-9e8d-0f1a2b3c4d5e";
    private static final Long NODE_DB_ID = 42L;
    private static final String MIME_TYPE = "application/pdf";
    private static final String TRANSFORMED_TEXT = "extracted text content from the document";
    private static final ZonedDateTime EVENT_TIME = ZonedDateTime.parse("2026-03-15T10:30:00Z");

    @Mock
    private ContentTransformSupportService contentTransformSupportService;
    @Mock
    private ContentTransformClient contentTransformClient;
    @Mock
    private ElasticsearchContentModifyPort contentModifyPort;
    @Captor
    private ArgumentCaptor<ContentIndexCmd> contentIndexCmdCaptor;

    private ContentTransformDirectWriter writer;

    @BeforeEach
    void setUp()
    {
        writer = new ContentTransformDirectWriter(
                contentTransformSupportService, contentTransformClient, contentModifyPort, STORE_REF);
    }

    @Nested
    @DisplayName("Empty and filtered chunks")
    class EmptyAndFilteredChunks
    {
        @Test
        @DisplayName("Empty chunk does not invoke any transform or index operation")
        void emptyChunk() throws Exception
        {
            writer.write(new Chunk<>());

            verifyNoInteractions(contentTransformClient);
            verifyNoInteractions(contentModifyPort);
        }

        @Test
        @DisplayName("All items filtered out by ContentTransformSupportService produces no transform calls")
        void allItemsFilteredOut() throws Exception
        {
            ReindexingEvent event1 = buildEvent(RESOURCE_UUID, NODE_DB_ID, MIME_TYPE);
            ReindexingEvent event2 = buildEvent("another-uuid", 99L, MIME_TYPE);

            when(contentTransformSupportService.isContentTransformSupported(any())).thenReturn(false);

            writer.write(Chunk.of(event1, event2));

            verifyNoInteractions(contentTransformClient);
            verifyNoInteractions(contentModifyPort);
        }
    }

    @Nested
    @DisplayName("Success scenarios")
    class SuccessScenarios
    {
        @Test
        @DisplayName("All items transform and index successfully")
        void allItemsSucceed() throws Exception
        {
            ReindexingEvent event1 = buildEvent("uuid-1", 10L, MIME_TYPE);
            ReindexingEvent event2 = buildEvent("uuid-2", 20L, MIME_TYPE);

            when(contentTransformSupportService.isContentTransformSupported(any())).thenReturn(true);
            when(contentTransformClient.transformContent(10L)).thenReturn(ContentTransformResult.ofText("text-1"));
            when(contentTransformClient.transformContent(20L)).thenReturn(ContentTransformResult.ofText("text-2"));

            writer.write(Chunk.of(event1, event2));

            verify(contentModifyPort, times(2)).upsertContent(contentIndexCmdCaptor.capture());
            assertThat(contentIndexCmdCaptor.getAllValues())
                    .extracting(ContentIndexCmd::contentText)
                    .containsExactlyInAnyOrder("text-1", "text-2");
        }

        @Test
        @DisplayName("ContentIndexCmd is built with correct uuid, timestamp, and text")
        void contentIndexCmdParameters() throws Exception
        {
            ReindexingEvent event = buildEvent(RESOURCE_UUID, NODE_DB_ID, MIME_TYPE);

            when(contentTransformSupportService.isContentTransformSupported(any())).thenReturn(true);
            when(contentTransformClient.transformContent(NODE_DB_ID)).thenReturn(ContentTransformResult.ofText(TRANSFORMED_TEXT));

            writer.write(Chunk.of(event));

            verify(contentModifyPort).upsertContent(contentIndexCmdCaptor.capture());
            ContentIndexCmd captured = contentIndexCmdCaptor.getValue();

            assertThat(captured.id()).isEqualTo(RESOURCE_UUID);
            assertThat(captured.updateTime()).isEqualTo(EVENT_TIME.toInstant().toEpochMilli());
            assertThat(captured.contentText()).isEqualTo(TRANSFORMED_TEXT);
        }
    }

    @Nested
    @DisplayName("Failure scenarios")
    class FailureScenarios
    {
        @Test
        @DisplayName("Empty transform result is not counted as a failure and does not touch the index")
        void emptyResultIsNoOp() throws Exception
        {
            ReindexingEvent event1 = buildEvent("uuid-1", 10L, MIME_TYPE);
            ReindexingEvent event2 = buildEvent("uuid-2", 20L, MIME_TYPE);

            when(contentTransformSupportService.isContentTransformSupported(any())).thenReturn(true);
            when(contentTransformClient.transformContent(anyLong())).thenReturn(ContentTransformResult.empty());

            writer.write(Chunk.of(event1, event2));

            // 200-empty from ACS is documented as "no content this cycle" — no index write, no listener.
            verifyNoInteractions(contentModifyPort);
        }

        @Test
        @DisplayName("Failure result is forwarded to the optional listener with the correlated node context")
        void failureResultTriggersListener() throws Exception
        {
            ContentTransformFailureListener listener = mock(ContentTransformFailureListener.class);
            ContentTransformDirectWriter writerWithListener = new ContentTransformDirectWriter(
                    contentTransformSupportService, contentTransformClient, contentModifyPort, STORE_REF, 1, listener);

            ReindexingEvent success = buildEvent("uuid-ok", 10L, MIME_TYPE);
            ReindexingEvent failure = buildEvent("uuid-fail", 20L, MIME_TYPE);

            when(contentTransformSupportService.isContentTransformSupported(any())).thenReturn(true);
            when(contentTransformClient.transformContent(10L)).thenReturn(ContentTransformResult.ofText("good text"));
            when(contentTransformClient.transformContent(20L)).thenReturn(ContentTransformResult.ofFailure(
                    new ContentTransformFailure("TRANSFORM_HTTP_400", "malformed input")));

            writerWithListener.write(Chunk.of(success, failure));

            verify(contentModifyPort, times(1)).upsertContent(any(ContentIndexCmd.class));
            verify(listener).onContentTransformFailure(
                    eq(20L),
                    eq(STORE_REF + "uuid-fail"),
                    eq(EVENT_TIME.toInstant().toEpochMilli()),
                    eq("TRANSFORM_HTTP_400"),
                    eq("malformed input"));
        }

        @Test
        @DisplayName("Listener exception is swallowed — the writer must not fail the chunk")
        void listenerExceptionIsIsolated() throws Exception
        {
            ContentTransformFailureListener listener = mock(ContentTransformFailureListener.class);
            doThrow(new RuntimeException("dlq offline")).when(listener)
                    .onContentTransformFailure(anyLong(), any(), any(), any(), any());

            ContentTransformDirectWriter writerWithListener = new ContentTransformDirectWriter(
                    contentTransformSupportService, contentTransformClient, contentModifyPort, STORE_REF, 1, listener);

            ReindexingEvent event = buildEvent(RESOURCE_UUID, NODE_DB_ID, MIME_TYPE);
            when(contentTransformSupportService.isContentTransformSupported(any())).thenReturn(true);
            when(contentTransformClient.transformContent(NODE_DB_ID)).thenReturn(ContentTransformResult.ofFailure(
                    new ContentTransformFailure("TRANSFORM_HTTP_401", "unauthorized")));

            assertThatCode(() -> writerWithListener.write(Chunk.of(event))).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Exception from transformContent is caught and does not touch the index")
        void transformContentThrowsException() throws Exception
        {
            ReindexingEvent event = buildEvent(RESOURCE_UUID, NODE_DB_ID, MIME_TYPE);

            when(contentTransformSupportService.isContentTransformSupported(any())).thenReturn(true);
            when(contentTransformClient.transformContent(NODE_DB_ID))
                    .thenThrow(new RuntimeException("connection timeout"));

            assertThatCode(() -> writer.write(Chunk.of(event))).doesNotThrowAnyException();

            verifyNoInteractions(contentModifyPort);
        }

        @Test
        @DisplayName("Exception from upsertContent is caught and does not fail the chunk")
        void upsertContentThrowsException() throws Exception
        {
            ReindexingEvent event = buildEvent(RESOURCE_UUID, NODE_DB_ID, MIME_TYPE);

            when(contentTransformSupportService.isContentTransformSupported(any())).thenReturn(true);
            when(contentTransformClient.transformContent(NODE_DB_ID)).thenReturn(ContentTransformResult.ofText(TRANSFORMED_TEXT));
            doThrow(new RuntimeException("ES write failed")).when(contentModifyPort).upsertContent(any());

            assertThatCode(() -> writer.write(Chunk.of(event))).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("MIME type handling")
    class MimeTypeHandling
    {
        @Test
        @DisplayName("Null MIME type skips transformation but is not counted as failure")
        void nullMimeTypeSkippedNotFailure() throws Exception
        {
            ReindexingEvent nullMime = buildEvent(RESOURCE_UUID, NODE_DB_ID, null);
            ReindexingEvent withMime = buildEvent("uuid-ok", 10L, MIME_TYPE);

            when(contentTransformSupportService.isContentTransformSupported(any())).thenReturn(true);
            when(contentTransformClient.transformContent(10L)).thenReturn(ContentTransformResult.ofText("text"));

            writer.write(Chunk.of(nullMime, withMime));

            verify(contentTransformClient, never()).transformContent(NODE_DB_ID);
            verify(contentTransformClient).transformContent(10L);
            verify(contentModifyPort, times(1)).upsertContent(any());
        }

        @Test
        @DisplayName("Event with no ContentInfo at all results in null MIME and is skipped")
        void noContentInfoSkipped() throws Exception
        {
            ReindexingEvent event = buildEventWithoutContent(RESOURCE_UUID, NODE_DB_ID);

            when(contentTransformSupportService.isContentTransformSupported(any())).thenReturn(true);

            writer.write(Chunk.of(event));

            verifyNoInteractions(contentTransformClient);
            verifyNoInteractions(contentModifyPort);
        }
    }

    @Nested
    @DisplayName("UUID extraction from resourceId")
    class UuidExtraction
    {
        @Test
        @DisplayName("UUID is extracted as the substring after the last slash in storeRef + resourceId")
        void extractsUuidAfterLastSlash() throws Exception
        {
            String uuid = "abc-123-def-456";
            ReindexingEvent event = buildEvent(uuid, NODE_DB_ID, MIME_TYPE);

            when(contentTransformSupportService.isContentTransformSupported(any())).thenReturn(true);
            when(contentTransformClient.transformContent(NODE_DB_ID)).thenReturn(ContentTransformResult.ofText(TRANSFORMED_TEXT));

            writer.write(Chunk.of(event));

            verify(contentModifyPort).upsertContent(contentIndexCmdCaptor.capture());
            assertThat(contentIndexCmdCaptor.getValue().id()).isEqualTo(uuid);
        }

        @Test
        @DisplayName("Resource ID with nested slashes still extracts only the trailing segment")
        void nestedSlashesExtractLastSegment() throws Exception
        {
            String resourceId = "extra/nested/path/final-uuid";
            ReindexingEvent event = buildEvent(resourceId, NODE_DB_ID, MIME_TYPE);

            when(contentTransformSupportService.isContentTransformSupported(any())).thenReturn(true);
            when(contentTransformClient.transformContent(NODE_DB_ID)).thenReturn(ContentTransformResult.ofText(TRANSFORMED_TEXT));

            writer.write(Chunk.of(event));

            verify(contentModifyPort).upsertContent(contentIndexCmdCaptor.capture());
            assertThat(contentIndexCmdCaptor.getValue().id()).isEqualTo("final-uuid");
        }

        @Test
        @DisplayName("storeRef without trailing slash is normalized so UUID extraction still works")
        void storeRefWithoutTrailingSlashIsNormalized() throws Exception
        {
            String uuid = "abc-123";
            ReindexingEvent event = buildEvent(uuid, NODE_DB_ID, MIME_TYPE);

            ContentTransformDirectWriter writerNoSlash = new ContentTransformDirectWriter(
                    contentTransformSupportService, contentTransformClient, contentModifyPort,
                    "workspace://SpacesStore");

            when(contentTransformSupportService.isContentTransformSupported(any())).thenReturn(true);
            when(contentTransformClient.transformContent(NODE_DB_ID)).thenReturn(ContentTransformResult.ofText(TRANSFORMED_TEXT));

            writerNoSlash.write(Chunk.of(event));

            verify(contentModifyPort).upsertContent(contentIndexCmdCaptor.capture());
            assertThat(contentIndexCmdCaptor.getValue().id()).isEqualTo(uuid);
        }

        @Test
        @DisplayName("null storeRef is rejected at construction time")
        void nullStoreRefThrows()
        {
            assertThatCode(() -> new ContentTransformDirectWriter(
                    contentTransformSupportService, contentTransformClient, contentModifyPort, null))
                            .isInstanceOf(IllegalArgumentException.class)
                            .hasMessageContaining("storeRef");
        }

        @Test
        @DisplayName("empty storeRef is rejected at construction time")
        void emptyStoreRefThrows()
        {
            assertThatCode(() -> new ContentTransformDirectWriter(
                    contentTransformSupportService, contentTransformClient, contentModifyPort, ""))
                            .isInstanceOf(IllegalArgumentException.class)
                            .hasMessageContaining("storeRef");
        }

        @Test
        @DisplayName("blank storeRef is rejected at construction time")
        void blankStoreRefThrows()
        {
            assertThatCode(() -> new ContentTransformDirectWriter(
                    contentTransformSupportService, contentTransformClient, contentModifyPort, "   "))
                            .isInstanceOf(IllegalArgumentException.class)
                            .hasMessageContaining("storeRef");
        }
    }

    @Nested
    @DisplayName("Mixed scenarios")
    class MixedScenarios
    {
        @Test
        @DisplayName("Mix of filtered, null-mime, successful, and failed items in one chunk")
        void mixedChunk() throws Exception
        {
            ReindexingEvent filtered = buildEvent("uuid-filtered", 1L, MIME_TYPE);
            ReindexingEvent nullMime = buildEvent("uuid-null-mime", 2L, null);
            ReindexingEvent success = buildEvent("uuid-success", 3L, MIME_TYPE);
            ReindexingEvent failure = buildEvent("uuid-failure", 4L, MIME_TYPE);

            when(contentTransformSupportService.isContentTransformSupported(filtered)).thenReturn(false);
            when(contentTransformSupportService.isContentTransformSupported(nullMime)).thenReturn(true);
            when(contentTransformSupportService.isContentTransformSupported(success)).thenReturn(true);
            when(contentTransformSupportService.isContentTransformSupported(failure)).thenReturn(true);

            when(contentTransformClient.transformContent(3L)).thenReturn(ContentTransformResult.ofText("text"));
            when(contentTransformClient.transformContent(4L)).thenReturn(ContentTransformResult.empty());

            writer.write(Chunk.of(filtered, nullMime, success, failure));

            verify(contentTransformClient, never()).transformContent(1L);
            verify(contentTransformClient, never()).transformContent(2L);
            verify(contentModifyPort, times(1)).upsertContent(any());
        }

        @Test
        @DisplayName("storeRef is prepended to resource ID to form the full nodeRef")
        void resourceIdIncludesStoreRef() throws Exception
        {
            String uuid = "the-resource-id";
            ReindexingEvent event = buildEvent(uuid, NODE_DB_ID, MIME_TYPE);

            when(contentTransformSupportService.isContentTransformSupported(any())).thenReturn(true);
            when(contentTransformClient.transformContent(NODE_DB_ID)).thenReturn(ContentTransformResult.ofText(TRANSFORMED_TEXT));

            writer.write(Chunk.of(event));

            verify(contentModifyPort).upsertContent(contentIndexCmdCaptor.capture());
            assertThat(contentIndexCmdCaptor.getValue().id()).isEqualTo(uuid);
        }
    }

    private ReindexingEvent buildEvent(String resourceId, Long nodeDbId, String mimeType)
    {
        NodeResource.Builder resourceBuilder = NodeResource.builder().setId(resourceId);
        if (mimeType != null)
        {
            resourceBuilder.setContent(new ContentInfo(mimeType, 100L, "UTF-8"));
        }

        EventData<NodeResource> eventData = EventData.<NodeResource> builder()
                .setResource(resourceBuilder.build())
                .build();

        RepoEvent<EventData<NodeResource>> repoEvent = RepoEvent.<EventData<NodeResource>> builder()
                .setType("org.alfresco.event.node.Created")
                .setData(eventData)
                .setTime(EVENT_TIME)
                .setId("event-" + resourceId)
                .build();

        return ReindexingEvent.builder()
                .withRepoEvent(repoEvent)
                .withNodeDbId(nodeDbId)
                .build();
    }

    private ReindexingEvent buildEventWithoutContent(String resourceId, Long nodeDbId)
    {
        NodeResource resource = NodeResource.builder().setId(resourceId).build();

        EventData<NodeResource> eventData = EventData.<NodeResource> builder()
                .setResource(resource)
                .build();

        RepoEvent<EventData<NodeResource>> repoEvent = RepoEvent.<EventData<NodeResource>> builder()
                .setType("org.alfresco.event.node.Created")
                .setData(eventData)
                .setTime(EVENT_TIME)
                .setId("event-" + resourceId)
                .build();

        return ReindexingEvent.builder()
                .withRepoEvent(repoEvent)
                .withNodeDbId(nodeDbId)
                .build();
    }
}
