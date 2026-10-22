/*-
 * #%L
 * Alfresco Search Community :: Batch Indexing
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
package org.alfresco.elasticsearch.batchindexing.listeners;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.alfresco.database.connector.model.AlfrescoNode;
import org.alfresco.elasticsearch.batchindexing.DlqWriter;
import org.alfresco.elasticsearch.batchindexing.model.DeadLetterDocument.FailureStage;
import org.alfresco.reindexing.model.ReindexingEvent;
import org.alfresco.reindexing.processors.UnresolvedNamespaceException;
import org.alfresco.repo.event.v1.model.EventData;
import org.alfresco.repo.event.v1.model.NodeResource;
import org.alfresco.repo.event.v1.model.RepoEvent;

@ExtendWith(MockitoExtension.class)
class DlqSkipListenerTest
{
    @Mock
    private DlqWriter dlqWriter;

    private DlqSkipListener listener;

    @BeforeEach
    void setUp()
    {
        listener = new DlqSkipListener(dlqWriter);
    }

    @Test
    void readSkipDoesNotWriteDlq()
    {
        listener.onSkipInRead(new IOException("db down"));

        verifyNoInteractions(dlqWriter);
    }

    @Test
    void processSkipRecordsNodeIdAndRef()
    {
        AlfrescoNode node = new AlfrescoNode();
        node.setId(42L);
        node.setNodeRef("workspace://SpacesStore/abc");
        node.setTimestamp(1_700_000_000_000L);
        IllegalStateException t = new IllegalStateException("bad node");

        listener.onSkipInProcess(node, t);

        verify(dlqWriter).recordSkip(eq(42L), eq("workspace://SpacesStore/abc"), eq(1_700_000_000_000L),
                eq(FailureStage.PROCESSOR), eq(t));
    }

    @Test
    void processSkipForAnUnresolvedNamespaceIsNoOp()
    {
        AlfrescoNode node = new AlfrescoNode();
        node.setId(42L);
        node.setNodeRef("workspace://SpacesStore/abc");

        listener.onSkipInProcess(node,
                new UnresolvedNamespaceException(42L, "http://acme", "invoice", false));

        // DlqUnresolvedNamespaceListener already recorded it; a second write would overwrite failureType.
        verifyNoInteractions(dlqWriter);
    }

    @Test
    void processSkipWithNullItemIsNoOp()
    {
        listener.onSkipInProcess(null, new IllegalStateException("x"));

        verifyNoInteractions(dlqWriter);
    }

    @Test
    void writeSkipRecordsNodeIdAndDerivedFields()
    {
        NodeResource resource = NodeResource.builder().setId("workspace://SpacesStore/xyz").build();
        RepoEvent<EventData<NodeResource>> repoEvent = mock(RepoEvent.class);
        when(repoEvent.getData()).thenReturn(EventData.<NodeResource> builder().setResource(resource).build());
        ReindexingEvent event = mock(ReindexingEvent.class);
        when(event.getNodeDbId()).thenReturn(99L);
        when(event.getRepoEvent()).thenReturn(repoEvent);
        when(event.getTime()).thenReturn(ZonedDateTime.of(2026, 1, 2, 3, 4, 5, 0, ZoneOffset.UTC));
        IOException t = new IOException("es down");

        listener.onSkipInWrite(event, t);

        verify(dlqWriter).recordSkip(eq(99L), eq("workspace://SpacesStore/xyz"),
                eq(ZonedDateTime.of(2026, 1, 2, 3, 4, 5, 0, ZoneOffset.UTC).toInstant().toEpochMilli()),
                eq(FailureStage.METADATA_WRITER), eq(t));
    }

    @Test
    void writeSkipToleratesMissingRepoEventContext()
    {
        ReindexingEvent event = mock(ReindexingEvent.class);
        when(event.getNodeDbId()).thenReturn(7L);
        when(event.getRepoEvent()).thenReturn(null);
        when(event.getTime()).thenReturn(null);
        IOException t = new IOException("boom");

        listener.onSkipInWrite(event, t);

        verify(dlqWriter).recordSkip(eq(7L), isNull(), isNull(), eq(FailureStage.METADATA_WRITER), eq(t));
    }

    @Test
    void writeSkipWithNullItemIsNoOp()
    {
        listener.onSkipInWrite(null, new IOException("x"));

        verifyNoInteractions(dlqWriter);
    }

    @Test
    @SuppressWarnings("unchecked")
    void writeSkipHandlesRepoEventWithNullResource()
    {
        RepoEvent<EventData<NodeResource>> repoEvent = mock(RepoEvent.class);
        when(repoEvent.getData()).thenReturn(EventData.<NodeResource> builder().build());
        ReindexingEvent event = mock(ReindexingEvent.class);
        when(event.getNodeDbId()).thenReturn(7L);
        when(event.getRepoEvent()).thenReturn(repoEvent);
        when(event.getTime()).thenReturn(null);

        listener.onSkipInWrite(event, new IOException("x"));

        verify(dlqWriter).recordSkip(eq(7L), isNull(), isNull(), eq(FailureStage.METADATA_WRITER), any(IOException.class));
    }
}
