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
import org.alfresco.elasticsearch.batchindexing.writers.ContentTransformFailureListener;

/**
 * Bridges non-retryable content-transform failures (currently ACS 4xx) into {@link DlqWriter}. Registered as a Spring bean; {@code ContentTransformDirectWriter} picks it up via {@code ObjectProvider} and stays a no-op if no bean is present (e.g. when the DLQ config is disabled).
 * <p>
 * Stage is always {@link FailureStage#CONTENT_WRITER} — the content writer is the only caller. Separate from the metadata / path bridges so operators can tell content-side failures apart in the DLQ index without parsing the {@code failureType}.
 */
public class DlqContentTransformFailureListener implements ContentTransformFailureListener
{
    private final DlqWriter dlqWriter;

    public DlqContentTransformFailureListener(DlqWriter dlqWriter)
    {
        this.dlqWriter = dlqWriter;
    }

    @Override
    public void onContentTransformFailure(Long dbId, String nodeRef, Long sourceCommitTimeMs, String failureType, String failureReason)
    {
        dlqWriter.recordSkip(dbId, nodeRef, sourceCommitTimeMs, FailureStage.CONTENT_WRITER, failureType, failureReason);
    }
}
