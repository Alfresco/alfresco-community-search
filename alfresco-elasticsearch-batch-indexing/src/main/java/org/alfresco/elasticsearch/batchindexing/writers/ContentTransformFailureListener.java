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
package org.alfresco.elasticsearch.batchindexing.writers;

/**
 * Hook invoked by {@link ContentTransformDirectWriter} when a per-node content transform fails in a non-retryable way (currently 4xx from ACS). Kept optional via {@code ObjectProvider} — when no bean is registered the writer only logs a WARN and moves on, matching the "best-effort secondary indexing" contract from {@code ContentTransformDirectWriter}'s JavaDoc.
 */
@FunctionalInterface
public interface ContentTransformFailureListener
{
    /**
     * @param dbId
     *            source node database id (never {@code null} — the writer only calls this after a per-node lookup)
     * @param nodeRef
     *            Alfresco node reference (never {@code null})
     * @param sourceCommitTimeMs
     *            best-effort event/commit time in epoch ms; may be {@code null}
     * @param failureType
     *            short classifier (e.g. {@code TRANSFORM_HTTP_400}); never {@code null}
     * @param failureReason
     *            human-readable reason from the transform layer; may be {@code null}
     */
    void onContentTransformFailure(Long dbId, String nodeRef, Long sourceCommitTimeMs, String failureType, String failureReason);
}
