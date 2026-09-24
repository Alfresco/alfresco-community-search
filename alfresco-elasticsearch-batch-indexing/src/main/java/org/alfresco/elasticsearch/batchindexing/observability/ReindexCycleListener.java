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
package org.alfresco.elasticsearch.batchindexing.observability;

/**
 * Reacts to reindex lifecycle events fanned out by {@link ContinuousReindexingMonitor}. A cycle is one scheduled invocation of the continuous reindexing loop; a cycle contains one or more jobs (multiple during catch-up recovery). Implementations should not throw; the monitor logs and swallows exceptions raised by listeners so one bad listener cannot break the rest.
 */
public interface ReindexCycleListener
{
    /**
     * Fired at the top of a scheduler tick, before any job runs.
     */
    default void onCycleStart()
    {
        // no-op default; implementations override the callbacks they care about
    }

    /**
     * Fired in the finally block of a scheduler tick, regardless of outcome.
     */
    default void onCycleEnd()
    {
        // no-op default; implementations override the callbacks they care about
    }

    /**
     * Fired before a Spring Batch job is launched.
     */
    default void onJobStart()
    {
        // no-op default; implementations override the callbacks they care about
    }

    /**
     * Fired after a Spring Batch job returns {@code COMPLETED}. Not fired on failure.
     */
    default void onJobSuccess()
    {
        // no-op default; implementations override the callbacks they care about
    }

    /**
     * Fired in the finally block covering the job launch, regardless of outcome.
     */
    default void onJobEnd()
    {
        // no-op default; implementations override the callbacks they care about
    }
}
