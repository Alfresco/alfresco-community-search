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
package org.alfresco.reindexing.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ElasticsearchReindexByDateJobConfig time bounds")
class ElasticsearchReindexByDateJobConfigTest
{
    private static final LocalDateTime FROM = LocalDateTime.of(2026, 1, 1, 0, 0);
    private static final LocalDateTime TO = LocalDateTime.of(2026, 1, 2, 0, 0);

    @Test
    @DisplayName("accepts a window with fromTime before toTime")
    void acceptsValidWindow()
    {
        assertThatCode(() -> ElasticsearchReindexByDateJobConfig.validateTimeBounds(FROM, TO))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("rejects a reversed window instead of completing having read nothing")
    void rejectsReversedWindow()
    {
        assertThatThrownBy(() -> ElasticsearchReindexByDateJobConfig.validateTimeBounds(TO, FROM))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("before");
    }

    @Test
    @DisplayName("rejects an empty window")
    void rejectsEmptyWindow()
    {
        assertThatThrownBy(() -> ElasticsearchReindexByDateJobConfig.validateTimeBounds(FROM, FROM))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("names both properties when one-shot bounds are missing, rather than failing with an NPE")
    void rejectsMissingBounds()
    {
        assertThatThrownBy(() -> ElasticsearchReindexByDateJobConfig.validateTimeBounds(null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("alfresco.reindex.fromTime")
                .hasMessageContaining("alfresco.reindex.toTime");

        assertThatThrownBy(() -> ElasticsearchReindexByDateJobConfig.validateTimeBounds(FROM, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
