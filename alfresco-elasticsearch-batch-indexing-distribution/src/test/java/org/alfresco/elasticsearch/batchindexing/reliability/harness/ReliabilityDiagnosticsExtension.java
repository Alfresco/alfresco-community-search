/*-
 * #%L
 * Alfresco Elasticsearch Connector :: Batch Indexing Distribution
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
package org.alfresco.elasticsearch.batchindexing.reliability.harness;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestWatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dumps batch-indexing container state on any reliability-IT failure so tests do not each need a try/catch around {@code await(...)}. Attached to {@link BaseReliabilityIT} — every subclass inherits it automatically.
 *
 * <p>
 * The extension locates the test's {@link ReliabilityStack} by reflection: walk the test-class hierarchy, pick the first static field of type {@code ReliabilityStack} that holds a non-null value. This keeps each concrete IT free to declare its own {@code private static ReliabilityStack stack;}.
 *
 * <p>
 * If no stack is found (for example the failure happened before {@code @BeforeAll} assigned it), the extension logs a warning and returns without rethrowing — it must never mask the original test failure.
 */
public class ReliabilityDiagnosticsExtension implements TestWatcher
{
    private static final Logger LOG = LoggerFactory.getLogger(ReliabilityDiagnosticsExtension.class);
    private static final int LOG_TAIL_LINES = 120;

    @Override
    public void testFailed(ExtensionContext context, Throwable cause)
    {
        ReliabilityStack stack = findStack(context.getRequiredTestClass());
        if (stack == null)
        {
            LOG.warn("[diagnostics] {} failed but no ReliabilityStack was discovered on {}; skipping container dump.",
                    context.getDisplayName(), context.getRequiredTestClass().getName());
            return;
        }

        String runningStatus;
        try
        {
            runningStatus = String.valueOf(stack.serviceControl().isRunning(ReliabilityStack.BATCH_INDEXING_SERVICE));
        }
        catch (Exception e)
        {
            runningStatus = "unknown (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")";
        }

        String logTail;
        try
        {
            logTail = stack.serviceControl().readContainerLogsTail(ReliabilityStack.BATCH_INDEXING_SERVICE, LOG_TAIL_LINES);
        }
        catch (Exception e)
        {
            logTail = "log tail unavailable: " + e;
        }

        LOG.error("[diagnostics] {} failed with {}. batch-indexing running: {}. Last {} log lines:\n{}",
                context.getDisplayName(),
                cause.getClass().getSimpleName(),
                runningStatus,
                LOG_TAIL_LINES,
                logTail);
    }

    @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
    private static ReliabilityStack findStack(Class<?> testClass)
    {
        for (Class<?> c = testClass; c != null && c != Object.class; c = c.getSuperclass())
        {
            for (Field f : c.getDeclaredFields())
            {
                if (!Modifier.isStatic(f.getModifiers()))
                {
                    continue;
                }
                if (!ReliabilityStack.class.isAssignableFrom(f.getType()))
                {
                    continue;
                }
                try
                {
                    f.setAccessible(true);
                    Object value = f.get(null);
                    if (value != null)
                    {
                        return (ReliabilityStack) value;
                    }
                }
                catch (Exception ignored)
                {
                    // Broad catch on purpose: setAccessible / Field#get can throw
                    // unchecked JPMS / SecurityManager / IAE that don't extend
                    // ReflectiveOperationException. Diagnostics must never mask
                    // the real test failure.
                }
            }
        }
        return null;
    }
}
