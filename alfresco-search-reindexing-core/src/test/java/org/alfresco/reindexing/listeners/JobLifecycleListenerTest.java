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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;

import org.alfresco.metrics.ReindexingMetrics;
import org.alfresco.reindexing.config.ReindexNodeRole;

class JobLifecycleListenerTest
{
    private static final long JOB_ID = 42L;

    private ReindexingMetrics metrics;
    private ReindexNodeRole nodeRole;
    private JobLifecycleListener listener;

    @BeforeEach
    void setUp()
    {
        metrics = mock(ReindexingMetrics.class);
        nodeRole = mock(ReindexNodeRole.class);
        // a node that owns the job lifecycle: job metrics recording is enabled
        when(nodeRole.recordsJobMetrics()).thenReturn(true);
        listener = new JobLifecycleListener(metrics, nodeRole);
    }

    @Test
    void beforeJobWhenManagerModeSetsJobExecutionIdAndRecordsJobStart()
    {
        listener.beforeJob(jobExecution(BatchStatus.STARTING));

        verify(metrics).setJobExecutionId(JOB_ID);
        verify(metrics).recordJobStart();
    }

    @Test
    void beforeJobWhenWorkerModeDoesNotTouchMetrics()
    {
        when(nodeRole.recordsJobMetrics()).thenReturn(false);

        listener.beforeJob(jobExecution(BatchStatus.STARTING));

        verifyNoInteractions(metrics);
    }

    @Test
    void afterJobWhenWorkerModeDoesNotTouchMetrics()
    {
        when(nodeRole.recordsJobMetrics()).thenReturn(false);

        listener.afterJob(jobExecution(BatchStatus.COMPLETED));

        verifyNoInteractions(metrics);
    }

    @Test
    void afterJobWhenCompletedRecordsJobComplete()
    {
        listener.afterJob(jobExecution(BatchStatus.COMPLETED));

        verify(metrics).recordJobComplete();
    }

    @Test
    void afterJobWhenFailedRecordsJobFailed()
    {
        listener.afterJob(jobExecution(BatchStatus.FAILED));

        verify(metrics).recordJobFailed();
    }

    @Test
    void afterJobWhenStoppedRecordsJobStopped()
    {
        listener.afterJob(jobExecution(BatchStatus.STOPPED));

        verify(metrics).recordJobStopped();
        verify(metrics, org.mockito.Mockito.never()).recordJobComplete();
        verify(metrics, org.mockito.Mockito.never()).recordJobFailed();
    }

    @Test
    void afterJobWhenAbandonedRecordsJobFailed()
    {
        listener.afterJob(jobExecution(BatchStatus.ABANDONED));

        verify(metrics).recordJobFailed();
        verify(metrics, org.mockito.Mockito.never()).recordJobStopped();
    }

    @Test
    void afterJobWhenUnknownRecordsJobFailed()
    {
        listener.afterJob(jobExecution(BatchStatus.UNKNOWN));

        verify(metrics).recordJobFailed();
        verify(metrics, org.mockito.Mockito.never()).recordJobStopped();
    }

    private static JobExecution jobExecution(BatchStatus status)
    {
        JobExecution jobExecution = new JobExecution(JOB_ID, new JobInstance(1L, "testJob"), new JobParameters());
        jobExecution.setStatus(status);
        return jobExecution;
    }
}
