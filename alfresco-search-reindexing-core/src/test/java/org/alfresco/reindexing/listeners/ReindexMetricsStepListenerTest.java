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

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.step.StepExecution;

import org.alfresco.metrics.MetricsConstants;
import org.alfresco.metrics.ReindexingMetrics;

class ReindexMetricsStepListenerTest
{
    private static final long JOB_EXECUTION_ID = 7L;

    private ReindexingMetrics metrics;

    @BeforeEach
    void setUp()
    {
        metrics = mock(ReindexingMetrics.class);
    }

    @Test
    void beforeStepWiresJobExecutionIdAndPreRegistersEnabledWriters()
    {
        List<String> enabledWriters = List.of(MetricsConstants.METADATA, MetricsConstants.CONTENT);
        ReindexMetricsStepListener listener = new ReindexMetricsStepListener(metrics, enabledWriters);

        listener.beforeStep(stepExecution());

        verify(metrics).setJobExecutionId(JOB_EXECUTION_ID);
        verify(metrics).preRegisterStepMetrics(enabledWriters);
    }

    private static StepExecution stepExecution()
    {
        JobExecution jobExecution = new JobExecution(JOB_EXECUTION_ID, new JobInstance(1L, "testJob"), new JobParameters());
        return new StepExecution(1L, "reindexStep", jobExecution);
    }
}
