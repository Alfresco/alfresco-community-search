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
 * Mirror of {@link DlqBulkItemFailureListenerTest} / {@link DlqPathBulkItemFailureListenerTest} for the merged-writer variant. Only difference is the target {@link FailureStage}, so the merged write path stays distinguishable from metadata and path in the DLQ.
 */
@ExtendWith(MockitoExtension.class)
class DlqMergedBulkItemFailureListenerTest
{
    @Mock
    private DlqWriter dlqWriter;

    @InjectMocks
    private DlqMergedBulkItemFailureListener listener;

    @Test
    void forwardsToDlqWriterWithMergedWriterStage()
    {
        listener.onMergedBulkItemFailure(42L, "workspace://SpacesStore/abc", 1_700_000_000_000L,
                "mapper_parsing_exception", "bad field");

        verify(dlqWriter).recordSkip(42L, "workspace://SpacesStore/abc", 1_700_000_000_000L,
                FailureStage.MERGED_WRITER, "mapper_parsing_exception", "bad field");
    }

    @Test
    void forwardsWhenDbIdMissing()
    {
        listener.onMergedBulkItemFailure(null, "workspace://SpacesStore/abc", null, "x", "y");

        verify(dlqWriter).recordSkip(null, "workspace://SpacesStore/abc", null,
                FailureStage.MERGED_WRITER, "x", "y");
    }
}
