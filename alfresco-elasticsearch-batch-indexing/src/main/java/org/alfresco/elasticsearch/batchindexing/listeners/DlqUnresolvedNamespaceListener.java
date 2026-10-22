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
import org.alfresco.reindexing.processors.UnresolvedNamespaceListener;

/**
 * Records a node lost to an unresolved namespace in the dead-letter index, so it can be reprocessed once the prefix mapping is fixed (ACS-12851).
 * <p>
 * The node is filtered out rather than failed, so this never reaches Spring Batch's skip machinery — the record is written directly instead.
 */
public class DlqUnresolvedNamespaceListener implements UnresolvedNamespaceListener
{
    static final String FAILURE_TYPE = "unresolved_namespace";

    private final DlqWriter dlqWriter;

    public DlqUnresolvedNamespaceListener(DlqWriter dlqWriter)
    {
        this.dlqWriter = dlqWriter;
    }

    @Override
    public void onUnresolvedNamespace(Long dbId, String nodeRef, Long sourceCommitTimeMs,
            String namespaceUri, String localName)
    {
        dlqWriter.recordSkip(dbId, nodeRef, sourceCommitTimeMs, FailureStage.PROCESSOR, FAILURE_TYPE,
                "no prefix mapping for namespace " + namespaceUri + "; type " + localName + " cannot be indexed");
    }
}
