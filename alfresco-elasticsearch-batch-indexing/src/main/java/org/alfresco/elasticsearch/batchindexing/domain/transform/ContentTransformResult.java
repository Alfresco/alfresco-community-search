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
package org.alfresco.elasticsearch.batchindexing.domain.transform;

import java.util.Optional;

/**
 * Outcome of a single {@link ContentTransformClient#transformContent(Long)} call. Three shapes:
 * <ul>
 * <li>{@link #ofText(String)} — 200 OK with a non-empty body; index it.</li>
 * <li>{@link #empty()} — 200 OK with an empty body. Documented as "no content" (not a failure); writer moves on and no DLQ entry is produced.</li>
 * <li>{@link #ofFailure(ContentTransformFailure)} — non-retryable HTTP error (e.g. 4xx). Writer skips the item and forwards the failure to the optional DLQ bridge.</li>
 * </ul>
 * Transient 5xx / timeout retries are handled inside the client; they only surface here as an empty result once retries are exhausted, matching the pre-existing "best-effort secondary indexing" contract.
 */
public record ContentTransformResult(Optional<String> text, Optional<ContentTransformFailure> failure)
{
    public static ContentTransformResult ofText(String text)
    {
        return new ContentTransformResult(Optional.of(text), Optional.empty());
    }

    public static ContentTransformResult empty()
    {
        return new ContentTransformResult(Optional.empty(), Optional.empty());
    }

    public static ContentTransformResult ofFailure(ContentTransformFailure failure)
    {
        return new ContentTransformResult(Optional.empty(), Optional.of(failure));
    }
}
