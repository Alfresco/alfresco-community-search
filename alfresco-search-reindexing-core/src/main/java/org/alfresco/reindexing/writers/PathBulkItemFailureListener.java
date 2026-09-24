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
 * Hook invoked by {@link ElasticsearchPathWriter} for each item that ran out of writer-level retries and still failed. Absent by default — with no listener bean registered the writer only logs a WARN and moves on. Consumers (e.g. batch-indexing's DLQ) register a bean of this type to be notified.
 * <p>
 * Kept as a separate type from {@link BulkItemFailureListener} on purpose: the two writers have different callers, different stages (batch-indexing classifies metadata bulk writes as {@code METADATA_WRITER} and path bulk writes as {@code PATH_WRITER} in the DLQ), and are wired independently. One interface per call site keeps the Spring wiring unambiguous without needing qualifiers.
 */
@FunctionalInterface
public interface PathBulkItemFailureListener
{
    /**
     * @param dbId
     *            source node database id; may be {@code null} if the writer couldn't correlate the failed bulk op back to a chunk item
     * @param nodeRef
     *            Alfresco node reference (never {@code null} — this is the ES doc id from the bulk response)
     * @param sourceCommitTimeMs
     *            best-effort event/commit time in epoch ms; may be {@code null}
     * @param errorType
     *            short classifier from the ES error (e.g. {@code strict_dynamic_mapping_exception}); may be {@code null}
     * @param errorReason
     *            human-readable reason from ES; may be {@code null}
     */
    void onPathBulkItemFailure(Long dbId, String nodeRef, Long sourceCommitTimeMs, String errorType, String errorReason);
}
