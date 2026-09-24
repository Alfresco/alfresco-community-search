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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static org.alfresco.indexing.shared.utils.EventUtils.getEventTimestamp;
import static org.alfresco.repo.event.v1.model.EventType.NODE_CREATED;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import org.alfresco.repo.event.v1.model.RepoEvent;

class EventUtilsTest
{
    @ParameterizedTest
    @CsvSource({"Z", "+01:00", "-08:00", "+05:30"})
    void shouldReturnCorrectEventTimestampInDifferentZones(String offset)
    {
        var instant = Instant.now();
        var eventTime = ZonedDateTime.ofInstant(instant, ZoneOffset.of(offset));
        var repoEvent = repoEventAt(eventTime);

        var eventTimestamp = getEventTimestamp(repoEvent);

        assertThat(eventTimestamp).isEqualTo(instant.toEpochMilli());
    }

    @Test
    void shouldThrowExceptionForEventWithoutTime()
    {
        // given
        var repoEvent = repoEventAt(null);

        // when, then
        assertThatThrownBy(() -> getEventTimestamp(repoEvent))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Incorrect event org.alfresco.repo.event.v1.model.RepoEvent: the time of event is null");
    }

    private RepoEvent<?> repoEventAt(ZonedDateTime time)
    {
        return RepoEvent.builder()
                .setType(NODE_CREATED.getType())
                .setTime(time)
                .build();
    }
}
