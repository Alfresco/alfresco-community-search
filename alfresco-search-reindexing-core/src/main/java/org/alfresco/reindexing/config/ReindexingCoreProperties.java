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
package org.alfresco.reindexing.config;

import java.time.LocalDateTime;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.format.annotation.DateTimeFormat;

@Setter
@Getter
@ConfigurationProperties(prefix = "alfresco.reindex")
public class ReindexingCoreProperties
{
    private String jobName;

    private int batchSize;

    private int pathCacheSize;

    private int pageSize;

    private int concurrentProcessors;

    private long fromId;

    private long toId;

    @DateTimeFormat(pattern = "yyyyMMddHHmm")
    private LocalDateTime fromTime;

    @DateTimeFormat(pattern = "yyyyMMddHHmm")
    private LocalDateTime toTime;

    protected boolean retryingEnabled;
    protected Integer retryingMaxCount;
    protected boolean multithreadedStepEnabled;

    private long retryingInitialDelay;
    private long retryingDelayIntervalMultiplier;
    private long retryingMaxDelay;
    private long writerRetryDelay;
    private int writerRetryCount;

    private int skipLimit;

    /** What to do with a node whose type namespace has no prefix mapping. Reporting and dead-lettering happen either way. */
    private UnresolvedNamespaceAction onUnresolvedNamespace = UnresolvedNamespaceAction.FILTER;

    private boolean metadataIndexingEnabled;
    private boolean contentIndexingEnabled;
    private boolean pathIndexingEnabled;

    @Override
    public String toString()
    {
        return "ReindexingCoreProperties{" +
                "jobName='" + jobName + '\'' +
                ", batchSize=" + batchSize +
                ", pageSize=" + pageSize +
                ", concurrentProcessors=" + concurrentProcessors +
                ", fromId=" + fromId +
                ", toId=" + toId +
                ", fromTime=" + fromTime +
                ", toTime=" + toTime +
                ", retryingEnabled=" + retryingEnabled +
                ", retryingMaxCount=" + retryingMaxCount +
                ", multithreadedStepEnabled=" + multithreadedStepEnabled +
                ", retryingInitialDelay=" + retryingInitialDelay +
                ", retryingDelayIntervalMultiplier=" + retryingDelayIntervalMultiplier +
                ", retryingMaxDelay=" + retryingMaxDelay +
                ", writerRetryDelay=" + writerRetryDelay +
                ", writerRetryCount=" + writerRetryCount +
                ", skipLimit=" + skipLimit +
                ", onUnresolvedNamespace=" + onUnresolvedNamespace +
                ", pathCacheSize=" + pathCacheSize +
                '}';
    }

}
