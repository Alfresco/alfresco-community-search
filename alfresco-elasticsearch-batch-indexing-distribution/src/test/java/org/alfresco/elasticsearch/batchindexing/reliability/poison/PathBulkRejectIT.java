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
 * Path-writer per-item bulk rejection → DLQ visibility (path-writer variant of the ES bulk mapping conflict poison).
 * <p>
 * Mirror of {@link EsBulkMappingConflictIT} but poisons a field the <b>path writer</b> touches ({@code PATH}) instead of one the metadata writer touches ({@code ALIVE}). {@code PATH} is declared as {@code long}, everything else dynamic. Sequence per node:
 * <ol>
 * <li>{@code RepositoryDescriptorItemWriter} single-doc write — untouched, doesn't set {@code PATH}.</li>
 * <li>{@code ElasticsearchRepoEventItemWriter} metadata bulk — succeeds; dynamic mapping absorbs {@code cm:*}/{@code sys:*} fields, {@code PATH} isn't touched.</li>
 * <li>{@code ElasticsearchPathWriter} path bulk — script sets {@code ctx._source.PATH = [strings...]}; ES re-index fails mapping validation with {@code mapper_parsing_exception} because {@code PATH} is {@code long}. Item retries {@code writerRetryCount} more times, then the final round's failure is surfaced through {@link org.alfresco.reindexing.writers.PathBulkItemFailureListener} and bridged to the DLQ with {@code failureStage=PATH_WRITER}.</li>
 * </ol>
 * <p>
 * Assertion set is the same shape as the metadata poison test: DLQ populated, records carry the {@code PATH_WRITER} stage, populated {@code failureType}/{@code failureReason}, and both {@code dbId} + {@code nodeRef}.
 * <p>
 * <b>Start-order.</b> Same trick as {@link EsBulkMappingConflictIT}: SIGKILL batch-indexing right after compose start so its first cycle can't auto-create {@code alfresco} with dynamic {@code PATH} before we install the poison mapping.
 * <p>
 * The default {@code writerRetryCount=3} adds ~3 s per cycle (one retry loop over the ~70-node dump fits in a single chunk at {@code batchSize=1000}). Every item lands in the DLQ exactly once after the final round.
 */
@Tag("poison")
public class PathBulkRejectIT extends BaseReliabilityIT
{
    private static final Logger LOG = LoggerFactory.getLogger(PathBulkRejectIT.class);

    private static final String ALFRESCO_INDEX = "alfresco";

    private static final String POLLING_INTERVAL = "5s";
    /** Same value the memory/capacity ITs use — big enough for a single cycle to sweep the entire history back to epoch 0. */
    private static final String MAX_WINDOW = "876000h";
    private static final Duration DLQ_AVAILABILITY_TIMEOUT = Duration.ofMinutes(3);
    /** Floor well below the ~70-node dump, robust to small dump changes. Every node in the swept window should end up in the DLQ from a path-write failure. */
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

        // Same race-proofing recipe as EsBulkMappingConflictIT.
        LOG.info("[poison] Killing batch-indexing so we can seed poison mapping before its first cycle");
        stack.serviceControl().kill(ReliabilityStack.BATCH_INDEXING_SERVICE);

        WatermarkProbe watermarkProbe = new WatermarkProbe(stack.openSearch().client());
        dlqProbe = new DlqProbe(stack.openSearch().client());

        deleteAlfrescoIndexIfExists();
        createPoisonAlfrescoIndex();
        watermarkProbe.seedForFullHistoricalScan();
        LOG.info("[poison] Restarting batch-indexing — path writes should now hit the poison mapping");
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
    void perItemPathBulkFailuresLandInDlqWithPathWriterStage() throws IOException
    {
        LOG.info("[poison] Waiting up to {} for DLQ to fill from path-write failures", DLQ_AVAILABILITY_TIMEOUT);
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
                    .as("every DLQ record must be tagged PATH_WRITER (path-writer bridge)")
                    .isEqualTo("PATH_WRITER");
            assertThat(entry.failureType()).as("failureType should carry the ES error classifier").isNotBlank();
            assertThat(entry.failureReason()).as("failureReason should carry the ES error message").isNotBlank();
            assertThat(entry.dbId()).as("dbId must be correlated back to the source chunk item").isNotNull();
            assertThat(entry.nodeRef()).as("nodeRef comes straight from the ES bulk response").isNotBlank();
            assertThat(entry.failureCount()).as("first-time failure").isEqualTo(1);
        });
    }

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
     * Create the {@code alfresco} index with {@code PATH} declared as {@code long}. Everything else stays dynamic so the descriptor + metadata bulk writes succeed. When the path writer's script sets {@code ctx._source.PATH = [strings...]} ES rejects the re-index per bulk item with {@code mapper_parsing_exception} — which is what the {@link org.alfresco.reindexing.writers.PathBulkItemFailureListener} bridge feeds into the DLQ.
     */
    private static void createPoisonAlfrescoIndex() throws IOException
    {
        String body = "{"
                + "\"settings\":{\"index\":{\"number_of_shards\":1,\"number_of_replicas\":0}},"
                + "\"mappings\":{\"properties\":{\"PATH\":{\"type\":\"long\"}}}}";
        Request request = new Request("PUT", "/" + ALFRESCO_INDEX);
        request.setEntity(new StringEntity(body, ContentType.APPLICATION_JSON));
        stack.openSearch().restClient().performRequest(request);
        LOG.info("[poison] Created '{}' with PATH mapped as long; path writes will fail per-item", ALFRESCO_INDEX);
    }
}
