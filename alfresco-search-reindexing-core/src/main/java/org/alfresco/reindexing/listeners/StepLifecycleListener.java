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

import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.REINDEXING_START_TIME;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.springframework.batch.core.listener.StepListenerSupport;
import org.springframework.batch.core.step.StepExecution;

public class StepLifecycleListener extends StepListenerSupport
{

    @Override
    public void beforeStep(StepExecution stepExecution)
    {
        // The indexing start time will be used to avoid updating with PATH information documents updated by the live indexing.
        LocalDateTime ldt = stepExecution.getJobExecution().getStartTime();
        ZonedDateTime zdt = ldt.atZone(ZoneId.systemDefault());
        long startTimeToEpochMillis = zdt.toInstant().toEpochMilli();

        stepExecution.getExecutionContext().putLong(REINDEXING_START_TIME, startTimeToEpochMillis);
    }

}
