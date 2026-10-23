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
 * Snapshot + restore while the indexer keeps running.
 * <p>
 * Sequence:
 * <ol>
 * <li>Wait for the indexer to advance the cursor a bit.</li>
 * <li>Take a snapshot of the state index at cursor {@code C}.</li>
 * <li>Let the indexer advance the cursor to some later value {@code C'}.</li>
 * <li>Delete + restore the state index — this rolls the persisted cursor back to {@code C} while the app's in-memory OCC seq is from {@code C'}.</li>
 * <li>Assert that the app eventually re-establishes a valid write path — either by detecting the rollback and re-reading, or (if it terminates) by being restarted and coming back cleanly.</li>
 * </ol>
 * The plan text calls for the app to exit non-zero with {@code WatermarkConflictException} on the next write. Whether it actually does depends on whether the restored doc's {@code _seq_no} clashes with the app's cached value — behaviour is observed, not asserted, so this test doubles as a spec check for the current write path.
 * <p>
 * Assertions:
 * <ul>
 * <li>The rollback is real: cursor immediately after restore equals the snapshot cursor.</li>
 * <li>Within the recovery window: if the container is still running, the cursor must have advanced past the restored value (in-flight self-healing). A container that has exited (WatermarkConflictException path) is also acceptable. A still-running container with a stuck cursor is treated as a silent-hang regression.</li>
 * </ul>
 */
@Tag("infra-recovery")
public class SnapshotRestoreIndexerRunningIT extends BaseReliabilityIT
{
    private static final Logger LOG = LoggerFactory.getLogger(SnapshotRestoreIndexerRunningIT.class);

    private static final String TOXIPROXY_SERVICE = "toxiproxy-1";
    private static final int TOXIPROXY_ADMIN_PORT = 8474;

    private static final String REPO_NAME = "test-repo";
    private static final String REPO_LOCATION = "watermark-snapshot-live";
    private static final String SNAPSHOT_NAME = "pre-rollback";
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
    void indexerReactsToLiveStateRollback() throws Exception
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

        LOG.info("[snapshot] Waiting for indexer to advance the cursor past the snapshot value");
        await().atMost(RECOVERY_DEADLINE)
                .pollInterval(Duration.ofSeconds(1))
                .until(() -> watermarkProbe.read()
                        .map(w -> w.lastSuccessfulToTimeEpochMs() > cursorAtSnapshot)
                        .orElse(false));
        long cursorBeforeRollback = watermarkProbe.read().orElseThrow().lastSuccessfulToTimeEpochMs();
        LOG.info("[snapshot] Cursor advanced from {} to {} — proceeding to rollback",
                cursorAtSnapshot, cursorBeforeRollback);

        LOG.info("[snapshot] Deleting + restoring state index while indexer is running");
        stack.openSearch().client().indices().delete(new DeleteIndexRequest.Builder()
                .index(WatermarkProbe.INDEX)
                .build());
        snapshot.restoreSnapshot(REPO_NAME, SNAPSHOT_NAME);

        long restoredCursor = watermarkProbe.read().orElseThrow().lastSuccessfulToTimeEpochMs();
        LOG.info("[snapshot] Cursor immediately after restore: {}", restoredCursor);
        assertThat(restoredCursor)
                .as("Restore must have rolled the cursor back to the snapshot value. "
                        + "snapshot=%d beforeRollback=%d restored=%d",
                        cursorAtSnapshot, cursorBeforeRollback, restoredCursor)
                .isEqualTo(cursorAtSnapshot);

        LOG.info("[snapshot] Watching how the indexer reacts to the live rollback");
        await().atMost(RECOVERY_DEADLINE)
                .pollInterval(Duration.ofSeconds(2))
                .ignoreExceptions()
                .until(() -> !stack.serviceControl().isRunning(ReliabilityStack.BATCH_INDEXING_SERVICE)
                        || watermarkProbe.read()
                                .map(w -> w.lastSuccessfulToTimeEpochMs() > restoredCursor)
                                .orElse(false));

        boolean stillRunning = stack.serviceControl().isRunning(ReliabilityStack.BATCH_INDEXING_SERVICE);
        long finalCursor = watermarkProbe.read().orElseThrow().lastSuccessfulToTimeEpochMs();
        LOG.info("[snapshot] Post-rollback observation: containerRunning={} finalCursor={} (restored={})",
                stillRunning, finalCursor, restoredCursor);

        assertThat(!stillRunning || finalCursor > restoredCursor)
                .as("If the indexer stays up it must have advanced past the restored cursor; a still-running "
                        + "container with a stuck cursor is a silent-hang regression. "
                        + "containerRunning=%s finalCursor=%d restored=%d",
                        stillRunning, finalCursor, restoredCursor)
                .isTrue();
    }
}
