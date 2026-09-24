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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * @author msah
 */
@Getter
@Setter
@ToString
@EqualsAndHashCode
@NoArgsConstructor
public class BulkRequestDto
{
    int numberOfActions;
    /** Items that still failed on the final attempt. Matches {@link #failures}.size() for writers that expose per-item failures; a headline counter for writers that don't. */
    long failuresCount;
    /** Items that failed on the first attempt (i.e. needed at least one retry). Same as {@link #failuresCount} for writers that don't retry internally. Used by the path writer's counter log so operators can tell retry activity from ultimate failure. */
    long initialFailuresCount;
    /** Per-item failure details for observability hooks (DLQ, metrics). Empty by default; populated when the bulk response carries item-level errors on the final attempt. */
    List<BulkItemFailure> failures = new ArrayList<>();

    /**
     * Fluent add — preserves the existing {@link Setter} contract while making the populate side one call.
     */
    public void addFailure(BulkItemFailure failure)
    {
        if (failures == null)
        {
            failures = new ArrayList<>();
        }
        failures.add(failure);
    }

    /** Unmodifiable view for readers so listeners can't mutate the writer's state. */
    public List<BulkItemFailure> getFailures()
    {
        return failures == null ? List.of() : Collections.unmodifiableList(failures);
    }
}
