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
import org.springframework.batch.core.listener.StepExecutionListener;
import org.springframework.batch.core.step.StepExecution;

import org.alfresco.reindexing.writers.FailureSummary;
import org.alfresco.reindexing.writers.WriterTimingSummary;

/**
 * Logs the per-writer timing ({@link WriterTimingSummary}) and failure ({@link FailureSummary}) roll-ups once per JVM at the end of a reindex, then resets them so a subsequent run in the same JVM starts fresh.
 * <p>
 * Attach this to the single indexing step in standalone mode, to each remote worker's step, and to the <b>local partition manager step</b> — never to the local partition <i>worker</i> steps. In local partitioning the writers and their two summaries are shared singletons across every partition worker step running in one JVM; logging + resetting from each worker step would let a partition that finishes early clear samples still being recorded by partitions that are still running, producing partial or cross-partition roll-ups. The manager step's {@code afterStep} runs only after every partition has completed, so it yields one correct whole-JVM summary.
 */
public class ReindexSummaryStepListener implements StepExecutionListener
{
    private final WriterTimingSummary writerTimingSummary;
    private final FailureSummary failureSummary;

    public ReindexSummaryStepListener(WriterTimingSummary writerTimingSummary, FailureSummary failureSummary)
    {
        this.writerTimingSummary = writerTimingSummary;
        this.failureSummary = failureSummary;
    }

    @Override
    public ExitStatus afterStep(StepExecution stepExecution)
    {
        String context = stepExecution.getStepName() + " (jobExecutionId=" + stepExecution.getJobExecutionId() + ")";
        if (writerTimingSummary != null)
        {
            writerTimingSummary.logSummaryAndReset(context);
        }
        if (failureSummary != null)
        {
            failureSummary.logSummaryAndReset(context);
        }
        return stepExecution.getExitStatus();
    }
}
