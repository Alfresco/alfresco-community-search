/*-
 * #%L
 * Alfresco Search Community :: OpenSearch Client
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
package org.alfresco.indexing.shared.model;

/**
 * Per-item failure entry carried inside {@link BulkRequestDto} so writers can surface item-level errors to observability hooks (DLQ, metrics) without leaking OpenSearch types up the stack.
 *
 * @param documentId
 *            the ES document id used in the bulk operation (for the reindexing path this is the Alfresco nodeRef)
 * @param errorType
 *            short classifier from ES (e.g. {@code mapper_parsing_exception}); may be {@code null} if the response didn't include one
 * @param errorReason
 *            human-readable reason from ES; may be {@code null}
 */
public record BulkItemFailure(String documentId, String errorType, String errorReason)
{}
