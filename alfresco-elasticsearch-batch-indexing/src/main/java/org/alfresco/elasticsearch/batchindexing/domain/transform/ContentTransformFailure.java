/*-
 * #%L
 * Alfresco Elasticsearch Connector :: Batch Indexing Module
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

/**
 * Structured, non-retryable content-transform failure surfaced by {@link ContentTransformClient} so the writer can hand it to the DLQ bridge without re-inspecting the underlying HTTP exception.
 *
 * @param failureType
 *            short classifier suitable for the DLQ {@code failureType} field (e.g. {@code TRANSFORM_HTTP_400}); never null
 * @param failureReason
 *            human-readable reason (typically the response body or exception message); may be null when the HTTP layer didn't give one
 */
public record ContentTransformFailure(String failureType, String failureReason)
{}
