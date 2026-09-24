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

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.Optional;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import org.alfresco.repo.event.EventAttributes;

/**
 * Utility class containing common helpers for repository events.
 */
@Slf4j
@UtilityClass
public class EventUtils
{
    public static long getEventTimestamp(EventAttributes event)
    {
        return Optional.ofNullable(event.getTime())
                .map(ZonedDateTime::toInstant)
                .map(Instant::toEpochMilli)
                .orElseThrow(() -> {
                    log.trace("Event timestamp is null for: {}", event);
                    return new IllegalStateException(String.format("Incorrect event %s: the time of event is null",
                            event.getClass().getCanonicalName()));
                });
    }
}
