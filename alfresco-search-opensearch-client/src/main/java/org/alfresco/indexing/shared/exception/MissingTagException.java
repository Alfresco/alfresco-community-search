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
package org.alfresco.indexing.shared.exception;

import lombok.Getter;

/**
 * Thrown when a tag referenced by a node's {@code cm:taggable} property cannot be resolved to a tag name.
 * <p>
 * This is a data problem, not an Elasticsearch one, and it is deterministic: retrying the same node will fail the same way until the data changes, so callers should not retry it with backoff (see {@code StepConfigurer}).
 */
@Getter
public class MissingTagException extends RuntimeException
{
    private final String tagId;

    public MissingTagException(String tagId)
    {
        super("Failed to find tag name for id: %s".formatted(tagId));
        this.tagId = tagId;
    }
}
