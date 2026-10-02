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
package org.alfresco.reindexing.writers;

/**
 * Thrown by a reindex writer when per-item bulk failures remain after retries and no dead-letter listener is configured to capture them. Failing the chunk makes the reindex end {@code FAILED} instead of silently dropping the affected nodes. Gated by {@code alfresco.reindex.failOnTerminalWriteErrors}.
 */
public class TerminalWriteFailureException extends RuntimeException
{
    public TerminalWriteFailureException(String stage, long failuresCount)
    {
        super(failuresCount + " " + stage + " node(s) still failed the Elasticsearch bulk write after retries and "
                + "no dead-letter listener is configured to capture them. Failing the reindex so these failures are "
                + "not silently dropped (set alfresco.reindex.failOnTerminalWriteErrors=false to restore the previous "
                + "log-and-continue behaviour).");
    }
}
