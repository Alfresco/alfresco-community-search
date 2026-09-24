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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.listener.SkipListener;

import org.alfresco.database.connector.model.AlfrescoNode;
import org.alfresco.elasticsearch.batchindexing.DlqWriter;
import org.alfresco.elasticsearch.batchindexing.model.DeadLetterDocument.FailureStage;
import org.alfresco.reindexing.model.ReindexingEvent;
import org.alfresco.reindexing.processors.UnresolvedNamespaceException;
import org.alfresco.repo.event.v1.model.EventData;
import org.alfresco.repo.event.v1.model.NodeResource;
import org.alfresco.repo.event.v1.model.RepoEvent;

/**
 * Bridges Spring Batch skip events into {@link DlqWriter}. Typed to {@code SkipListener<AlfrescoNode, ReindexingEvent>} because that matches the reindex-by-date step's I/O; the reindexing module's skip-listener hook wires this in via {@code ObjectProvider<SkipListener<?, ?>>} so no explicit registration is needed.
 */
public class DlqSkipListener implements SkipListener<AlfrescoNode, ReindexingEvent>
{
    private static final Logger LOGGER = LoggerFactory.getLogger(DlqSkipListener.class);

    private final DlqWriter dlqWriter;

    public DlqSkipListener(DlqWriter dlqWriter)
    {
        this.dlqWriter = dlqWriter;
    }

    @Override
    public void onSkipInRead(Throwable throwable)
    {
        // Read-side skips don't carry an item, so there's no dbId to key a DLQ record on.
        // Log at ERROR so the operator sees it; classification lives in a follow-up.
        LOGGER.error("Skip on reader (no item context): {}", throwable == null ? "<null>" : throwable.getMessage(), throwable);
    }

    @Override
    public void onSkipInProcess(AlfrescoNode item, Throwable throwable)
    {
        if (item == null)
        {
            LOGGER.warn("Process-side skip with null item; not recording");
            return;
        }
        if (throwable instanceof UnresolvedNamespaceException)
        {
            // Already recorded by DlqUnresolvedNamespaceListener, which runs whatever the configured action.
            return;
        }
        dlqWriter.recordSkip(item.getId(), item.getNodeRef(), item.getTimestamp(), FailureStage.PROCESSOR, throwable);
    }

    @Override
    public void onSkipInWrite(ReindexingEvent item, Throwable throwable)
    {
        if (item == null)
        {
            LOGGER.warn("Write-side skip with null item; not recording");
            return;
        }
        dlqWriter.recordSkip(item.getNodeDbId(), extractNodeRef(item), extractCommitTimeMs(item), FailureStage.METADATA_WRITER, throwable);
    }

    private static String extractNodeRef(ReindexingEvent event)
    {
        RepoEvent<EventData<NodeResource>> repoEvent = event.getRepoEvent();
        if (repoEvent == null || repoEvent.getData() == null)
        {
            return null;
        }
        NodeResource resource = repoEvent.getData().getResource();
        return resource == null ? null : resource.getId();
    }

    private static Long extractCommitTimeMs(ReindexingEvent event)
    {
        return event.getTime() == null ? null : event.getTime().toInstant().toEpochMilli();
    }
}
