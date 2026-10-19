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
package org.alfresco.indexing.shared.utils;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import org.alfresco.indexing.shared.EventGenerator;
import org.alfresco.repo.event.v1.model.EventData;
import org.alfresco.repo.event.v1.model.EventType;
import org.alfresco.repo.event.v1.model.NodeResource;
import org.alfresco.repo.event.v1.model.RepoEvent;

class DeletionTypeCheckerTest
{
    @Test
    void shouldDetectArchiveDeleteWhenFlagIsFalse()
    {
        var event = deleteEvent(Boolean.FALSE);

        assertThat(DeletionTypeChecker.isArchiveDelete(event)).isTrue();
    }

    @Test
    void shouldDetectPermanentDeleteWhenFlagIsTrue()
    {
        var event = deleteEvent(Boolean.TRUE);

        assertThat(DeletionTypeChecker.isArchiveDelete(event)).isFalse();
    }

    @Test
    void shouldTreatMissingFlagAsPermanentDelete()
    {
        var event = deleteEvent(null);

        assertThat(DeletionTypeChecker.isArchiveDelete(event)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = EventType.class, names = {"NODE_CREATED", "NODE_UPDATED", "PERMISSION_UPDATED"})
    void shouldNotClassifyNonDeleteEventsAsArchiveDelete(EventType eventType)
    {
        var event = EventGenerator.builder()
                .withType(eventType)
                .withMetadataSet(true)
                .withPermanentlyDeleted(Boolean.FALSE)
                .generateEvent();

        assertThat(DeletionTypeChecker.isArchiveDelete(event)).isFalse();
    }

    private RepoEvent<EventData<NodeResource>> deleteEvent(Boolean isPermanentlyDeleted)
    {
        return EventGenerator.builder()
                .withType(EventType.NODE_DELETED)
                .withMetadataSet(true)
                .withPermanentlyDeleted(isPermanentlyDeleted)
                .generateEvent();
    }
}
