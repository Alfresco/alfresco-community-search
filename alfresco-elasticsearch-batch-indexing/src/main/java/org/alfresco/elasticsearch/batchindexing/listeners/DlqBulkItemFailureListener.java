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

import org.alfresco.elasticsearch.batchindexing.DlqWriter;
import org.alfresco.elasticsearch.batchindexing.model.DeadLetterDocument.FailureStage;
import org.alfresco.reindexing.writers.BulkItemFailureListener;

/**
 * Bridges per-item bulk failures from the metadata writer into {@link DlqWriter}. Registered as a Spring bean; the reindexing module's writer picks it up via {@code ObjectProvider} and stays a no-op if no bean is present.
 * <p>
 * Stage is always {@link FailureStage#METADATA_WRITER} — the metadata bulk writer is the only caller.
 */
public class DlqBulkItemFailureListener implements BulkItemFailureListener
{
    private final DlqWriter dlqWriter;

    public DlqBulkItemFailureListener(DlqWriter dlqWriter)
    {
        this.dlqWriter = dlqWriter;
    }

    @Override
    public void onBulkItemFailure(Long dbId, String nodeRef, Long sourceCommitTimeMs, String errorType, String errorReason)
    {
        dlqWriter.recordSkip(dbId, nodeRef, sourceCommitTimeMs, FailureStage.METADATA_WRITER, errorType, errorReason);
    }
}
