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

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.alfresco.elasticsearch.batchindexing.DlqWriter;
import org.alfresco.elasticsearch.batchindexing.model.DeadLetterDocument.FailureStage;

/**
 * Bridges a node lost to an unresolved namespace into the DLQ (ACS-12851). Unlike the other bridges this one does not run through Spring Batch skip, so it is invoked whatever {@code onUnresolvedNamespace} is set to.
 */
@ExtendWith(MockitoExtension.class)
class DlqUnresolvedNamespaceListenerTest
{
    @Mock
    private DlqWriter dlqWriter;

    @InjectMocks
    private DlqUnresolvedNamespaceListener listener;

    @Test
    void forwardsToDlqWriterWithProcessorStageAndNamespaceFailureType()
    {
        listener.onUnresolvedNamespace(42L, "workspace://SpacesStore/abc", 1_700_000_000_000L,
                "http://acme.example.org/model/custom/1.0", "invoice");

        verify(dlqWriter).recordSkip(eq(42L), eq("workspace://SpacesStore/abc"), eq(1_700_000_000_000L),
                eq(FailureStage.PROCESSOR), eq(DlqUnresolvedNamespaceListener.FAILURE_TYPE),
                contains("http://acme.example.org/model/custom/1.0"));
    }

    @Test
    void reasonNamesTheTypeThatCouldNotBeResolved()
    {
        listener.onUnresolvedNamespace(42L, "workspace://SpacesStore/abc", 1L,
                "http://acme.example.org/model/custom/1.0", "invoice");

        verify(dlqWriter).recordSkip(eq(42L), eq("workspace://SpacesStore/abc"), eq(1L),
                eq(FailureStage.PROCESSOR), eq(DlqUnresolvedNamespaceListener.FAILURE_TYPE),
                contains("invoice"));
    }

    @Test
    void forwardsWhenNodeRefAndCommitTimeMissing()
    {
        listener.onUnresolvedNamespace(42L, null, null, "http://acme", "invoice");

        verify(dlqWriter).recordSkip(eq(42L), eq(null), eq(null),
                eq(FailureStage.PROCESSOR), eq(DlqUnresolvedNamespaceListener.FAILURE_TYPE),
                contains("http://acme"));
    }
}
