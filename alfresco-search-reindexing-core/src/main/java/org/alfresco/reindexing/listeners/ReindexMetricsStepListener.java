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

import java.util.List;

import org.springframework.batch.core.listener.StepExecutionListener;
import org.springframework.batch.core.step.StepExecution;

import org.alfresco.metrics.ReindexingMetrics;

public class ReindexMetricsStepListener implements StepExecutionListener
{
    private final ReindexingMetrics metrics;
    private final List<String> enabledWriters;

    public ReindexMetricsStepListener(ReindexingMetrics metrics, List<String> enabledWriters)
    {
        this.metrics = metrics;
        this.enabledWriters = enabledWriters;
    }

    @Override
    public void beforeStep(StepExecution stepExecution)
    {
        metrics.setJobExecutionId(stepExecution.getJobExecutionId());
        metrics.preRegisterStepMetrics(enabledWriters);
    }
}
