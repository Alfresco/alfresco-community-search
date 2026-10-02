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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.alfresco.database.connector.NodeParams;
import org.alfresco.elasticsearch.batchindexing.DlqReader.DlqEntry;
import org.alfresco.elasticsearch.batchindexing.config.DlqRecoveryProperties;
import org.alfresco.elasticsearch.batchindexing.listeners.DlqPageReadFailureListener;
import org.alfresco.elasticsearch.batchindexing.model.DeadLetterDocument;

@ExtendWith(MockitoExtension.class)
@DisplayName("DlqDrainService")
class DlqDrainServiceTest
{
    @Mock
    private DlqReader dlqReader;

    @Mock
    private DlqWriter dlqWriter;

    private DlqRecoveryProperties properties;
    private DlqDrainService service;

    @BeforeEach
    void setUp()
    {
        properties = new DlqRecoveryProperties();
        service = new DlqDrainService(dlqReader, dlqWriter, properties);
    }

    private static DeadLetterDocument nodeLevel(long dbId, int failureCount)
    {
        DeadLetterDocument doc = new DeadLetterDocument();
        doc.setDbId(dbId);
        doc.setFailureCount(failureCount);
        return doc;
    }

    /** Runs a scan returning the given entries, populating the service's target ids and failureCount snapshot. */
    private void seedScan(DlqEntry... entries)
    {
        when(dlqReader.scanEntries(10_000)).thenReturn(List.of(entries));
        service.scan();
    }

    @Nested
    @DisplayName("scan")
    class Scan
    {
        @Test
        @DisplayName("exposes the scanned node-level dbIds as the drain targets")
        void snapshotsTargetIds()
        {
            when(dlqReader.scanEntries(10_000)).thenReturn(List.of(
                    new DlqEntry("42", nodeLevel(42L, 1)),
                    new DlqEntry("99", nodeLevel(99L, 3))));

            service.scan();

            assertThat(service.getTargetDbIds()).containsExactlyInAnyOrder(42L, 99L);
        }

        @Test
        @DisplayName("reports window-level presence via countWindowLevelEntries without draining them")
        void reportsWindowLevelSeparately()
        {
            when(dlqReader.scanEntries(10_000)).thenReturn(List.of(new DlqEntry("42", nodeLevel(42L, 1))));
            when(dlqReader.countWindowLevelEntries()).thenReturn(3L);

            service.scan();

            assertThat(service.getTargetDbIds()).containsExactly(42L);
            verify(dlqReader).countWindowLevelEntries();
        }

        @Test
        @DisplayName("empty scan yields no targets")
        void emptyScanYieldsNoTargets()
        {
            when(dlqReader.scanEntries(10_000)).thenReturn(List.of());

            service.scan();

            assertThat(service.getTargetDbIds()).isEmpty();
        }
    }

    @Nested
    @DisplayName("cleanup")
    class Cleanup
    {
        @Test
        @DisplayName("deletes an entry whose failureCount is unchanged (reindexed cleanly)")
        void deletesUnchangedEntry()
        {
            seedScan(new DlqEntry("42", nodeLevel(42L, 1)));
            when(dlqReader.readByIds(any())).thenReturn(Map.of("42", nodeLevel(42L, 1)));

            assertThat(service.cleanup()).isEqualTo(1);
            verify(dlqWriter).delete("42");
        }

        @Test
        @DisplayName("deletes an entry that no longer exists in the DLQ")
        void deletesMissingEntry()
        {
            seedScan(new DlqEntry("42", nodeLevel(42L, 1)));
            when(dlqReader.readByIds(any())).thenReturn(Map.of());

            assertThat(service.cleanup()).isEqualTo(1);
            verify(dlqWriter).delete("42");
        }

        @Test
        @DisplayName("leaves an entry whose failureCount increased (failed again during the run)")
        void leavesStillFailingEntry()
        {
            seedScan(new DlqEntry("42", nodeLevel(42L, 1)));
            when(dlqReader.readByIds(any())).thenReturn(Map.of("42", nodeLevel(42L, 2)));

            assertThat(service.cleanup()).isZero();
            verify(dlqWriter, never()).delete(any());
        }

        @Test
        @DisplayName("deleteOnSuccess=false leaves every drained entry in place")
        void deleteOnSuccessFalseSkipsCleanup()
        {
            properties.setDeleteOnSuccess(false);
            seedScan(new DlqEntry("42", nodeLevel(42L, 1)));

            assertThat(service.cleanup()).isZero();
            verify(dlqReader, never()).readByIds(any());
            verify(dlqWriter, never()).delete(any());
        }

        @Test
        @DisplayName("no-op (no lookup) when nothing was scanned")
        void noOpWhenNothingScanned()
        {
            assertThat(service.cleanup()).isZero();
            verify(dlqReader, never()).readByIds(any());
        }
    }

    /**
     * The drain reads its targets back in id-shaped pages. A page read failure has to leave a trace on every id in that page, or cleanup reads the untouched failureCount as "reindexed cleanly" and deletes entries for nodes that were never touched.
     */
    @Nested
    @DisplayName("id-shaped page read failure during the drain")
    class IdPageReadFailure
    {
        @Test
        @DisplayName("entries in the failed page survive cleanup; the rest are still cleared")
        void keepsEntriesWhosePageReadFailed()
        {
            seedScan(new DlqEntry("42", nodeLevel(42L, 1)),
                    new DlqEntry("99", nodeLevel(99L, 1)),
                    new DlqEntry("7", nodeLevel(7L, 1)));

            // Stands in for the real DLQ index: recordSkip bumps the stored count for "<dbId>".
            Map<String, Integer> failureCounts = new HashMap<>(Map.of("42", 1, "99", 1, "7", 1));
            doAnswer(invocation -> failureCounts.merge(
                    String.valueOf((Long) invocation.getArgument(0)), 1, Integer::sum))
                            .when(dlqWriter).recordSkip(any(), any(), any(), any(), any(Throwable.class));
            when(dlqReader.readByIds(any())).thenAnswer(invocation -> failureCounts.entrySet().stream()
                    .collect(Collectors.toMap(Map.Entry::getKey,
                            e -> nodeLevel(Long.parseLong(e.getKey()), e.getValue()))));

            new DlqPageReadFailureListener(dlqWriter).onPageReadFailure(
                    "reindexDlqReader",
                    NodeParams.searchByIds(Set.of(42L, 99L)),
                    new RuntimeException("connection reset"));

            assertThat(service.cleanup()).isEqualTo(1);
            verify(dlqWriter).delete("7");
            verify(dlqWriter, never()).delete("42");
            verify(dlqWriter, never()).delete("99");
        }
    }
}
