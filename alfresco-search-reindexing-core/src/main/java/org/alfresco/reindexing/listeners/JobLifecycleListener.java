/*-
 * #%L
 * Alfresco Search Community :: Reindexing Core
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
package org.alfresco.reindexing.listeners;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.listener.JobExecutionListener;

import org.alfresco.metrics.ReindexingMetrics;
import org.alfresco.reindexing.config.ReindexNodeRole;

public class JobLifecycleListener implements JobExecutionListener
{
    private static final Logger LOGGER = LoggerFactory.getLogger(JobLifecycleListener.class);

    private final ReindexingMetrics metrics;
    private final ReindexNodeRole nodeRole;

    public JobLifecycleListener(ReindexingMetrics metrics, ReindexNodeRole nodeRole)
    {
        this.metrics = metrics;
        this.nodeRole = nodeRole;
    }

    @Override
    public void beforeJob(JobExecution jobExecution)
    {
        // Only a node that owns the job lifecycle records job metrics
        if (!nodeRole.recordsJobMetrics())
        {
            LOGGER.debug("Job metrics recording disabled for this node - SKIPPING job start metrics recording");
            return;
        }

        LOGGER.info("Reindex job {} started", jobExecution.getId());
        metrics.setJobExecutionId(jobExecution.getId());
        metrics.recordJobStart();
    }

    @Override
    public void afterJob(JobExecution jobExecution)
    {
        // Only a node that owns the job lifecycle records job metrics
        if (!nodeRole.recordsJobMetrics())
        {
            LOGGER.debug("Job metrics recording disabled for this node - SKIPPING job completion metrics recording");
            return;
        }

        BatchStatus status = jobExecution.getStatus();
        LOGGER.info("Reindex job {} finished with status {}", jobExecution.getId(), status);

        if (status == BatchStatus.COMPLETED)
        {
            metrics.recordJobComplete();
        }
        else if (status == BatchStatus.STOPPED)
        {
            LOGGER.warn("Reindex job {} was stopped", jobExecution.getId());
            metrics.recordJobStopped();
        }
        else if (status == BatchStatus.FAILED || status == BatchStatus.ABANDONED || status == BatchStatus.UNKNOWN)
        {
            LOGGER.warn("Reindex job {} FAILED with status {}", jobExecution.getId(), status);
            metrics.recordJobFailed();
        }
    }
}
