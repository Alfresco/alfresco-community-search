/*-
 * #%L
 * Alfresco Search Community :: Batch Indexing
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
package org.alfresco.elasticsearch.batchindexing.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Typed watermark document persisted in Elasticsearch. The purpose of this document is to act as a store for the reindexing cursor and remove the need for on-disk database otherwise. Field semantics:
 * <ul>
 * <li><b>schemaVersion</b> — monotonically increasing integer; bump when the document structure changes so readers can migrate or reject incompatible documents.</li>
 * <li><b>lastSuccessful*</b> — the time range of the last run that completed successfully ({@code BatchStatus.COMPLETED}). These define the trusted anchor for gap recovery.</li>
 * <li><b>lastRun*</b> — the time range, status, and item counts of the most recent run regardless of outcome. Useful for diagnosing degraded runs without external logging.</li>
 * <li><b>updatedAt</b> — ISO-8601 timestamp of when this document was last written.</li>
 * </ul>
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class WatermarkDocument
{
    /** Monotonically increasing integer. Bump when the document structure changes so readers can migrate or reject incompatible documents. */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    @JsonProperty("schemaVersion")
    private int schemaVersion = CURRENT_SCHEMA_VERSION;

    /** Epoch millis of the last successful run's fromTime */
    @JsonProperty("lastSuccessfulFromTimeEpochMs")
    private long lastSuccessfulFromTimeEpochMs;

    /** Epoch millis of the last successful run's toTime — the trusted watermark anchor */
    @JsonProperty("lastSuccessfulToTimeEpochMs")
    private long lastSuccessfulToTimeEpochMs;

    /** Epoch millis of the most recent run's fromTime */
    @JsonProperty("lastRunFromTimeEpochMs")
    private long lastRunFromTimeEpochMs;

    /** Epoch millis of the most recent run's toTime */
    @JsonProperty("lastRunToTimeEpochMs")
    private long lastRunToTimeEpochMs;

    /** Batch status of the most recent run (e.g. COMPLETED, FAILED) */
    @JsonProperty("lastRunStatus")
    private String lastRunStatus;

    /** Total items read in the most recent run */
    @JsonProperty("lastRunReadCount")
    private long lastRunReadCount;

    /** Total items written in the most recent run */
    @JsonProperty("lastRunWriteCount")
    private long lastRunWriteCount;

    /** Total items skipped in the most recent run. Skipped items are items that were not processed due to acceptable exceptions. */
    @JsonProperty("lastRunSkipCount")
    private long lastRunSkipCount;

    /** Total items filtered out in the most recent run. Filtered items are items that didn't pass the filter criteria, like node type or node aspect. */
    @JsonProperty("lastRunFilterCount")
    private long lastRunFilterCount;

    /** ISO-8601 timestamp of when this document was last persisted */
    @JsonProperty("updatedAt")
    private String updatedAt;
}
