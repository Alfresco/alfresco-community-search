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
import java.util.List;
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
 * Missing-tag poison → DLQ visibility.
 * <p>
 * Mirrors a customer scenario where ACS Share allowed {@code cm:categories} to be attached as {@code cm:taggable} values. The reindexer's tag resolver never finds those UUIDs under {@code tag:tag-root}, so {@code AddTagsPostProcessor} throws {@code ElasticsearchUnexpectedException} when {@code alfresco.reindex.failOnMissingTag=true} (the shipping default).
 * <p>
 * The stack is the standard reliability lifecycle stack plus one extra postgres init script that adds one hostile {@code cm:taggable} value on node 384 (uuid {@code d1ddf3fc-...}). That's the entire poison shape — a single row.
 * <p>
 * With batch-indexing's default {@code skipLimit=100} the exception is caught by Spring Batch skip and {@code DlqSkipListener.onSkipInWrite} routes the node to the DLQ. The stage is {@code METADATA_WRITER} because {@code AddTagsPostProcessor} runs inside the writer (via {@code ReindexingRequestBuilder.buildBulkRequest} → {@code encodedFieldsExtractor.extract}), not in the item processor — so Spring Batch categorises the skip as write-side. The cycle completes {@code COMPLETED} and the other ~70 nodes end up in the {@code alfresco} index normally.
 * <p>
 * Assertions:
 * <ol>
 * <li>DLQ contains a record for node 384 with {@code failureStage=METADATA_WRITER}, {@code failureType=ElasticsearchUnexpectedException}, {@code failureReason} mentioning the missing tag UUID, and a non-null {@code dbId} / {@code nodeRef}.</li>
 * <li>Watermark reports {@code lastRunStatus=COMPLETED} and {@code lastSuccessfulToTimeEpochMs} advanced past the seed value.</li>
 * <li>Other nodes in the same chunk survive the skip and end up in the {@code alfresco} index — this is the {@code MixedBatchOneBadIT} row-7 shape absorbed into this test. Spring Batch splits the failing chunk into per-item writes; only the poison node is skipped, everything else commits.</li>
 * </ol>
 * <p>
 * The regression variant — same seed, {@code skipLimit=0}, cycle stays {@code FAILED} and watermark held — lives in {@link MissingTagStuckWithoutSkipIT}.
 */
@Tag("poison")
public class MissingTagPoisonIT extends BaseReliabilityIT
{
    private static final Logger LOG = LoggerFactory.getLogger(MissingTagPoisonIT.class);

    private static final String POISON_SQL_NAME = "zz-missing-tag-poison.sql";

    /** Node 384 in {@code alfresco-dump.sql} — already carries valid tags at list_index 0/1; the poison sits at list_index 2. */
    static final long POISON_NODE_DB_ID = 384L;
    /** Node 384's uuid — this is what {@code AlfrescoNode.getNodeRef()} returns and what the DLQ record keys on. */
    static final String POISON_NODE_UUID = "d1ddf3fc-ffd5-4877-b181-8f681f1f1ee8";
    /** Bogus tag UUID — must match {@code zz-missing-tag-poison.sql}. */
    static final String POISON_TAG_UUID = "deadc0de-c0de-c0de-c0de-c0dec0dec0de";
    /** Thrown by {@link org.alfresco.indexing.shared.tag.AddTagsPostProcessor} when a tag id can't be resolved. */
    static final String EXPECTED_FAILURE_TYPE = "ElasticsearchUnexpectedException";

    /** JVM warm-up + one poison cycle + retry backoff — 3 minutes is comfortable. */
    private static final Duration DLQ_AVAILABILITY_TIMEOUT = Duration.ofMinutes(3);
    /**
     * Lower bound on nodes the batch-indexer writes when it sweeps the whole 2021 dump. The dump has ~70 candidate nodes; 10 is a floor that stays valid even if the dump loses or gains a handful of rows. Matches the floor used in {@link EsBulkMappingConflictIT}.
     */
    private static final long MIN_ALFRESCO_INDEX_DOCS = 10L;

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

        // Seed the watermark so the first cycle scans back to 2021 — otherwise batch-indexing
        // starts from "now" and never reaches the dump nodes. Written before batch-indexing's
        // first poll ends; if it wins the race the seed is overwritten and we retry on the
        // next cycle, which still sees our seed.
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
    void missingTagLandsInDlqAndCycleCompletes() throws IOException
    {
        LOG.info("[poison] Waiting up to {} for both DLQ entry (node {}) and COMPLETED watermark",
                DLQ_AVAILABILITY_TIMEOUT, POISON_NODE_DB_ID);
        await().atMost(DLQ_AVAILABILITY_TIMEOUT)
                .pollInterval(Duration.ofSeconds(2))
                .ignoreExceptions()
                .until(() -> findPoisonEntry().isPresent() && cycleCompleted());

        DlqProbe.Entry entry = findPoisonEntry().orElseThrow();
        LOG.info("[poison] DLQ entry for poison node: {}", entry);

        assertThat(entry.dbId()).as("dbId keys the DLQ document; must match the poisoned node").isEqualTo(POISON_NODE_DB_ID);
        assertThat(entry.nodeRef()).as("nodeRef comes from the RepoEvent's NodeResource — the bare uuid").isEqualTo(POISON_NODE_UUID);
        assertThat(entry.failureStage())
                .as("AddTagsPostProcessor runs inside the writer, so Spring Batch skips it as write-side")
                .isEqualTo("METADATA_WRITER");
        assertThat(entry.failureType()).as("simple class name of the exception thrown by AddTagsPostProcessor")
                .isEqualTo(EXPECTED_FAILURE_TYPE);
        assertThat(entry.failureReason()).as("the exception message includes the missing tag uuid")
                .containsIgnoringCase("tag")
                .contains(POISON_TAG_UUID);
        assertThat(entry.failureCount()).as("first-time failure").isEqualTo(1);

        WatermarkProbe.Watermark watermark = watermarkProbe.read().orElseThrow();
        LOG.info("[poison] Watermark after poison cycle: {}", watermark);
        assertThat(watermark.lastRunStatus())
                .as("skip absorbed the poison — cycle should complete, not fail")
                .isEqualTo("COMPLETED");
        assertThat(watermark.lastSuccessfulToTimeEpochMs())
                .as("cycle completed → watermark advanced past the seed value")
                .isGreaterThan(1L);
        // Closes plan gap #6 observability side: skip totals must reach the watermark doc,
        // not only the DLQ. One poison row → exactly one write-side skip on the completing cycle.
        assertThat(watermark.lastRunSkipCount())
                .as("Spring Batch write-side skip count must reach the watermark doc")
                .isEqualTo(1L);

        // Row-7 "mixed batch" contract: the poison chunk was retried item-by-item and the good items
        // were committed. If Spring Batch had thrown the whole chunk away we'd see zero (or very few)
        // alfresco docs.
        long alfrescoDocs = alfrescoIndex.countDocuments();
        LOG.info("[poison] Alfresco index doc count after poison cycle: {}", alfrescoDocs);
        assertThat(alfrescoDocs)
                .as("good items in the poisoned chunk should be indexed; only node %d is skipped", POISON_NODE_DB_ID)
                .isGreaterThanOrEqualTo(MIN_ALFRESCO_INDEX_DOCS);
    }

    private static boolean cycleCompleted() throws IOException
    {
        return watermarkProbe.read()
                .map(w -> "COMPLETED".equals(w.lastRunStatus()))
                .orElse(false);
    }

    private static Optional<DlqProbe.Entry> findPoisonEntry() throws IOException
    {
        List<DlqProbe.Entry> entries = dlqProbe.readAll();
        return entries.stream()
                .filter(e -> e.dbId() != null && e.dbId() == POISON_NODE_DB_ID)
                .findFirst();
    }
}
