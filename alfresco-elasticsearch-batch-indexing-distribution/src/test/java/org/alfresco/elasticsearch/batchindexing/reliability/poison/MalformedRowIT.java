/*-
 * #%L
 * Alfresco Elasticsearch Connector :: Batch Indexing Distribution
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
import java.util.Optional;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.alfresco.elasticsearch.batchindexing.reliability.harness.AlfrescoIndexHelper;
import org.alfresco.elasticsearch.batchindexing.reliability.harness.BaseReliabilityIT;
import org.alfresco.elasticsearch.batchindexing.reliability.harness.DistributionEnv;
import org.alfresco.elasticsearch.batchindexing.reliability.harness.DlqProbe;
import org.alfresco.elasticsearch.batchindexing.reliability.harness.ReliabilityStack;
import org.alfresco.elasticsearch.batchindexing.reliability.harness.WatermarkProbe;

/**
 * Malformed-row poison → reader-side DLQ visibility.
 * <p>
 * The reader fetches a whole JDBC page in one {@code getAlfrescoNodes} call; if any row's mapper throws {@code SQLException} the entire page fails. Reindexing's {@code AbstractAlfrescoNodeReader} catches the page failure, hands it to an opt-in {@code PageReadFailureListener} (batch-indexing registers {@code DlqPageReadFailureListener}), and returns an empty page so the step commits, the cursor advances past the window, and future cycles keep making progress. The trade-off is that all rows in the failed page (and any pages after it in the same cycle) are lost — the operator has to trigger a targeted reindexById for the window bounds after seeing the DLQ record.
 * <p>
 * Poison shape: one Postgres init script that {@code ALTER}s away the {@code NOT NULL} on {@code alf_node.uuid} and sets one node's uuid to NULL. The row mapper's {@code requiredString("uuid")} then throws {@code DataAccessException} whenever that row is in a scanned page.
 * <p>
 * Assertions:
 * <ol>
 * <li>DLQ contains exactly one {@code READER} entry keyed by the scan window (id shape {@code window:<tsFrom>-<tsTo>}), with a non-null {@code failureType} and a {@code failureReason} that carries the window bounds.</li>
 * <li>Watermark reports {@code lastRunStatus=COMPLETED} and {@code lastSuccessfulToTimeEpochMs} advanced past the seed value — proof the cycle didn't stall.</li>
 * <li>Alfresco index has at least one document — proof that the pages before the poison page were committed and that the reader didn't just abandon the whole cycle.</li>
 * </ol>
 * <p>
 * Uses the same {@code batch-indexing-reliability-poison-compose.yaml} as {@link MissingTagPoisonIT}; only the {@code POISON_SQL_PATH} script differs.
 */
@Tag("poison")
public class MalformedRowIT extends BaseReliabilityIT
{
    private static final Logger LOG = LoggerFactory.getLogger(MalformedRowIT.class);

    private static final String POISON_SQL_NAME = "zz-null-uuid-poison.sql";

    /** JVM warm-up + one poison cycle + retry backoff. */
    private static final Duration DLQ_AVAILABILITY_TIMEOUT = Duration.ofMinutes(3);

    private static ReliabilityStack stack;
    private static WatermarkProbe watermarkProbe;
    private static DlqProbe dlqProbe;
    private static AlfrescoIndexHelper alfrescoIndex;

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
        alfrescoIndex = new AlfrescoIndexHelper(stack.openSearch().client());

        // Same seed as the other poison ITs — makes the first cycle sweep back to 2021 where the
        // dump lives, otherwise the scan starts at "now" and never touches the poison row.
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
    void malformedRowLandsInDlqAndCycleAdvances() throws IOException
    {
        LOG.info("[poison] Waiting up to {} for READER DLQ entry and COMPLETED cycle",
                DLQ_AVAILABILITY_TIMEOUT);
        await().atMost(DLQ_AVAILABILITY_TIMEOUT)
                .pollInterval(Duration.ofSeconds(2))
                .ignoreExceptions()
                .until(() -> findReaderEntry().isPresent() && cycleCompleted());

        DlqProbe.Entry entry = findReaderEntry().orElseThrow();
        LOG.info("[poison] READER DLQ entry: {}", entry);

        assertThat(entry.failureStage()).isEqualTo("READER");
        // dbId is null on window-level entries — the reader never materialised the poison row so we
        // don't know which node it was. sourceCommitTimeMs carries the window tsFrom instead.
        assertThat(entry.dbId()).as("read-side skip has no per-node identity").isNull();
        assertThat(entry.nodeRef()).as("read-side skip has no per-node identity").isNull();
        assertThat(entry.sourceCommitTimeMs())
                .as("window tsFrom is stashed here so an operator can see the window start at a glance")
                .isNotNull();
        assertThat(entry.failureType())
                .as("simple class name of the wrapping DataAccessException")
                .isNotBlank();
        assertThat(entry.failureReason())
                .as("reason is prefixed with the window bounds so the operator can see the range")
                .contains("window [")
                .contains(String.valueOf(entry.sourceCommitTimeMs()));
        assertThat(entry.failureCount()).as("first-time failure").isEqualTo(1);

        WatermarkProbe.Watermark watermark = watermarkProbe.read().orElseThrow();
        LOG.info("[poison] Watermark after malformed-row cycle: {}", watermark);
        assertThat(watermark.lastRunStatus())
                .as("reader swallowed the failure so the cycle should COMPLETE, not FAIL")
                .isEqualTo("COMPLETED");
        assertThat(watermark.lastSuccessfulToTimeEpochMs())
                .as("cycle completed → cursor advanced past the seed value")
                .isGreaterThan(1L);

        long alfrescoDocs = alfrescoIndex.countDocuments();
        LOG.info("[poison] Alfresco index doc count after malformed-row cycle: {}", alfrescoDocs);
        assertThat(alfrescoDocs)
                .as("at least the RepositoryDescriptor from the schema-validate step (which runs before the by-date reader) should be indexed — proving the poison didn't take the whole app down")
                .isGreaterThanOrEqualTo(1L);

        assertThat(countReaderEntries())
                .as("one window failure → one DLQ record; the merge-key is (tsFrom, tsTo)")
                .isEqualTo(1);
    }

    private static boolean cycleCompleted() throws IOException
    {
        return watermarkProbe.read()
                .map(w -> "COMPLETED".equals(w.lastRunStatus()))
                .orElse(false);
    }

    private static Optional<DlqProbe.Entry> findReaderEntry() throws IOException
    {
        return dlqProbe.readAll().stream()
                .filter(e -> "READER".equals(e.failureStage()))
                .findFirst();
    }

    private static long countReaderEntries() throws IOException
    {
        return dlqProbe.readAll().stream()
                .filter(e -> "READER".equals(e.failureStage()))
                .count();
    }
}
