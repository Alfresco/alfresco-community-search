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
import java.time.Duration;
import java.util.List;

import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.opensearch.client.Request;
import org.opensearch.client.ResponseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.alfresco.elasticsearch.batchindexing.reliability.harness.BaseReliabilityIT;
import org.alfresco.elasticsearch.batchindexing.reliability.harness.DlqProbe;
import org.alfresco.elasticsearch.batchindexing.reliability.harness.ReliabilityStack;
import org.alfresco.elasticsearch.batchindexing.reliability.harness.WatermarkProbe;

/**
 * Elasticsearch bulk-write mapping conflict → DLQ visibility.
 * <p>
 * The Alfresco index is pre-created with a single hostile field: {@code ALIVE} declared as {@code long}. Dynamic mapping is left on for everything else, so the {@code RepositoryDescriptorItemWriter} single-doc write goes through untouched (the descriptor doesn't set {@code ALIVE}). Every regular metadata bulk operation sets {@code ALIVE: true} (boolean) and gets rejected <b>per bulk item</b> with {@code mapper_parsing_exception}. Watermark is seeded to epoch 1 and {@code maxWindow} bumped to 100 years so one cycle sweeps the whole ~70-node dump into the poison.
 * <p>
 * Letting the app auto-create the index would let dynamic mapping absorb everything and the bridge would stay dormant. A fully strict mapping would abort the descriptor step and never reach the bulk path. Poisoning one field is the narrowest shape that exercises the bridge end-to-end without depending on the specific field set the descriptor writes.
 * <p>
 * With the write-side {@link org.alfresco.reindexing.writers.BulkItemFailureListener} hook shipped, batch-indexing bridges those per-item failures into its DLQ. The assertion is:
 * <ol>
 * <li>DLQ index is populated (count &gt; 0).</li>
 * <li>Sample records carry {@code failureStage=METADATA_WRITER}, a non-null {@code failureType} ({@code mapper_parsing_exception}), a non-null {@code failureReason}, and both {@code dbId} and {@code nodeRef}.</li>
 * </ol>
 * <p>
 * <b>Start-order.</b> The compose can't seed the alfresco index before batch-indexing starts, because Testcontainers has no hook between "ES healthy" and "batch-indexing container process started". Left alone, batch-indexing's initial-delay=0 scheduler runs its first cycle in a couple of seconds — which lets the writer auto-create the {@code alfresco} index with the app's dynamic mapping. That would render the poison mapping unreachable. The workaround is to SIGKILL batch-indexing right after {@link ComposeContainer#start()} returns, do the seeding, then restart it. Kill is fine here: nothing important is running yet, and Testcontainers keeps the compose network alive across the bounce.
 */
@Tag("poison")
public class EsBulkMappingConflictIT extends BaseReliabilityIT
{
    private static final Logger LOG = LoggerFactory.getLogger(EsBulkMappingConflictIT.class);

    private static final String ALFRESCO_INDEX = "alfresco";

    /** Fast polling — once we restart the app after seeding we want the first poison cycle to kick off promptly. */
    private static final String POLLING_INTERVAL = "5s";
    /**
     * Same value the memory/capacity ITs use — big enough for a single cycle to sweep the entire history back to epoch 0 without splitting into per-window chunks. The dump seed is April 2021 which is well beyond the default {@code 24h}.
     */
    private static final String MAX_WINDOW = "876000h";
    /** Time to wait for the DLQ to reflect the poisoned writes. Cycle + bulk + ES ack is a few seconds; leave slack for JVM warm-up + restart. */
    private static final Duration DLQ_AVAILABILITY_TIMEOUT = Duration.ofMinutes(3);
    /**
     * Minimum expected DLQ records. The dump seeds a small tree; the batch-indexing chunk size means every node in the scanned window should end up in the DLQ. Ten is a floor well below the ~70-node dump but robust to small dump changes.
     */
    private static final long MIN_EXPECTED_DLQ_RECORDS = 10;

    private static ReliabilityStack stack;
    private static DlqProbe dlqProbe;

    @BeforeAll
    static void startStack() throws IOException
    {
        stack = ReliabilityStack.builder(LOG, "batch-indexing-reliability-lifecycle-compose.yaml")
                .env("LIFECYCLE_POLLING_INTERVAL", POLLING_INTERVAL)
                .env("LIFECYCLE_MAX_WINDOW", MAX_WINDOW)
                .start();

        // Race-proofing: SIGKILL batch-indexing so its cold-start cycle can't create the alfresco index
        // (with dynamic mapping) before we can poison it.
        LOG.info("[poison] Killing batch-indexing so we can seed poison state before its first cycle");
        stack.serviceControl().kill(ReliabilityStack.BATCH_INDEXING_SERVICE);

        WatermarkProbe watermarkProbe = new WatermarkProbe(stack.openSearch().client());
        dlqProbe = new DlqProbe(stack.openSearch().client());

        deleteAlfrescoIndexIfExists();
        createPoisonAlfrescoIndex();
        watermarkProbe.seedForFullHistoricalScan();
        LOG.info("[poison] Restarting batch-indexing — it should now read the seed and hit the poison mapping");
        stack.serviceControl().start(ReliabilityStack.BATCH_INDEXING_SERVICE);
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
    void perItemBulkFailuresLandInDlqWithMetadataWriterStage() throws IOException
    {
        LOG.info("[poison] Waiting up to {} for DLQ to fill", DLQ_AVAILABILITY_TIMEOUT);
        await().atMost(DLQ_AVAILABILITY_TIMEOUT)
                .pollInterval(Duration.ofSeconds(2))
                .ignoreExceptions()
                .until(() -> dlqProbe.count() >= MIN_EXPECTED_DLQ_RECORDS);

        long dlqCount = dlqProbe.count();
        LOG.info("[poison] DLQ contains {} records", dlqCount);

        List<DlqProbe.Entry> entries = dlqProbe.readAll();
        assertThat(entries)
                .as("DLQ should contain at least %d entries after the poisoned cycle", MIN_EXPECTED_DLQ_RECORDS)
                .hasSizeGreaterThanOrEqualTo((int) MIN_EXPECTED_DLQ_RECORDS);

        DlqProbe.Entry sample = entries.get(0);
        LOG.info("[poison] Sample DLQ entry: {}", sample);

        assertThat(entries).allSatisfy(entry -> {
            assertThat(entry.failureStage())
                    .as("every DLQ record must be tagged METADATA_WRITER (write-side skip)")
                    .isEqualTo("METADATA_WRITER");
            assertThat(entry.failureType()).as("failureType should be populated with the ES error classifier").isNotBlank();
            assertThat(entry.failureReason()).as("failureReason should carry the ES error message").isNotBlank();
            assertThat(entry.dbId()).as("dbId must be correlated back to the source chunk item").isNotNull();
            assertThat(entry.nodeRef()).as("nodeRef comes straight from the ES bulk response").isNotBlank();
            assertThat(entry.failureCount()).as("first-time failure").isEqualTo(1);
        });
    }

    /**
     * Best-effort delete of the {@code alfresco} index. If batch-indexing ran even a partial write before we killed it, the index will already exist with dynamic mapping — we need it gone so {@link #createPoisonAlfrescoIndex()} can put the strict mapping in its place.
     */
    private static void deleteAlfrescoIndexIfExists() throws IOException
    {
        Request request = new Request("DELETE", "/" + ALFRESCO_INDEX);
        try
        {
            stack.openSearch().restClient().performRequest(request);
            LOG.info("[poison] Deleted pre-existing '{}' index", ALFRESCO_INDEX);
        }
        catch (ResponseException e)
        {
            if (e.getResponse().getStatusLine().getStatusCode() != 404)
            {
                throw e;
            }
        }
    }

    /**
     * Create the {@code alfresco} index with a single hostile field: {@code ALIVE} declared as {@code long}. The {@code RepositoryDescriptorItemWriter} single-doc write doesn't touch {@code ALIVE} so it succeeds. Every regular metadata bulk write sets {@code ALIVE: true} (boolean) and gets a per-item {@code mapper_parsing_exception} — which is exactly what the bulk-item-failure listener bridges into the DLQ.
     * <p>
     * This is intentionally the narrowest poison shape: one field, one type mismatch, dynamic mapping otherwise on so we don't have to enumerate descriptor fields or worry about aspect drift.
     */
    private static void createPoisonAlfrescoIndex() throws IOException
    {
        String body = "{"
                + "\"settings\":{\"index\":{\"number_of_shards\":1,\"number_of_replicas\":0}},"
                + "\"mappings\":{\"properties\":{\"ALIVE\":{\"type\":\"long\"}}}}";
        Request request = new Request("PUT", "/" + ALFRESCO_INDEX);
        request.setEntity(new StringEntity(body, ContentType.APPLICATION_JSON));
        stack.openSearch().restClient().performRequest(request);
        LOG.info("[poison] Created '{}' with strict mapping (descriptor fields only); bulk writes will fail per-item", ALFRESCO_INDEX);
    }
}
