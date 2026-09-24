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
package org.alfresco.elasticsearch.batchindexing.config;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ContinuousReindexingProperties validation")
class ContinuousReindexingPropertiesTest
{
    private ContinuousReindexingProperties props;

    @BeforeEach
    void setUp()
    {
        props = new ContinuousReindexingProperties();
    }

    @Test
    @DisplayName("default values pass validation")
    void validPropertiesDoNotThrow()
    {
        assertThatCode(props::validate).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("maxWindow=0 with default polling throws")
    void maxWindowSmallerThanPollingThrows()
    {
        props.setMaxWindow(Duration.ZERO);

        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxWindow");
    }

    @Test
    @DisplayName("catchUpPollingInterval equal to pollingInterval throws")
    void catchUpGreaterThanPollingThrows()
    {
        props.setCatchUpPollingInterval(props.getPollingInterval());

        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("catchUpPollingInterval");
    }

    @Test
    @DisplayName("negative maxGapAge throws")
    void negativeMaxGapAgeThrows()
    {
        props.setMaxGapAge(Duration.ofHours(-1));

        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxGapAge");
    }

    @Test
    @DisplayName("negative watermarkRetryDelay throws")
    void negativeRetryDelayThrows()
    {
        props.setWatermarkRetryDelay(Duration.ofMillis(-1));

        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("watermarkRetryDelay");
    }

    @Test
    @DisplayName("watermarkRetryMaxAttempts=0 throws")
    void zeroRetryAttemptsThrows()
    {
        props.setWatermarkRetryMaxAttempts(0);

        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("watermarkRetryMaxAttempts");
    }

    @Test
    @DisplayName("stuckThreshold equal to maxPollingInterval throws")
    void stuckThresholdEqualToMaxPollingThrows()
    {
        props.setStuckThreshold(props.getMaxPollingInterval());

        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stuckThreshold");
    }

    @Test
    @DisplayName("stuckThreshold less than maxPollingInterval throws")
    void stuckThresholdLessThanMaxPollingThrows()
    {
        props.setStuckThreshold(props.getMaxPollingInterval().minusSeconds(1));

        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stuckThreshold");
    }
}
