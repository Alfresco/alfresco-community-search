/*-
 * #%L
 * Alfresco Elasticsearch Connector :: Batch Indexing Module
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
package org.alfresco.elasticsearch.batchindexing;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;

import org.alfresco.elasticsearch.batchindexing.DlqReader.DlqEntry;
import org.alfresco.elasticsearch.batchindexing.config.DlqRecoveryProperties;
import org.alfresco.elasticsearch.batchindexing.model.DeadLetterDocument;

/**
 * Drives the {@code reindexDlq} drain: selecting drainable entries and clearing the ones that recovered. The batch job (in {@code OneShotReindexDlqConfig}) is just three tasklets around {@link #scan()}, {@link #getTargetDbIds()} and {@link #cleanup()}.
 * <p>
 * Holds the run's state (the target ids and their pre-run {@code failureCount} snapshot) in one immutable snapshot. The steps run in sequence, so {@link #scan()} is the only writer and no locking is needed; the volatile write publishes the snapshot to the later steps, which read it from other threads (the reindex step is multi-threaded).
 */
@Slf4j
public class DlqDrainService
{
    private final DlqReader dlqReader;
    private final DlqWriter dlqWriter;
    private final DlqRecoveryProperties properties;

    /** Single write per run, so the two halves can never be read out of step with each other. */
    private volatile ScanState scanState = ScanState.EMPTY;

    public DlqDrainService(DlqReader dlqReader, DlqWriter dlqWriter, DlqRecoveryProperties properties)
    {
        this.dlqReader = dlqReader;
        this.dlqWriter = dlqWriter;
        this.properties = properties;
    }

    /**
     * Scan step: select drainable (node-level) entries and snapshot each one's {@code failureCount} for the cleanup comparison. Window-level records can't be drained by id — {@code scanEntries} already excludes them; here we only count them for an operator warning.
     */
    public void scan()
    {
        List<DlqEntry> entries = dlqReader.scanEntries(properties.getMaxItems());

        Set<Long> dbIds = entries.stream()
                .map(e -> e.document().getDbId())
                .collect(Collectors.toUnmodifiableSet());
        Map<String, Integer> failureCountBefore = entries.stream()
                .collect(Collectors.toUnmodifiableMap(DlqEntry::documentId, e -> e.document().getFailureCount()));
        scanState = new ScanState(dbIds, failureCountBefore);

        long windowLevelCount = dlqReader.countWindowLevelEntries();
        if (windowLevelCount > 0)
        {
            log.warn("reindexDlq: {} window-level DLQ record(s) with no dbId are not drainable by id — run "
                    + "reindexByDate over the affected window(s) instead.", windowLevelCount);
        }

        log.info("reindexDlq: found {} node-level DLQ entries to drain ({} window-level not drainable)",
                dbIds.size(), windowLevelCount);
    }

    /** The node ids selected by the last {@link #scan()}, for the reindex step's reader. */
    public Set<Long> getTargetDbIds()
    {
        return scanState.targetDbIds();
    }

    /**
     * Cleanup step: delete each drained entry whose {@code failureCount} did not increase during the run (nothing re-recorded a failure, so the reindex succeeded); leave the ones that failed again. Looks the snapshotted ids up <em>by id</em> rather than re-scanning — the {@code failureCount} sort means a still-failing entry could sort out of a capped re-scan window and be mistaken for drained.
     *
     * @return the number of DLQ entries deleted
     */
    public int cleanup()
    {
        Map<String, Integer> before = scanState.failureCountBeforeByDocumentId();
        if (before.isEmpty())
        {
            return 0;
        }
        if (!properties.isDeleteOnSuccess())
        {
            log.info("reindexDlq: deleteOnSuccess=false — leaving all {} drained DLQ entries in place.", before.size());
            return 0;
        }

        Map<String, DeadLetterDocument> after = dlqReader.readByIds(before.keySet());

        int cleared = 0;
        for (Map.Entry<String, Integer> beforeEntry : before.entrySet())
        {
            String documentId = beforeEntry.getKey();
            int countBefore = beforeEntry.getValue();
            DeadLetterDocument current = after.get(documentId);
            boolean stillFailing = current != null && current.getFailureCount() > countBefore;
            if (!stillFailing)
            {
                dlqWriter.delete(documentId);
                cleared++;
            }
        }
        log.info("reindexDlq: cleared {}/{} drained DLQ entries; {} still failing and left in place.",
                cleared, before.size(), before.size() - cleared);
        return cleared;
    }

    /** What the scan step selected: the ids to reindex and their failure counts as they stood before the run. */
    private record ScanState(Set<Long> targetDbIds, Map<String, Integer> failureCountBeforeByDocumentId)
    {
        private static final ScanState EMPTY = new ScanState(Set.of(), Map.of());
    }
}
