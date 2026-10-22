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
package org.alfresco.reindexing.readers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import org.alfresco.database.connector.AlfrescoMetadataRepository;
import org.alfresco.database.connector.NodeParams;

@ExtendWith(MockitoExtension.class)
class AlfrescoPagingItemReaderReadFailureTest
{
    private static final long TS_FROM = 1_000L;
    private static final long TS_TO = 2_000L;

    @Mock
    private AlfrescoMetadataRepository metadataRepository;

    @Mock
    private PageReadFailureListener listener;

    @Test
    void pageFailurePropagatesWhenNoListener()
    {
        DataAccessResourceFailureException boom = new DataAccessResourceFailureException("bad row");
        when(metadataRepository.getAlfrescoNodes(any())).thenThrow(boom);

        AlfrescoPagingItemReader reader = new AlfrescoPagingItemReader(
                metadataRepository, NodeParams.searchByTimestampRange(TS_FROM, TS_TO));
        reader.setPageSize(10);
        reader.setName("test-reader");

        assertThatThrownBy(reader::read).isSameAs(boom);
        verify(metadataRepository, times(1)).getAlfrescoNodes(any());
    }

    @Test
    void pageFailureIsSwallowedAndListenerNotifiedWhenListenerPresent() throws Exception
    {
        DataAccessResourceFailureException boom = new DataAccessResourceFailureException("bad row");
        when(metadataRepository.getAlfrescoNodes(any())).thenThrow(boom);

        AlfrescoPagingItemReader reader = new AlfrescoPagingItemReader(
                metadataRepository,
                NodeParams.searchByTimestampRange(TS_FROM, TS_TO),
                listener);
        reader.setPageSize(10);
        reader.setName("test-reader");

        assertThat(reader.read()).isNull();
        verify(listener, times(1)).onPageReadFailure(eq("test-reader"), any(NodeParams.class), eq(boom));
    }

    @Test
    void subsequentReadsDoNotReQueryAfterPageFailure() throws Exception
    {
        DataAccessResourceFailureException boom = new DataAccessResourceFailureException("bad row");
        when(metadataRepository.getAlfrescoNodes(any())).thenThrow(boom);

        AlfrescoPagingItemReader reader = new AlfrescoPagingItemReader(
                metadataRepository,
                NodeParams.searchByTimestampRange(TS_FROM, TS_TO),
                listener);
        reader.setPageSize(10);
        reader.setName("test-reader");

        assertThat(reader.read()).isNull();
        assertThat(reader.read()).isNull();
        assertThat(reader.read()).isNull();

        verify(metadataRepository, times(1)).getAlfrescoNodes(any());
        verify(listener, times(1)).onPageReadFailure(any(), any(), any());
    }

    @Test
    void listenerFailureShouldNotMaskOriginalPageFailure()
    {
        DataAccessResourceFailureException boom = new DataAccessResourceFailureException("bad row");
        when(metadataRepository.getAlfrescoNodes(any())).thenThrow(boom);
        // If the listener itself blows up, the reader propagates that — a bug in the DLQ bridge
        // shouldn't be swallowed silently.
        RuntimeException listenerFailure = new RuntimeException("listener down");
        doThrow(listenerFailure).when(listener).onPageReadFailure(any(), any(), any());

        AlfrescoPagingItemReader reader = new AlfrescoPagingItemReader(
                metadataRepository,
                NodeParams.searchByTimestampRange(TS_FROM, TS_TO),
                listener);
        reader.setPageSize(10);
        reader.setName("test-reader");

        assertThatThrownBy(reader::read).isSameAs(listenerFailure);
        verify(metadataRepository, times(1)).getAlfrescoNodes(any());
    }

    @Test
    void successfulReadIsUnaffectedByListenerPresence() throws Exception
    {
        when(metadataRepository.getAlfrescoNodes(any())).thenReturn(List.of());

        AlfrescoPagingItemReader reader = new AlfrescoPagingItemReader(
                metadataRepository,
                NodeParams.searchByTimestampRange(TS_FROM, TS_TO),
                listener);
        reader.setPageSize(10);
        reader.setName("test-reader");

        assertThat(reader.read()).isNull();
        verify(listener, never()).onPageReadFailure(any(), any(), any());
    }
}
