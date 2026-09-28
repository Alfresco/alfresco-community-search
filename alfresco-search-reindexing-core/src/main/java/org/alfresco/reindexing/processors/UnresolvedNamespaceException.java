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
package org.alfresco.reindexing.processors;

/**
 * A node's own type is in a namespace with no prefix mapping, so the node cannot be indexed (ACS-12851).
 * <p>
 * Raised when {@code alfresco.reindex.onUnresolvedNamespace} is {@code skip} or {@code stop}. Deterministic, so retrying cannot help â€” {@code StepConfigurer} excludes it from retry.
 */
public class UnresolvedNamespaceException extends RuntimeException
{
    private static final long serialVersionUID = 1L;

    private final boolean terminal;

    public UnresolvedNamespaceException(Long dbId, String namespaceUri, String localName, boolean terminal)
    {
        super("Node " + dbId + " will not be indexed: no prefix mapping for namespace " + namespaceUri
                + "; type " + localName + " cannot be indexed");
        this.terminal = terminal;
    }

    /**
     * {@code true} only under {@code stop}, where the application is expected to end rather than carry on to the next cycle.
     */
    public boolean isTerminal()
    {
        return terminal;
    }
}
