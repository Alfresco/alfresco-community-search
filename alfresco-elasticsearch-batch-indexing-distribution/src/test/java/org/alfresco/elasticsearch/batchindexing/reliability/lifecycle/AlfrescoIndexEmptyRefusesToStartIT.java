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

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.opensearch.client.opensearch._types.OpenSearchException;
import org.opensearch.client.opensearch.indices.DeleteIndexRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.alfresco.elasticsearch.batchindexing.reliability.harness.AlfrescoIndexHelper;
import org.alfresco.elasticsearch.batchindexing.reliability.harness.BaseReliabilityIT;
import org.alfresco.elasticsearch.batchindexing.reliability.harness.ReliabilityStack;
import org.alfresco.elasticsearch.batchindexing.reliability.harness.WatermarkProbe;

/**
 * The state doc records real indexing progress ({@code lastSuccessfulToTimeEpochMs > 1}) but the Alfresco index has been deleted. Without the opt-in guard the app would scan {@code [T − overlap, now]} and write to an empty index, leaving all older data unindexed — silent data loss with a healthy-looking cursor. With {@code requireAlfrescoIndex=true} the app refuses to start so the operator has to acknowledge the situation.
 * <p>
 * Setup shape (matches how the guard is expected to fire in production):
 * <ol>
 * <li>Start the stack normally; let the batch-indexer run one cycle so a real cursor lands in the state doc.</li>
 * <li>Delete the Alfresco index.</li>
 * <li>Restart the batch-indexer container with {@code requireAlfrescoIndex=true} already configured; the guard fires on re-bootstrap.</li>
 * </ol>
 * <p>
 * Assertions:
 * <ul>
 * <li>Before the restart the app produced a real cursor.</li>
 * <li>After the restart the batch-indexing container exits with a non-zero code.</li>
 * <li>The state doc's cursor is unchanged — the guard fired before any new cycle ran.</li>
 * </ul>
 */
@Tag("lifecycle")
public class AlfrescoIndexEmptyRefusesToStartIT extends BaseReliabilityIT
{
    private static final Logger LOG = LoggerFactory.getLogger(AlfrescoIndexEmptyRefusesToStartIT.class);

    private static ReliabilityStack stack;
    private static AlfrescoIndexHelper alfrescoIndex;
    private static WatermarkProbe watermarkProbe;

    @BeforeAll
    static void startStack()
    {
        // Override pins REQUIREALFRESCOINDEX=true and BOOTSTRAPFROMALFRESCOINDEX=false.
        // With the Alfresco-index seed path off, the presence guard is the only signal
        // driving the second-start behaviour (state doc present + Alfresco index missing
        // → refuse). Compose overlay instead of ${VAR} interpolation because JVM env
        // leaks between ComposeContainer instances in the same reactor invocation would
        // otherwise flip other tests to require Alfresco.
        stack = ReliabilityStack.builder(LOG, "batch-indexing-reliability-lifecycle-compose.yaml")
                .overrideCompose("batch-indexing-reliability-lifecycle-require-alfresco-override.yaml")
                .start();
        alfrescoIndex = new AlfrescoIndexHelper(stack.openSearch().client());
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
    void refusesToStartWhenAlfrescoIndexMissingAndGuardOn() throws IOException
    {
        LOG.info("[lifecycle] Waiting for the initial cycle so a real cursor lands in the state doc");
        await().atMost(Duration.ofMinutes(2))
                .pollInterval(Duration.ofSeconds(1))
                .ignoreExceptions()
                .until(() -> watermarkProbe.read()
                        .map(w -> w.lastSuccessfulToTimeEpochMs() != null && w.lastSuccessfulToTimeEpochMs() > 1L)
                        .orElse(false));

        WatermarkProbe.Watermark cursorBeforeRestart = watermarkProbe.read().orElseThrow();
        LOG.info("[lifecycle] Initial cursor: {}", cursorBeforeRestart);

        LOG.info("[lifecycle] Deleting the Alfresco index");
        try
        {
            stack.openSearch().client().indices().delete(new DeleteIndexRequest.Builder()
                    .index(AlfrescoIndexHelper.INDEX_NAME).build());
        }
        catch (OpenSearchException e)
        {
            if (e.status() != 404)
            {
                throw e;
            }
        }
        assertThat(alfrescoIndex.countDocuments())
                .as("The Alfresco index should be gone before the restart")
                .isZero();

        LOG.info("[lifecycle] Restarting batch-indexing so the presence guard fires on re-bootstrap");
        stack.serviceControl().stop(ReliabilityStack.BATCH_INDEXING_SERVICE);
        stack.serviceControl().start(ReliabilityStack.BATCH_INDEXING_SERVICE);

        await().atMost(Duration.ofMinutes(2))
                .pollInterval(Duration.ofSeconds(2))
                .until(() -> !stack.serviceControl().isRunning(ReliabilityStack.BATCH_INDEXING_SERVICE));

        Integer exitCode = stack.serviceControl().exitCode(ReliabilityStack.BATCH_INDEXING_SERVICE);
        LOG.info("[lifecycle] batch-indexing exit code after restart: {}", exitCode);

        assertThat(exitCode)
                .as("Refuse-to-start must produce a non-zero exit.")
                .isNotNull()
                .isNotZero();

        WatermarkProbe.Watermark stateAfter = watermarkProbe.read().orElseThrow(
                () -> new AssertionError("State doc should remain untouched"));
        assertThat(stateAfter)
                .as("The presence guard must leave the state doc untouched — guard fires before any new cycle runs")
                .isEqualTo(cursorBeforeRestart);
    }
}
