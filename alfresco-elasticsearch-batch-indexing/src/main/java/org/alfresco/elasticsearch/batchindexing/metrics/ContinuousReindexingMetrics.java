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
package org.alfresco.elasticsearch.batchindexing.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import org.alfresco.elasticsearch.batchindexing.observability.ContinuousReindexingMonitor;
import org.alfresco.metrics.MetricsConstants;

/**
 * Registers Micrometer gauges that expose the continuous reindexing scheduler heartbeats. The gauges read directly from the monitor's atomic timestamps on scrape, so they always reflect the most recent state.
 */
public class ContinuousReindexingMetrics
{
    public ContinuousReindexingMetrics(ContinuousReindexingMonitor monitor, MeterRegistry registry)
    {
        Gauge.builder(
                MetricsConstants.REINDEX_LAST_ACTIVITY_TIME,
                monitor,
                ContinuousReindexingMonitor::getLastActivityAtEpochMillis)
                .description("Epoch milliseconds of the last sign of life from the continuous reindexing scheduler "
                        + "(cycle start, cycle finish, or committed chunk). Zero means no cycle has run yet.")
                .register(registry);

        Gauge.builder(
                MetricsConstants.REINDEX_LAST_CYCLE_END_TIME,
                monitor,
                ContinuousReindexingMonitor::getLastCycleFinishedAtEpochMillis)
                .description("Epoch milliseconds when the continuous reindexing scheduler last finished a cycle. "
                        + "Zero means no cycle has finished yet.")
                .register(registry);
    }
}
