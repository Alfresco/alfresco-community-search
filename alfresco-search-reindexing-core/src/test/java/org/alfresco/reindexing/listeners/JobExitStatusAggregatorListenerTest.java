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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.step.StepExecution;

class JobExitStatusAggregatorListenerTest
{
    private final JobExitStatusAggregatorListener listener = new JobExitStatusAggregatorListener();

    @Test
    void shouldAggregateFailedStepExitDescriptionOntoJob()
    {
        JobExecution jobExecution = createJobExecution();
        jobExecution.setExitStatus(ExitStatus.FAILED);

        StepExecution stepExecution = new StepExecution(1L, "reindexByIdsStep", jobExecution);
        stepExecution.setExitStatus(ExitStatus.FAILED.addExitDescription("java.net.SocketTimeoutException: 30000 MILLISECONDS"));
        jobExecution.addStepExecutions(List.of(stepExecution));

        listener.afterJob(jobExecution);

        assertThat(jobExecution.getExitStatus().getExitCode()).isEqualTo(ExitStatus.FAILED.getExitCode());
        assertThat(jobExecution.getExitStatus().getExitDescription()).contains("java.net.SocketTimeoutException: 30000 MILLISECONDS");
    }

    @Test
    void shouldPreserveEmptyDescriptionWhenAllStepsSucceeded()
    {
        JobExecution jobExecution = createJobExecution();
        jobExecution.setExitStatus(ExitStatus.COMPLETED);

        StepExecution stepExecution = new StepExecution(1L, "reindexByIdsStep", jobExecution);
        stepExecution.setExitStatus(ExitStatus.COMPLETED);
        jobExecution.addStepExecutions(List.of(stepExecution));

        listener.afterJob(jobExecution);

        assertThat(jobExecution.getExitStatus().getExitCode()).isEqualTo(ExitStatus.COMPLETED.getExitCode());
        assertThat(jobExecution.getExitStatus().getExitDescription()).isEmpty();
    }

    @Test
    void shouldAggregateMultipleStepsKeepingFailureDescription()
    {
        JobExecution jobExecution = createJobExecution();
        jobExecution.setExitStatus(ExitStatus.FAILED);

        StepExecution step1 = new StepExecution(1L, "validateDbSchemaStep", jobExecution);
        step1.setExitStatus(ExitStatus.COMPLETED);

        StepExecution step2 = new StepExecution(2L, "reindexByIdsStep", jobExecution);
        step2.setExitStatus(ExitStatus.FAILED.addExitDescription("Connection refused"));

        jobExecution.addStepExecutions(List.of(step1, step2));

        listener.afterJob(jobExecution);

        assertThat(jobExecution.getExitStatus().getExitCode()).isEqualTo(ExitStatus.FAILED.getExitCode());
        assertThat(jobExecution.getExitStatus().getExitDescription()).contains("Connection refused");
    }

    private JobExecution createJobExecution()
    {
        return new JobExecution(1L, new JobInstance(1L, "testJob"), new JobParameters());
    }
}
