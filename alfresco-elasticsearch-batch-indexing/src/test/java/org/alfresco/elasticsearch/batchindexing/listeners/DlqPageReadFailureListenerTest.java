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
package org.alfresco.elasticsearch.batchindexing.listeners;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.alfresco.database.connector.NodeParams;
import org.alfresco.elasticsearch.batchindexing.DlqWriter;
import org.alfresco.elasticsearch.batchindexing.model.DeadLetterDocument.FailureStage;

@ExtendWith(MockitoExtension.class)
class DlqPageReadFailureListenerTest
{
    private static final long TS_FROM = 1_700_000_000_000L;
    private static final long TS_TO = 1_700_000_060_000L;

    @Mock
    private DlqWriter dlqWriter;

    @InjectMocks
    private DlqPageReadFailureListener listener;

    @Test
    void forwardsTimestampRangeToRecordWindowFailure()
    {
        RuntimeException cause = new RuntimeException("bad row");
        NodeParams params = NodeParams.searchByTimestampRange(TS_FROM, TS_TO);

        listener.onPageReadFailure("reindexingByDateReader", params, cause);

        verify(dlqWriter).recordWindowFailure(TS_FROM, TS_TO, cause);
    }

    @Test
    void skipsWhenParamsHaveNoTimestampRange()
    {
        // The by-id CLI reader has no timestamp range — we log and skip rather than
        // manufacture a bogus window key.
        NodeParams params = NodeParams.searchByIdRange(1L, 100L);

        listener.onPageReadFailure("reindexingByIdReader", params, new RuntimeException("bad row"));

        verify(dlqWriter, never()).recordWindowFailure(anyLong(), anyLong(), any());
    }

    @Test
    void recordsOneSkipPerIdWhenPageIsIdShaped()
    {
        // One record per id so the drain's cleanup sees the failureCount rise on the very
        // entries it is about to evaluate.
        RuntimeException cause = new RuntimeException("connection reset");
        NodeParams params = NodeParams.searchByIds(Set.of(42L, 99L));

        listener.onPageReadFailure("reindexDlqReader", params, cause);

        verify(dlqWriter).recordSkip(42L, null, null, FailureStage.READER, cause);
        verify(dlqWriter).recordSkip(99L, null, null, FailureStage.READER, cause);
        verify(dlqWriter, never()).recordWindowFailure(anyLong(), anyLong(), any());
    }

    private static long anyLong()
    {
        return org.mockito.ArgumentMatchers.anyLong();
    }

    private static Throwable any()
    {
        return org.mockito.ArgumentMatchers.any(Throwable.class);
    }
}
