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
 * Hook invoked when a node's own type is in a namespace with no prefix mapping, so the node cannot be indexed at all (ACS-12851).
 * <p>
 * Absent by default â€” with no listener bean registered the processor only reports the loss. Consumers (batch-indexing's DLQ) register a bean of this type so the node can be recovered once the mapping is fixed.
 * <p>
 * Fires without failing the item, so it is independent of Spring Batch's skip machinery: a node lost this way is recorded even though the step completes.
 */
@FunctionalInterface
public interface UnresolvedNamespaceListener
{
    /**
     * @param dbId
     *            source node database id
     * @param nodeRef
     *            Alfresco node reference; may be {@code null} for a node the reader could not fully map
     * @param sourceCommitTimeMs
     *            node commit time in epoch ms; may be {@code null}
     * @param namespaceUri
     *            the URI that has no prefix mapping
     * @param localName
     *            local name of the type that could not be resolved
     */
    void onUnresolvedNamespace(Long dbId, String nodeRef, Long sourceCommitTimeMs, String namespaceUri, String localName);
}
