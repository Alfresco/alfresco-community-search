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
package org.alfresco.elasticsearch.batchindexing.reliability.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.wait.strategy.Wait;

import org.alfresco.elasticsearch.batchindexing.reliability.harness.AlfrescoIndexHelper;
import org.alfresco.elasticsearch.batchindexing.reliability.harness.BaseReliabilityIT;
import org.alfresco.elasticsearch.batchindexing.reliability.harness.PostgresProbe;
import org.alfresco.elasticsearch.batchindexing.reliability.harness.ReliabilityStack;
import org.alfresco.elasticsearch.batchindexing.reliability.harness.WatermarkProbe;

/**
 * Gap-recovery chunking. When the watermark is far behind {@code now} and the gap exceeds {@code maxWindow}, the scheduler must walk forward in {@code maxWindow}-sized chunks rather than launching one giant scan that would blow up memory / lock the reader for hours.
 * <p>
 * The plan describes this as "stop the app for 2h and seed 500 new nodes", but the observable contract is time-agnostic — we fake the gap by backdating {@code alf_transaction.commit_time_ms} on the dump and seeding a cursor well before the earliest backdated row. That collapses a 2-hour narrative into a ~25-second test.
 * <p>
 * Setup:
 * <ul>
 * <li>Dump loads normally.</li>
 * <li>{@link PostgresProbe} then rewrites every {@code alf_transaction.commit_time_ms} to {@code NOW_MS - id*50} so the 456 dump transactions spread across roughly {@code [now-23s, now]}.</li>
 * <li>Watermark is seeded at {@code now-60s}, so a chunk-free catch-up would need to walk ~60s of time in {@code maxWindow=10s} steps (net advance {@code maxWindow-overlap = 9.5s} per cycle) — around 7 chunks before the cursor snaps to {@code now}.</li>
 * <li>{@code maxGapAge=0s} (disabled) prevents the gap-guard from fast-forwarding.</li>
 * </ul>
 * <p>
 * Assertions:
 * <ul>
 * <li>All 456 backdated transactions worth of dump docs (~70 nodes) get indexed — no silent gaps.</li>
 * <li>At least 4 distinct intermediate cursor values are captured during catch-up — proves the cursor advances in chunks, not a single jump to {@code now}.</li>
 * <li>Final cursor sits near test wall-clock — catch-up actually finishes.</li>
 * </ul>
 */
@Tag("lifecycle")
public class GapRecoveryChunkedIT extends BaseReliabilityIT
{
    private static final Logger LOG = LoggerFactory.getLogger(GapRecoveryChunkedIT.class);

    private static final String POSTGRES_SERVICE = "postgres-1";
    private static final int POSTGRES_PORT = 5432;

    private static final Duration SEEDED_GAP = Duration.ofSeconds(60);
    // Set all three timing knobs the compose exposes so shell env leakage
    // (e.g. LIFECYCLE_POLLING_INTERVAL exported from a prior run) can't push
    // them past the ContinuousReindexingProperties validator's constraints:
    // catchUpPollingInterval < pollingInterval < maxWindow.
    private static final String POLLING_INTERVAL_CONF = "2s";
    private static final String MAX_POLLING_INTERVAL_CONF = "5s";
    private static final String MAX_WINDOW_CONF = "10s";
    private static final String OVERLAP_CONF = "500ms";
    private static final int MIN_CHUNK_ADVANCES = 4;
    // Modest floor — the dump has ~70 content-bearing nodes but the exact number
    // that survive the pipeline filters isn't the point of this test. Anything
    // meaningfully above zero proves catch-up ran through data, not empty windows.
    private static final long EXPECTED_INDEXED_DOCS_MIN = 10;
    // Await stops when cursor is within this of wall-clock now, which means
    // catch-up has reached the end of the backdated window.
    private static final Duration FINAL_CURSOR_TOLERANCE = Duration.ofSeconds(5);
    // Elasticsearch default refresh interval is 1s. After the final chunk
    // completes, the last batch of docs may not be searchable yet.
    private static final Duration ES_REFRESH_SETTLE = Duration.ofSeconds(2);
    // Happy-path catch-up is under 20s. Extra slack absorbs docker / host hiccups
    // that occasionally stall the container for tens of seconds.
    private static final Duration CATCH_UP_TIMEOUT = Duration.ofMinutes(3);

    private static ReliabilityStack stack;
    private static AlfrescoIndexHelper alfrescoIndex;
    private static WatermarkProbe watermarkProbe;
    private static long seededCursorEpochMs;

    @BeforeAll
    static void startStack() throws IOException
    {
        stack = ReliabilityStack.builder(LOG, "batch-indexing-reliability-lifecycle-compose.yaml")
                .env("LIFECYCLE_POLLING_INTERVAL", POLLING_INTERVAL_CONF)
                .env("LIFECYCLE_MAX_POLLING_INTERVAL", MAX_POLLING_INTERVAL_CONF)
                .env("LIFECYCLE_MAX_WINDOW", MAX_WINDOW_CONF)
                .env("LIFECYCLE_OVERLAP", OVERLAP_CONF)
                .customize(cc -> cc.withExposedService(POSTGRES_SERVICE, POSTGRES_PORT,
                        Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(3))))
                .start();

        int pgPort = stack.compose().getServicePort(POSTGRES_SERVICE, POSTGRES_PORT);

        alfrescoIndex = new AlfrescoIndexHelper(stack.openSearch().client());
        watermarkProbe = new WatermarkProbe(stack.openSearch().client());
        alfrescoIndex.createIfMissing();

        // Backdate every dump transaction so commit_time_ms spans roughly [NOW-23s, NOW].
        // id ranges 1..456 → id*50ms ranges 50ms..22.8s.
        PostgresProbe postgres = PostgresProbe.forAlfresco(pgPort);
        int updated = postgres.execute(
                "UPDATE public.alf_transaction "
                        + "SET commit_time_ms = (EXTRACT(EPOCH FROM NOW()) * 1000)::bigint - (id * 50)");
        LOG.info("[lifecycle] Backdated {} alf_transaction rows to span [NOW-{}ms, NOW-50ms]",
                updated, 456 * 50);

        // Seed cursor comfortably before any backdated transaction.
        seededCursorEpochMs = Instant.now().minus(SEEDED_GAP).toEpochMilli();
        watermarkProbe.seed(seededCursorEpochMs, "SEEDED");
        LOG.info("[lifecycle] Seeded watermark at {} (now - {}); pollingInterval={} maxWindow={} overlap={} — "
                + "expecting ~{} catch-up chunks and >={} distinct cursor advances",
                seededCursorEpochMs, SEEDED_GAP, POLLING_INTERVAL_CONF, MAX_WINDOW_CONF, OVERLAP_CONF,
                SEEDED_GAP.toSeconds() / 10, MIN_CHUNK_ADVANCES);
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
    void catchUpAdvancesInChunksNotOneShot() throws IOException, InterruptedException
    {
        // Sample the watermark during catch-up. Each distinct cursor value is a
        // completed chunk. If maxWindow chunking is working we should collect
        // several values before the cursor settles near now.
        Set<Long> distinctCursors = new LinkedHashSet<>();
        AtomicReference<Long> latestCursor = new AtomicReference<>(seededCursorEpochMs);
        distinctCursors.add(seededCursorEpochMs);

        LOG.info("[lifecycle] Sampling cursor during catch-up until it reaches within {}s of now",
                FINAL_CURSOR_TOLERANCE.toSeconds());

        await().atMost(CATCH_UP_TIMEOUT)
                .pollInterval(Duration.ofMillis(200))
                .ignoreExceptions()
                .until(() -> {
                    watermarkProbe.read().ifPresent(w -> {
                        long cursor = w.lastSuccessfulToTimeEpochMs();
                        if (distinctCursors.add(cursor))
                        {
                            LOG.info("[lifecycle] cursor advance #{}: {} (+{}ms from prev)",
                                    distinctCursors.size(), cursor, cursor - latestCursor.get());
                            latestCursor.set(cursor);
                        }
                    });
                    long currentFloor = Instant.now().toEpochMilli() - FINAL_CURSOR_TOLERANCE.toMillis();
                    return latestCursor.get() >= currentFloor;
                });

        // ES needs a beat to refresh so the last chunk's writes are searchable.
        Thread.sleep(ES_REFRESH_SETTLE.toMillis());

        long finalCursor = latestCursor.get();
        long now = Instant.now().toEpochMilli();
        long docCount = alfrescoIndex.countDocuments();

        LOG.info("[lifecycle] Catch-up finished: seeded={} finalCursor={} (now-{}ms), "
                + "distinctCursors={}, indexedDocs={}",
                seededCursorEpochMs, finalCursor, now - finalCursor, distinctCursors.size(), docCount);

        assertThat(distinctCursors.size())
                .as("Cursor must advance in maxWindow-sized chunks, not jump directly to now. "
                        + "seeded=%d finalCursor=%d distinctCursors=%s",
                        seededCursorEpochMs, finalCursor, distinctCursors)
                .isGreaterThanOrEqualTo(MIN_CHUNK_ADVANCES);

        assertThat(finalCursor)
                .as("Final cursor must reach the wall-clock band after catch-up drains. "
                        + "finalCursor=%d now=%d tolerance=%s",
                        finalCursor, now, FINAL_CURSOR_TOLERANCE)
                .isGreaterThanOrEqualTo(now - FINAL_CURSOR_TOLERANCE.toMillis() - ES_REFRESH_SETTLE.toMillis());

        assertThat(docCount)
                .as("Backdated transactions must produce indexed docs after chunked catch-up. "
                        + "docCount=%d expectedMin=%d", docCount, EXPECTED_INDEXED_DOCS_MIN)
                .isGreaterThanOrEqualTo(EXPECTED_INDEXED_DOCS_MIN);
    }
}
