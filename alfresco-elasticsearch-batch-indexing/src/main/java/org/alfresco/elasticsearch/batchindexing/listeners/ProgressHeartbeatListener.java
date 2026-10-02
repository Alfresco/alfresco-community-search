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

import org.springframework.batch.core.listener.ItemWriteListener;
import org.springframework.batch.infrastructure.item.Chunk;

import org.alfresco.elasticsearch.batchindexing.observability.ContinuousReindexingMonitor;

/**
 * Ticks the {@link ContinuousReindexingMonitor} progress heartbeat after each written chunk so the liveness probe can distinguish a slow-but-progressing cycle from a stuck one. Item types are erased because the heartbeat does not touch the item payload.
 */
public class ProgressHeartbeatListener implements ItemWriteListener<Object>
{
    private final ContinuousReindexingMonitor monitor;

    public ProgressHeartbeatListener(ContinuousReindexingMonitor monitor)
    {
        this.monitor = monitor;
    }

    @Override
    public void afterWrite(Chunk<?> items)
    {
        monitor.recordActivity();
    }
}
