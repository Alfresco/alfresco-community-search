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
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.opensearch.client.opensearch.indices.DeleteIndexRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.alfresco.elasticsearch.batchindexing.reliability.harness.BaseReliabilityIT;
import org.alfresco.elasticsearch.batchindexing.reliability.harness.DistributionEnv;
import org.alfresco.elasticsearch.batchindexing.reliability.harness.ReliabilityStack;
import org.alfresco.elasticsearch.batchindexing.reliability.harness.SnapshotHelper;
import org.alfresco.elasticsearch.batchindexing.reliability.harness.WatermarkProbe;

/**
 * Snapshot + restore with the indexer stopped.
 * <p>
 * Sequence:
 * <ol>
 * <li>Let the indexer run to establish a cursor and capture its value.</li>
 * <li>Take a snapshot of the state index.</li>
 * <li>Stop the batch-indexing container so no writes race the restore.</li>
 * <li>Delete the state index (simulates corruption or accidental drop).</li>
 * <li>Restore the snapshot.</li>
 * <li>Restart the batch-indexing container.</li>
 * <li>Assert the app picks the restored cursor up and advances from there.</li>
 * </ol>
 * The "no data loss" check from the plan is not asserted here because there's no seeded DB write to lose — the fixture's DB dump is old enough that no txn falls into any current scan window.
 */
@Tag("infra-recovery")
public class SnapshotRestoreIndexerStoppedIT extends BaseReliabilityIT
{
    private static final Logger LOG = LoggerFactory.getLogger(SnapshotRestoreIndexerStoppedIT.class);

    private static final String TOXIPROXY_SERVICE = "toxiproxy-1";
    private static final int TOXIPROXY_ADMIN_PORT = 8474;

    private static final String REPO_NAME = "test-repo";
    private static final String REPO_LOCATION = "watermark-snapshot";
    private static final String SNAPSHOT_NAME = "pre-corruption";
    private static final Duration RECOVERY_DEADLINE = Duration.ofMinutes(2);

    private static ReliabilityStack stack;
    private static WatermarkProbe watermarkProbe;
    private static SnapshotHelper snapshot;

    @BeforeAll
    static void startStack() throws IOException
    {
        Path toxiproxyConfig = DistributionEnv.toxiproxyConfigPath();

        stack = ReliabilityStack.builder(LOG, "batch-indexing-reliability-infra-recovery-compose.yaml")
                .env("TOXIPROXY_CONFIG_PATH", toxiproxyConfig.toString())
                .customize(cc -> cc.withExposedService(TOXIPROXY_SERVICE, TOXIPROXY_ADMIN_PORT))
                .start();
        watermarkProbe = new WatermarkProbe(stack.openSearch().client());
        snapshot = new SnapshotHelper(stack.openSearch().restClient());
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
    void restoredSnapshotIsUsedAsResumePoint() throws Exception
    {
        LOG.info("[snapshot] Waiting for first cursor before snapshotting");
        // ignoreExceptions to ride out shard RECOVERING on the freshly-created state index —
        // the first poll after ES start-up can 503 for a few hundred ms before shards settle.
        await().atMost(Duration.ofMinutes(2))
                .pollInterval(Duration.ofSeconds(1))
                .ignoreExceptions()
                .until(() -> watermarkProbe.read().isPresent());
        long cursorAtSnapshot = watermarkProbe.read().orElseThrow().lastSuccessfulToTimeEpochMs();
        LOG.info("[snapshot] Cursor at snapshot time: {}", cursorAtSnapshot);

        snapshot.registerFsRepository(REPO_NAME, REPO_LOCATION);
        snapshot.takeSnapshot(REPO_NAME, SNAPSHOT_NAME, WatermarkProbe.INDEX);

        LOG.info("[snapshot] Stopping batch-indexing so it doesn't race the restore");
        stack.serviceControl().stop(ReliabilityStack.BATCH_INDEXING_SERVICE);
        await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofSeconds(1))
                .until(() -> !stack.serviceControl().isRunning(ReliabilityStack.BATCH_INDEXING_SERVICE));

        LOG.info("[snapshot] Deleting state index '{}' before restore", WatermarkProbe.INDEX);
        stack.openSearch().client().indices().delete(new DeleteIndexRequest.Builder()
                .index(WatermarkProbe.INDEX)
                .build());

        LOG.info("[snapshot] Restoring snapshot");
        snapshot.restoreSnapshot(REPO_NAME, SNAPSHOT_NAME);

        long restoredCursor = watermarkProbe.read().orElseThrow().lastSuccessfulToTimeEpochMs();
        LOG.info("[snapshot] Cursor after restore: {} (matches snapshot? {})",
                restoredCursor, restoredCursor == cursorAtSnapshot);
        assertThat(restoredCursor)
                .as("Restored cursor should match the snapshot's cursor. snapshot=%d restored=%d",
                        cursorAtSnapshot, restoredCursor)
                .isEqualTo(cursorAtSnapshot);

        LOG.info("[snapshot] Restarting batch-indexing to resume from the restored cursor");
        stack.serviceControl().start(ReliabilityStack.BATCH_INDEXING_SERVICE);
        await().atMost(Duration.ofMinutes(1))
                .pollInterval(Duration.ofSeconds(1))
                .until(() -> stack.serviceControl().isRunning(ReliabilityStack.BATCH_INDEXING_SERVICE));

        LOG.info("[snapshot] Waiting for the cursor to advance past the restored value");
        await().atMost(RECOVERY_DEADLINE)
                .pollInterval(Duration.ofSeconds(2))
                .ignoreExceptions()
                .until(() -> watermarkProbe.read()
                        .map(w -> w.lastSuccessfulToTimeEpochMs() > restoredCursor)
                        .orElse(false));

        long cursorAfterResume = watermarkProbe.read().orElseThrow().lastSuccessfulToTimeEpochMs();
        LOG.info("[snapshot] Cursor after resume: {} (advanced {}ms from restored)",
                cursorAfterResume, cursorAfterResume - restoredCursor);
        assertThat(cursorAfterResume).isGreaterThan(restoredCursor);
    }
}
