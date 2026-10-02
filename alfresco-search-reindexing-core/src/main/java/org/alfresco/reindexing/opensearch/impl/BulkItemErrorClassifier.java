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
package org.alfresco.reindexing.opensearch.impl;

import java.util.Set;

import org.opensearch.client.opensearch._types.ErrorCause;
import org.opensearch.client.opensearch.core.bulk.BulkResponseItem;

/**
 * Classifies a single per-item bulk error as either retryable (worth another round) or terminal (permanent — send straight to the DLQ). Used by both metadata and path bulk writers to decide which failures deserve another round of the writer retry loop.
 * <p>
 * Polarity is "retry unless known permanent". Retry-on-unknown matches the writer's historical behaviour and keeps races like {@code document_missing_exception} (path writer racing metadata-writer creation) recoverable. Only errors that would fail identically on every retry are terminal, so permanent failures don't burn the round budget.
 * <p>
 * Terminal error types:
 * <ul>
 * <li>{@code mapper_parsing_exception} — value doesn't fit the mapped type.</li>
 * <li>{@code document_parsing_exception} — ES 8.x parse failure for a field value that doesn't fit the mapped type (the modern equivalent of {@code mapper_parsing_exception}).</li>
 * <li>{@code document_mapping_exception} — ES 8.x mapping failure raised while parsing a document.</li>
 * <li>{@code strict_dynamic_mapping_exception} — new field on a strict-mapping index.</li>
 * <li>{@code illegal_argument_exception} — script argument or query shape bug.</li>
 * </ul>
 * Everything else — including 429/503, network transients, {@code document_missing_exception}, {@code version_conflict_engine_exception}, and unknown types — is retryable.
 */
public final class BulkItemErrorClassifier
{
    /** Error type names that will never succeed on retry. Anything not in this set is retryable. */
    private static final Set<String> TERMINAL_TYPES = Set.of(
            "mapper_parsing_exception",
            "document_parsing_exception",
            "document_mapping_exception",
            "strict_dynamic_mapping_exception",
            "illegal_argument_exception");

    private BulkItemErrorClassifier()
    {}

    /**
     * Return whether the given failed bulk-response item should be retried. Assumes the caller has already established that {@code item.error() != null}.
     */
    public static boolean isRetryable(BulkResponseItem item)
    {
        ErrorCause error = item.error();
        if (error == null || error.type() == null)
        {
            return true;
        }
        return !TERMINAL_TYPES.contains(error.type());
    }
}
