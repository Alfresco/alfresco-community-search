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
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.opensearch.client.opensearch._types.aggregations.Aggregate;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.indices.DeleteIndexRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.alfresco.elasticsearch.batchindexing.reliability.harness.AlfrescoIndexHelper;
import org.alfresco.elasticsearch.batchindexing.reliability.harness.BaseReliabilityIT;
import org.alfresco.elasticsearch.batchindexing.reliability.harness.ReliabilityStack;
import org.alfresco.elasticsearch.batchindexing.reliability.harness.WatermarkProbe;

/**
 * On a missing state doc but a populated Alfresco index, the app seeds its cursor from {@code MAX(METADATA_INDEXING_LAST_UPDATE) − overlap} instead of the first-run default ({@code now − overlap}). Near-zero silent data loss when the state doc gets lost after the app has been indexing for a while.
 * <p>
 * Setup:
 * <ol>
 * <li>Start the stack with a huge overlap so the first cycle scans back far enough to index all the historical dump data.</li>
 * <li>Delete the state index once the Alfresco index is populated.</li>
 * <li>Restart batch-indexing with {@code bootstrapFromAlfrescoIndex=true}: state absent + Alfresco populated → seed from MAX.</li>
 * </ol>
 * <p>
 * Assertions:
 * <ul>
 * <li>The Alfresco index has real {@code METADATA_INDEXING_LAST_UPDATE} data before the restart (otherwise there is nothing to seed from and the app falls through to first-run bootstrap, making the test moot).</li>
 * <li>The container logs the {@code Cursor bootstrapped from Alfresco index: seeded watermark from MAX(...)} line on restart. That's the only stable signal — the seeded cursor value gets overwritten within milliseconds by the first cycle, so a value-based assertion races with normal operation.</li>
 * </ul>
 */
@Tag("lifecycle")
public class BootstrapFromAlfrescoIndexIT extends BaseReliabilityIT
{
    private static final Logger LOG = LoggerFactory.getLogger(BootstrapFromAlfrescoIndexIT.class);

    private static final Pattern SEED_LOG = Pattern.compile("Cursor bootstrapped from Alfresco index: seeded watermark from MAX");

    private static ReliabilityStack stack;
    private static WatermarkProbe watermarkProbe;

    @BeforeAll
    static void startStack()
    {
        // Override compose pins BOOTSTRAPFROMALFRESCOINDEX=true plus OVERLAP and MAXWINDOW
        // large enough for the first cycle to sweep the whole historical dump into the
        // Alfresco index. Compose overlay instead of ${VAR} interpolation because JVM env
        // vars from prior test classes in the same reactor invocation would otherwise leak
        // in and flip flags that this test relies on being defaulted.
        stack = ReliabilityStack.builder(LOG, "batch-indexing-reliability-lifecycle-compose.yaml")
                .overrideCompose("batch-indexing-reliability-lifecycle-bootstrap-alfresco-override.yaml")
                .start();
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
    void seedsCursorFromAlfrescoIndexMaxOnMissingState() throws IOException
    {
        LOG.info("[lifecycle] Waiting for MAX(METADATA_INDEXING_LAST_UPDATE) to appear on the Alfresco index");
        await().atMost(Duration.ofMinutes(3))
                .pollInterval(Duration.ofSeconds(2))
                .ignoreExceptions()
                .until(() -> {
                    Long m = queryAlfrescoMaxMetadataTimestampOrNull();
                    return m != null && m > 0L;
                });

        long alfrescoMax = queryAlfrescoMaxMetadataTimestampOrNull();
        assertThat(alfrescoMax)
                .as("MAX(METADATA_INDEXING_LAST_UPDATE) on the Alfresco index must be a real epoch-ms value")
                .isPositive();
        LOG.info("[lifecycle] Alfresco index MAX(METADATA_INDEXING_LAST_UPDATE) = {}", alfrescoMax);

        // Stop the container BEFORE deleting the state index. The first-boot cycle is often
        // still running when the test reaches this point; if we delete first, the cycle can
        // complete a few hundred ms later and rewrite the state doc — the restarted container
        // then sees a populated state doc and skips the bootstrap-from-Alfresco path entirely.
        LOG.info("[lifecycle] Stopping batch-indexing so the running cycle can't rewrite the state doc after our delete");
        stack.serviceControl().stop(ReliabilityStack.BATCH_INDEXING_SERVICE);
        await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofSeconds(1))
                .until(() -> !stack.serviceControl().isRunning(ReliabilityStack.BATCH_INDEXING_SERVICE));

        LOG.info("[lifecycle] Deleting state index so the Alfresco-index seed path fires on restart");
        stack.openSearch().client().indices().delete(new DeleteIndexRequest.Builder()
                .index(WatermarkProbe.INDEX).build());
        assertThat(watermarkProbe.read()).isEmpty();

        LOG.info("[lifecycle] Restarting batch-indexing so bootstrap runs against the populated Alfresco index");
        stack.serviceControl().start(ReliabilityStack.BATCH_INDEXING_SERVICE);
        await().atMost(Duration.ofMinutes(1))
                .pollInterval(Duration.ofSeconds(1))
                .until(() -> stack.serviceControl().isRunning(ReliabilityStack.BATCH_INDEXING_SERVICE));

        // Poll docker logs directly rather than via a log consumer — TC log consumers do
        // not follow across a stop/start. The seeded cursor value gets overwritten within
        // milliseconds by the first cycle so a value-based assertion races with normal
        // operation; the log line is the stable signal.
        await().atMost(Duration.ofMinutes(3))
                .pollInterval(Duration.ofSeconds(2))
                .ignoreExceptions()
                .until(() -> SEED_LOG.matcher(stack.serviceControl().readContainerLogs(ReliabilityStack.BATCH_INDEXING_SERVICE)).find());
    }

    private Long queryAlfrescoMaxMetadataTimestampOrNull() throws IOException
    {
        SearchRequest request = new SearchRequest.Builder()
                .index(AlfrescoIndexHelper.INDEX_NAME)
                .size(0)
                .aggregations("max_ts", a -> a.max(m -> m.field("METADATA_INDEXING_LAST_UPDATE")))
                .build();
        SearchResponse<Void> response = stack.openSearch().client().search(request, Void.class);
        Aggregate agg = response.aggregations().get("max_ts");
        if (agg == null || !agg.isMax())
        {
            return null;
        }
        Double value = agg.max().value();
        return value == null ? null : value.longValue();
    }
}
