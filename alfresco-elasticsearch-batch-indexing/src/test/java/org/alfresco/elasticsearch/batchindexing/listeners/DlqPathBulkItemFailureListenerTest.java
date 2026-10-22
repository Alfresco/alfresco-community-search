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

import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.alfresco.elasticsearch.batchindexing.DlqWriter;
import org.alfresco.elasticsearch.batchindexing.model.DeadLetterDocument.FailureStage;

/**
 * Mirror of {@link DlqBulkItemFailureListenerTest} for the path-writer variant. Only difference is the target {@link FailureStage}, so both bridges can be told apart in the DLQ.
 */
@ExtendWith(MockitoExtension.class)
class DlqPathBulkItemFailureListenerTest
{
    @Mock
    private DlqWriter dlqWriter;

    @InjectMocks
    private DlqPathBulkItemFailureListener listener;

    @Test
    void forwardsToDlqWriterWithPathWriterStage()
    {
        listener.onPathBulkItemFailure(42L, "workspace://SpacesStore/abc", 1_700_000_000_000L,
                "strict_dynamic_mapping_exception", "unknown field");

        verify(dlqWriter).recordSkip(42L, "workspace://SpacesStore/abc", 1_700_000_000_000L,
                FailureStage.PATH_WRITER, "strict_dynamic_mapping_exception", "unknown field");
    }

    @Test
    void forwardsWhenDbIdMissing()
    {
        listener.onPathBulkItemFailure(null, "workspace://SpacesStore/abc", null, "x", "y");

        verify(dlqWriter).recordSkip(null, "workspace://SpacesStore/abc", null,
                FailureStage.PATH_WRITER, "x", "y");
    }
}
