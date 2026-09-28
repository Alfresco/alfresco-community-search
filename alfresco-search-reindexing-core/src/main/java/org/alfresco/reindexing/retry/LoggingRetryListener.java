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

import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.core.retry.RetryListener;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryState;
import org.springframework.core.retry.Retryable;

import org.alfresco.metrics.ReindexingMetrics;

@Slf4j
@NoArgsConstructor
@AllArgsConstructor
public class LoggingRetryListener implements RetryListener
{
    @Nullable private String tag;

    @Nullable private ReindexingMetrics metrics;

    @Override
    public void beforeRetry(@NonNull RetryPolicy retryPolicy, @NonNull Retryable<?> retryable, RetryState retryState)
    {
        if (metrics != null)
        {
            metrics.reindexRetry();
        }
    }

    @Override
    public void onRetryableExecution(@NonNull RetryPolicy retryPolicy, @NonNull Retryable<?> retryable, RetryState retryState)
    {
        if (retryState.isSuccessful())
        {
            return;
        }
        Throwable lastException = retryState.getLastException();
        String tag = this.tag != null ? this.tag : retryable.getName();
        log.warn("[{}] Retry count: {}.", tag, retryState.getRetryCount(), lastException);
    }
}
