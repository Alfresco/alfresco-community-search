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
package org.alfresco.elasticsearch.batchindexing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.test.annotation.DirtiesContext;

import org.alfresco.elasticsearch.batchindexing.model.WatermarkDocument;

/**
 * Integration tests verifying watermark persistence and continuous reindexing lifecycle. Tests are ordered because watermark assertions depend on the state of previous cycles.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public class BatchIndexingWatermarkIT extends BatchIndexingAbstractIT
{

    @Test
    @Order(1)
    void watermark_shouldBePersisted_afterSuccessfulCycle() throws WatermarkAccessException
    {
        Optional<WatermarkDocument> before = watermarkStore.read();
        assertThat(before).isPresent();
        long previousToTime = before.get().getLastSuccessfulToTimeEpochMs();

        continuousReindexingService.runCycleSafe();

        Optional<WatermarkDocument> after = watermarkStore.read();
        assertThat(after).isPresent();

        WatermarkDocument watermark = after.get();
        assertThat(watermark.getLastSuccessfulToTimeEpochMs()).isGreaterThanOrEqualTo(previousToTime);
        assertThat(watermark.getLastRunStatus()).isEqualTo("COMPLETED");
        assertThat(watermark.getUpdatedAt()).isNotBlank();
    }

    @Test
    @Order(2)
    void watermark_shouldAdvance_acrossConsecutiveCycles() throws WatermarkAccessException
    {
        Optional<WatermarkDocument> before = watermarkStore.read();
        assertThat(before).isPresent();
        long previousToTime = before.get().getLastSuccessfulToTimeEpochMs();

        continuousReindexingService.runCycleSafe();

        Optional<WatermarkDocument> after = watermarkStore.read();
        assertThat(after).isPresent();
        long nextToTime = after.get().getLastSuccessfulToTimeEpochMs();

        assertThat(nextToTime).isGreaterThanOrEqualTo(previousToTime);
    }

    @Test
    @Order(3)
    void cycle_shouldDoNoWork_whenWindowHasNoNewDbChanges() throws WatermarkAccessException
    {
        // After @Order(1) and @Order(2) the dump has been fully reindexed. The static
        // testcontainer DB hasn't changed since, so the next cycle's window
        // (lastSuccessfulToTime - overlap, now) cannot contain any new rows.
        continuousReindexingService.runCycleSafe();

        Optional<WatermarkDocument> watermark = watermarkStore.read();
        assertThat(watermark).isPresent();

        assertThat(watermark.get().getLastRunReadCount())
                .as("Cycle window had no new DB changes — readCount must be 0. "
                        + "A non-zero value means the reindexByDate reader is ignoring the "
                        + "fromTime/toTime job parameters and rescanning the whole dataset.")
                .isZero();
        assertThat(watermark.get().getLastRunWriteCount())
                .as("Cycle window had no new DB changes — writeCount must be 0.")
                .isZero();
    }

    @Test
    @Order(4)
    void lifecycle_startAndStop_shouldBeFunctional()
    {
        assertThat(continuousReindexingService.isRunning()).isFalse();

        continuousReindexingService.start();
        assertThat(continuousReindexingService.isRunning()).isTrue();

        continuousReindexingService.stop();
        assertThat(continuousReindexingService.isRunning()).isFalse();
    }
}
