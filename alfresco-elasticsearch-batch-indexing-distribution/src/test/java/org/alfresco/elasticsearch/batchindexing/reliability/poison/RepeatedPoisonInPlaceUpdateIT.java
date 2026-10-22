/*-
 * #%L
 * Alfresco Search Community :: Batch Indexing Distribution
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
package org.alfresco.elasticsearch.batchindexing.reliability.poison;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.alfresco.elasticsearch.batchindexing.reliability.harness.BaseReliabilityIT;
import org.alfresco.elasticsearch.batchindexing.reliability.harness.DistributionEnv;
import org.alfresco.elasticsearch.batchindexing.reliability.harness.DlqProbe;
import org.alfresco.elasticsearch.batchindexing.reliability.harness.ReliabilityStack;
import org.alfresco.elasticsearch.batchindexing.reliability.harness.WatermarkProbe;

/**
 * DLQ in-place update semantics.
 * <p>
 * The DLQ writer merges by {@code dbId}: {@code firstFailedAt} is preserved, {@code lastFailedAt} refreshes, {@code failureCount} increments. That contract stays invisible in {@link MissingTagPoisonIT} (single cycle, single hit); this test proves it end-to-end by making the same poison node cross the reader window twice.
 * <p>
 * Reuses the missing-tag poison shape from {@link MissingTagPoisonIT} — a bogus {@code cm:taggable} value on node 384 that {@code AddTagsPostProcessor} can't resolve. The node lives in the 2021 dump, so the app won't naturally see it again after the first cycle finishes and the watermark jumps to "now". To force the second hit the test <b>bounces batch-indexing</b>: stop the service, re-seed the watermark back to epoch, start the service. The cold-start cycle then walks the whole dump again and hits the same poison node.
 * <p>
 * The bounce replaces an earlier "just re-seed and wait for the scheduler" shape, which was flaky in CI: the continuous scheduler occasionally never fired a second cycle after the first one completed, leaving the test to time out with no diagnostic signal to grab. A restart guarantees exactly one cold-start cycle from the seeded state — same pattern {@link EsBulkMappingConflictIT} and {@link TransformEmptyResponseIT} use for the same reason.
 * <p>
 * Assertions:
 * <ol>
 * <li>After the first cycle: exactly one DLQ document for the poison dbId, {@code failureCount == 1}.</li>
 * <li>After the restart cycle: still exactly one DLQ document, {@code failureCount == 2}, {@code firstFailedAt} unchanged, {@code lastFailedAt} newer than the first observation.</li>
 * </ol>
 */
@Tag("poison")
public class RepeatedPoisonInPlaceUpdateIT extends BaseReliabilityIT
{
    private static final Logger LOG = LoggerFactory.getLogger(RepeatedPoisonInPlaceUpdateIT.class);

    private static final String POISON_SQL_NAME = "zz-missing-tag-poison.sql";

    /** Same poison node as {@link MissingTagPoisonIT}; keeps the two ITs coupled to a single dbId. */
    private static final long POISON_NODE_DB_ID = MissingTagPoisonIT.POISON_NODE_DB_ID;

    /** JVM warm-up + one full-history sweep — mirrors {@link MissingTagPoisonIT#DLQ_AVAILABILITY_TIMEOUT}. */
    private static final Duration FIRST_HIT_TIMEOUT = Duration.ofMinutes(3);
    /**
     * Container restart + JVM warm-up + one cold-start full-history sweep. Same budget as the first hit so a slow CI runner has the same headroom.
     */
    private static final Duration SECOND_HIT_TIMEOUT = Duration.ofMinutes(3);
    /** How long we allow the compose service to reach a stopped / running state after {@code stop()} / {@code start()}. */
    private static final Duration SERVICE_STATE_TIMEOUT = Duration.ofSeconds(60);

    private static ReliabilityStack stack;
    private static WatermarkProbe watermarkProbe;
    private static DlqProbe dlqProbe;

    @BeforeAll
    static void startStack() throws IOException
    {
        Path poisonSql = DistributionEnv.poisonSqlPath(POISON_SQL_NAME);

        stack = ReliabilityStack.builder(LOG, "batch-indexing-reliability-poison-compose.yaml")
                .env("POISON_SQL_PATH", poisonSql.toString())
                .env("POISON_SKIP_LIMIT", "100")
                .start();

        watermarkProbe = new WatermarkProbe(stack.openSearch().client());
        dlqProbe = new DlqProbe(stack.openSearch().client());

        watermarkProbe.seedForFullHistoricalScan();
    }

    @AfterAll
    static void tearDown() throws IOException
    {
        if (stack != null)
        {
            stack.close();
        }
    }

    @Test
    void repeatedPoisonHitIncrementsFailureCountInPlace() throws IOException
    {
        LOG.info("[poison] Waiting up to {} for the first DLQ hit on node {}", FIRST_HIT_TIMEOUT, POISON_NODE_DB_ID);
        await().atMost(FIRST_HIT_TIMEOUT)
                .pollInterval(Duration.ofSeconds(2))
                .ignoreExceptions()
                .until(() -> findPoisonEntry().isPresent() && cycleCompleted());

        DlqProbe.Entry firstHit = findPoisonEntry().orElseThrow();
        LOG.info("[poison] First hit: {}", firstHit);
        assertThat(firstHit.failureCount()).as("first observation of the poison node").isEqualTo(1);
        assertThat(firstHit.firstFailedAt()).as("firstFailedAt is set on first write").isNotBlank();
        assertThat(firstHit.lastFailedAt()).as("lastFailedAt is set on first write").isNotBlank();

        LOG.info("[poison] Bouncing batch-indexing so the second cycle is a cold start from a fresh seed");
        stack.serviceControl().stop(ReliabilityStack.BATCH_INDEXING_SERVICE);
        await().atMost(SERVICE_STATE_TIMEOUT)
                .pollInterval(Duration.ofSeconds(1))
                .until(() -> !stack.serviceControl().isRunning(ReliabilityStack.BATCH_INDEXING_SERVICE));

        LOG.info("[poison] Re-seeding watermark to epoch so the restarted app scans node {} again", POISON_NODE_DB_ID);
        watermarkProbe.seedForFullHistoricalScan();

        stack.serviceControl().start(ReliabilityStack.BATCH_INDEXING_SERVICE);
        await().atMost(SERVICE_STATE_TIMEOUT)
                .pollInterval(Duration.ofSeconds(1))
                .until(() -> stack.serviceControl().isRunning(ReliabilityStack.BATCH_INDEXING_SERVICE));

        LOG.info("[poison] Waiting up to {} for failureCount to reach 2", SECOND_HIT_TIMEOUT);
        await().atMost(SECOND_HIT_TIMEOUT)
                .pollInterval(Duration.ofSeconds(2))
                .ignoreExceptions()
                .until(() -> findPoisonEntry()
                        .map(e -> e.failureCount() != null && e.failureCount() >= 2)
                        .orElse(false));

        DlqProbe.Entry secondHit = findPoisonEntry().orElseThrow();
        LOG.info("[poison] Second hit: {}", secondHit);

        assertThat(dlqProbe.count())
                .as("still exactly one DLQ document — merged in place, not appended")
                .isEqualTo(1L);
        assertThat(secondHit.dbId()).isEqualTo(POISON_NODE_DB_ID);
        assertThat(secondHit.failureCount())
                .as("second hit should increment the counter")
                .isEqualTo(2);
        assertThat(secondHit.firstFailedAt())
                .as("firstFailedAt is stamped on creation and never rewritten")
                .isEqualTo(firstHit.firstFailedAt());
        assertThat(secondHit.lastFailedAt())
                .as("lastFailedAt refreshes on every failure")
                .isNotEqualTo(firstHit.lastFailedAt());
    }

    private static Optional<DlqProbe.Entry> findPoisonEntry() throws IOException
    {
        List<DlqProbe.Entry> entries = dlqProbe.readAll();
        return entries.stream()
                .filter(e -> e.dbId() != null && e.dbId() == POISON_NODE_DB_ID)
                .findFirst();
    }

    private static boolean cycleCompleted() throws IOException
    {
        return watermarkProbe.read()
                .map(w -> "COMPLETED".equals(w.lastRunStatus()))
                .orElse(false);
    }
}
