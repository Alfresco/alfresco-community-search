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
package org.alfresco.elasticsearch.batchindexing.reliability.infra_recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.time.Duration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.opensearch.client.opensearch.indices.DeleteIndexRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.alfresco.elasticsearch.batchindexing.reliability.harness.BaseReliabilityIT;
import org.alfresco.elasticsearch.batchindexing.reliability.harness.ReliabilityStack;
import org.alfresco.elasticsearch.batchindexing.reliability.harness.WatermarkProbe;

/**
 * State index deleted while the indexer is actively cycling.
 * <p>
 * State index deleted mid-cycle. End-to-end contract: the JVM must not exit, the cursor must reappear, and it must have a well-formed schema so the next cycle can read and advance it.
 * <p>
 * The stronger contract — "the in-memory cursor is preserved and reused on the recovery write" — is asserted by the unit tests in {@code ElasticsearchWatermarkStoreTest.StateDeletedRecoveryTests} (specifically {@code doc_missing_after_409_triggers_unconditional_retry}, which verifies the retried write drops the OCC guard and carries the same {@code WatermarkDocument} instance). This IT does not try to reproduce that at the container level because guaranteeing a delete lands mid-cycle would require slowing the write path with toxiproxy or driving cycles by hand; the two-layer defence (unit test for the persistence contract, IT for end-to-end continuity) is intentional.
 * <p>
 * Assertions:
 * <ul>
 * <li>A valid cursor exists before the deletion.</li>
 * <li>The state index is gone immediately after the delete call.</li>
 * <li>Within a couple of cycles, a watermark document reappears with a valid schema and a non-empty {@code lastRunStatus}.</li>
 * <li>A subsequent cycle continues to advance the cursor (or at least re-writes it) — proves the recovery didn't leave the store in a stuck state.</li>
 * <li>The batch-indexing container is still running.</li>
 * </ul>
 */
@Tag("infra-recovery")
public class StateIndexDeletedMidCycleIT extends BaseReliabilityIT
{
    private static final Logger LOG = LoggerFactory.getLogger(StateIndexDeletedMidCycleIT.class);

    private static final Duration RECOVERY_DEADLINE = Duration.ofMinutes(2);

    private static ReliabilityStack stack;
    private static WatermarkProbe watermarkProbe;

    @BeforeAll
    static void startStack() throws IOException
    {
        stack = ReliabilityStack.builder(LOG, "batch-indexing-reliability-lifecycle-compose.yaml").start();
        watermarkProbe = new WatermarkProbe(stack.openSearch().client());
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
    void deletingStateIndexMidCycleDoesNotCrashTheApp() throws Exception
    {
        LOG.info("[infra-recovery] Waiting for the first cursor before deleting the state index");
        // ignoreExceptions to ride out shard RECOVERING on the freshly-created state index.
        await().atMost(Duration.ofMinutes(2))
                .pollInterval(Duration.ofSeconds(1))
                .ignoreExceptions()
                .until(() -> watermarkProbe.read().isPresent());

        LOG.info("[infra-recovery] Deleting state index '{}'", WatermarkProbe.INDEX);
        stack.openSearch().client().indices().delete(new DeleteIndexRequest.Builder()
                .index(WatermarkProbe.INDEX)
                .build());

        // Sanity check the index is really gone before we wait for it to come back.
        assertThat(watermarkProbe.read())
                .as("The state index should be gone immediately after DELETE — read must return empty")
                .isEmpty();

        LOG.info("[infra-recovery] Waiting for the app to write a fresh cursor on its next cycle");
        await().atMost(RECOVERY_DEADLINE)
                .pollInterval(Duration.ofSeconds(2))
                .ignoreExceptions()
                .until(() -> watermarkProbe.read().isPresent());

        WatermarkProbe.Watermark recovered = watermarkProbe.read().orElseThrow();
        LOG.info("[infra-recovery] Cursor after state-index recovery: {}", recovered);

        assertThat(recovered.schemaVersion())
                .as("recovered watermark must carry the schema version so future cycles can read it")
                .isNotNull()
                .isEqualTo(1);
        assertThat(recovered.lastRunStatus())
                .as("recovered watermark must record a run status so operators can see the last cycle outcome")
                .isNotBlank();
        assertThat(recovered.lastSuccessfulToTimeEpochMs())
                .as("recovered watermark must have a non-null anchor")
                .isNotNull();

        long cursorAfterRecovery = recovered.lastSuccessfulToTimeEpochMs();
        LOG.info("[infra-recovery] Waiting for a subsequent cycle to prove the store is not stuck");
        await().atMost(Duration.ofMinutes(2))
                .pollInterval(Duration.ofSeconds(2))
                .ignoreExceptions()
                .until(() -> {
                    WatermarkProbe.Watermark w = watermarkProbe.read().orElse(null);
                    return w != null
                            && w.updatedAt() != null
                            && !w.updatedAt().equals(recovered.updatedAt());
                });

        assertThat(stack.serviceControl().isRunning(ReliabilityStack.BATCH_INDEXING_SERVICE))
                .as("The batch-indexing container must survive a state-index delete mid-cycle")
                .isTrue();

        LOG.info("[infra-recovery] Store keeps writing after recovery — cursor before subsequent cycle was {}, "
                + "after was {}", cursorAfterRecovery,
                watermarkProbe.read().orElseThrow().lastSuccessfulToTimeEpochMs());
    }
}
