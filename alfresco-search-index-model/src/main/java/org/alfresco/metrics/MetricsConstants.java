/*-
 * #%L
 * Alfresco Search Community :: Search Index Model
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
package org.alfresco.metrics;

public final class MetricsConstants
{

    public static final String REINDEX_READ_METRIC = "alfresco.reindex.read";
    public static final String REINDEX_FILTERED_METRIC = "alfresco.reindex.filtered";
    public static final String REINDEX_WRITE_METRIC = "alfresco.reindex.write";
    public static final String REINDEX_RETRIES_METRIC = "alfresco.reindex.retries";
    public static final String REINDEX_JOB_START_TIME = "alfresco.reindex.job.start.time";
    public static final String REINDEX_JOB_END_TIME = "alfresco.reindex.job.end.time";
    public static final String REINDEX_JOB_STATUS = "alfresco.reindex.job.status";
    public static final String REINDEX_CHUNK_WRITE_DURATION_METRIC = "alfresco.reindex.chunk.write.duration";
    public static final String UNRESOLVED_NAMESPACE_METRIC = "alfresco.reindex.namespace.unresolved";

    public static final String REINDEX_LAST_ACTIVITY_TIME = "alfresco.reindex.continuous.last.activity.timestamp";
    public static final String REINDEX_LAST_CYCLE_END_TIME = "alfresco.reindex.continuous.last.cycle.finished.timestamp";

    public static final String METADATA = "metadata";
    public static final String CONTENT = "content";
    public static final String PATH = "path";
    // Combined metadata+path write emitted as a single bulk (alfresco.reindex.writersMergedEnabled).
    public static final String MERGED = "merged";

    public static final String MODULE_TAG = "module";
    public static final String OUTCOME_TAG = "outcome";
    public static final String JOB_ID_TAG = "jobId";

    public static final String OUTCOME_SUCCESS = "success";
    public static final String OUTCOME_FAILURE = "failure";

    private MetricsConstants()
    {}
}
