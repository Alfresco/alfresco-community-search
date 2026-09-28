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
package org.alfresco.reindexing.retry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.FixedBackOff;

import org.alfresco.database.connector.AlfrescoMetadataRepository;
import org.alfresco.database.connector.NodeParams;
import org.alfresco.database.connector.model.AlfrescoNode;

class RetryProxyFactoryTest
{
    /**
     * When the delegate does not throw, the proxy should pass calls through transparently.
     */
    @Test
    void shouldDelegateCallsWhenNoFailureOccurs()
    {
        AlfrescoMetadataRepository delegate = mock(AlfrescoMetadataRepository.class);
        AlfrescoNode node = new AlfrescoNode();
        node.setId(42L);
        when(delegate.getAlfrescoNodes(any())).thenReturn(List.of(node));

        AlfrescoMetadataRepository proxy = RetryProxyFactory.createRetryProxy(delegate, AlfrescoMetadataRepository.class, minimalBackOff(3));

        List<AlfrescoNode> result = proxy.getAlfrescoNodes(NodeParams.searchByIdRange(1, 100));
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo(42L);
        verify(delegate, times(1)).getAlfrescoNodes(any());
    }

    /**
     * When transient failures occur but the retry count is sufficient to cover them, the proxy should eventually succeed.
     */
    @Test
    void shouldRetryAndSucceedWhenFailureCountIsWithinRetryLimit()
    {
        int failuresBeforeSuccess = 5;
        int maxRetries = failuresBeforeSuccess + 1;

        AlfrescoMetadataRepository delegate = mock(AlfrescoMetadataRepository.class);
        AtomicInteger callCount = new AtomicInteger();

        AlfrescoNode expectedNode = new AlfrescoNode();
        expectedNode.setId(99L);

        when(delegate.getAlfrescoNodes(any())).thenAnswer(invocation -> {
            if (callCount.incrementAndGet() <= failuresBeforeSuccess)
            {
                throw new CannotCreateTransactionException("Transient failure #" + callCount.get());
            }
            return List.of(expectedNode);
        });

        AlfrescoMetadataRepository proxy = RetryProxyFactory.createRetryProxy(delegate, AlfrescoMetadataRepository.class, minimalBackOff(maxRetries));

        List<AlfrescoNode> result = proxy.getAlfrescoNodes(NodeParams.searchByIdRange(1, 100));
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo(99L);
        verify(delegate, times(failuresBeforeSuccess + 1)).getAlfrescoNodes(any());
    }

    /**
     * When retries are exhausted, the proxy should re-throw the last exception.
     */
    @Test
    void shouldThrowWhenRetryAttemptsAreExhausted()
    {
        int maxRetries = 3;

        AlfrescoMetadataRepository delegate = mock(AlfrescoMetadataRepository.class);
        when(delegate.getAlfrescoNodes(any())).thenThrow(new TransactionTimedOutException("Persistent failure"));

        AlfrescoMetadataRepository proxy = RetryProxyFactory.createRetryProxy(delegate, AlfrescoMetadataRepository.class, minimalBackOff(maxRetries));

        assertThatThrownBy(() -> proxy.getAlfrescoNodes(NodeParams.searchByIdRange(1, 100)))
                .isInstanceOf(TransactionTimedOutException.class)
                .hasMessage("Persistent failure");

        // 1 initial call + maxRetries retries
        verify(delegate, times(1 + maxRetries)).getAlfrescoNodes(any());
    }

    /**
     * Simulates multiple successful calls interspersed with transient failures — similar to the original FaultyReaderAnswer pattern where N failures occur between each success.
     */
    @Test
    void shouldHandleInterleavedFailuresAndSuccesses()
    {
        int failuresBetweenSuccesses = 4;
        int totalSuccessfulCalls = 10;
        int maxRetries = failuresBetweenSuccesses + 1;

        AlfrescoMetadataRepository delegate = mock(AlfrescoMetadataRepository.class);
        AtomicInteger callCount = new AtomicInteger();

        when(delegate.getAlfrescoNodes(any())).thenAnswer(invocation -> {
            int call = callCount.incrementAndGet();
            int positionInCycle = (call - 1) % (failuresBetweenSuccesses + 1);
            if (positionInCycle != 0)
            {
                throw new TransactionSystemException("Transient failure at call #" + call);
            }

            AlfrescoNode node = new AlfrescoNode();
            long successIndex = (call - 1) / (failuresBetweenSuccesses + 1);
            node.setId(successIndex);
            return List.of(node);
        });

        AlfrescoMetadataRepository proxy = RetryProxyFactory.createRetryProxy(delegate, AlfrescoMetadataRepository.class, minimalBackOff(maxRetries));

        for (int i = 0; i < totalSuccessfulCalls; i++)
        {
            List<AlfrescoNode> result = proxy.getAlfrescoNodes(NodeParams.searchByIdRange(1, 100));
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getId()).isEqualTo(i);
        }
    }

    /**
     * Verifies that the retry proxy works with different methods on the interface.
     */
    @Test
    void shouldRetryDifferentMethods()
    {
        AlfrescoMetadataRepository delegate = mock(AlfrescoMetadataRepository.class);
        AtomicInteger callCount = new AtomicInteger();

        when(delegate.getDBIdFromNodeRef(any())).thenAnswer(invocation -> {
            if (callCount.incrementAndGet() <= 2)
            {
                throw new RuntimeException("Transient failure");
            }
            return 123L;
        });

        AlfrescoMetadataRepository proxy = RetryProxyFactory.createRetryProxy(delegate, AlfrescoMetadataRepository.class, minimalBackOff(5));

        Long result = proxy.getDBIdFromNodeRef("workspace://SpacesStore/abc");
        assertThat(result).isEqualTo(123L);
        verify(delegate, times(3)).getDBIdFromNodeRef(any());
    }

    private static BackOff minimalBackOff(int maxAttempts)
    {
        return new FixedBackOff(1, maxAttempts);
    }
}
