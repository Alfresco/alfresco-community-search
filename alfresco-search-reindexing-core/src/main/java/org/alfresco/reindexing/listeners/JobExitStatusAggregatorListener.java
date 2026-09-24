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

import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.listener.JobExecutionListener;
import org.springframework.batch.core.step.StepExecution;

/**
 * Aggregates step exit statuses onto the job's exit status.
 * <p>
 * Spring Batch's {@code FlowJob} sets the job's exit status via {@code JobFlowExecutor.updateJobExecutionStatus()}, which only carries the exit code (e.g. "FAILED") without any description. The step-level exit description (populated by {@code AbstractStep} with the full stack trace via {@code ExitStatus.addExitDescription(Throwable)}) is never propagated to the job. This listener bridges that gap using the same approach as {@code DefaultStepExecutionAggregator}. Job-level failure exceptions are handled by {@code AbstractJob.getDefaultExitStatusForFailure()} which already sets the exit description with the exception when an error escapes {@code doExecute()}.
 */
public class JobExitStatusAggregatorListener implements JobExecutionListener
{
    @Override
    public void afterJob(JobExecution jobExecution)
    {
        ExitStatus aggregated = jobExecution.getStepExecutions().stream()
                .map(StepExecution::getExitStatus)
                .reduce(jobExecution.getExitStatus(), ExitStatus::and);
        jobExecution.setExitStatus(aggregated);
    }
}
