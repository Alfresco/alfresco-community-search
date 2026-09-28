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

import java.util.Objects;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.jspecify.annotations.NonNull;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.retry.RetryException;
import org.springframework.core.retry.RetryListener;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.core.retry.Retryable;
import org.springframework.util.backoff.BackOff;

/**
 * Creates retry proxies that wrap every method call on a target object with a {@link RetryTemplate}.
 * <p>
 * On each retry the underlying call is re-invoked, giving transient-failure–prone layers (e.g. {@code MetadataRepositoryTransactionDecorator}) a chance to obtain a fresh connection from the pool.
 */
@Slf4j
@UtilityClass
public class RetryProxyFactory
{
    public static <T> T createRetryProxy(T delegate, Class<T> targetType, BackOff backOff)
    {
        return createRetryProxy(delegate, targetType, backOff, new LoggingRetryListener());
    }

    public static <T> T createRetryProxy(T delegate, Class<T> targetType, BackOff backOff, RetryListener retryListener)
    {
        Objects.requireNonNull(delegate, "delegate must not be null to create retry proxy");
        Objects.requireNonNull(targetType, "targetType must not be null to create retry proxy");
        Objects.requireNonNull(backOff, "backOff must not be null to create retry proxy");
        Objects.requireNonNull(retryListener, "retryListener must not be null to create retry proxy");

        String targetName = targetType.getSimpleName();

        log.info("Wrapping {} with retry proxy and: {}", targetName, backOff);

        RetryPolicy retryPolicy = RetryPolicy.builder()
                .backOff(backOff)
                .includes(Exception.class)
                .build();

        RetryTemplate retryTemplate = new RetryTemplate(retryPolicy);
        retryTemplate.setRetryListener(retryListener);

        ProxyFactory proxyFactory = new ProxyFactory(delegate);
        proxyFactory.addAdvice(createMethodInterceptor(retryTemplate, targetName));

        @SuppressWarnings("unchecked")
        T proxy = (T) proxyFactory.getProxy();
        return proxy;
    }

    private <T> @NonNull MethodInterceptor createMethodInterceptor(RetryTemplate retryTemplate, String targetName)
    {
        return invocation -> {
            Retryable<T> retryable = createRetryable(invocation, targetName);
            try
            {
                return retryTemplate.execute(retryable);
            }
            catch (RetryException e)
            {
                log.error("[{}] Stopped retrying after retryCount: {} with message: {}. Throwing last exception", targetName, e.getRetryCount(), e.getMessage());
                throw e.getLastException();
            }
        };
    }

    private <T> @NonNull Retryable<T> createRetryable(MethodInvocation invocation, String retryableName)
    {
        return new Retryable<>() {
            @SuppressWarnings("unchecked")
            @Override
            public T execute() throws Throwable
            {
                return (T) invocation.proceed();
            }

            @NonNull @Override
            public String getName()
            {
                return retryableName;
            }
        };
    }
}
