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

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.wait.strategy.Wait;

import org.alfresco.elasticsearch.batchindexing.reliability.harness.AlfrescoIndexHelper;
import org.alfresco.elasticsearch.batchindexing.reliability.harness.BaseReliabilityIT;
import org.alfresco.elasticsearch.batchindexing.reliability.harness.DlqProbe;
import org.alfresco.elasticsearch.batchindexing.reliability.harness.ReliabilityStack;
import org.alfresco.elasticsearch.batchindexing.reliability.harness.WatermarkProbe;
import org.alfresco.elasticsearch.batchindexing.reliability.harness.WireMockControl;

/**
 * ACS 4xx on the content-transform endpoint → DLQ visibility.
 * <p>
 * WireMock stands in for ACS and returns {@code 400 Bad Request} for every {@code /alfresco/service/api/solr/textContent} call. Every content-bearing node in the dump hits the 4xx branch in {@code ContentTransformClient}, which surfaces a {@code ContentTransformFailure} rather than the historical silent swallow. The writer forwards each failure through {@code ContentTransformFailureListener}; batch-indexing registers a {@code DlqContentTransformFailureListener} that records the skip with {@code failureStage=CONTENT_WRITER}.
 * <p>
 * Metadata + path indexing run in the same composite writer and are unaffected — those steps still succeed, so the Alfresco index has a non-trivial doc count after the poisoned cycle. That's the "metadata still indexed for that node" half of row 4.
 * <p>
 * <b>Start-order.</b> Same recipe as {@link EsBulkMappingConflictIT}: SIGKILL batch-indexing right after {@link ComposeContainer#start()} returns so its first cycle can't fire against a stub-less WireMock (unstubbed = 404 = wrong failureType). Register the 400 stub, seed the watermark for a full historical sweep, then restart.
 */
@Tag("poison")
public class TransformClientErrorIT extends BaseReliabilityIT
{
    private static final Logger LOG = LoggerFactory.getLogger(TransformClientErrorIT.class);

    private static final String WIREMOCK_SERVICE = "wiremock-1";
    private static final int WIREMOCK_PORT = 8080;

    private static final String TEXT_CONTENT_URL = "/alfresco/service/api/solr/textContent";
    private static final String POLLING_INTERVAL = "5s";
    /** Same value the other single-cycle poison ITs use — sweeps everything back to epoch in one window. */
    private static final String MAX_WINDOW = "876000h";
    private static final Duration DLQ_AVAILABILITY_TIMEOUT = Duration.ofMinutes(3);
    /** Floor for DLQ records — the dump ships ~50 content-bearing rows; 5 stays well below that. */
    private static final long MIN_EXPECTED_DLQ_RECORDS = 5;
    /** Floor for metadata indexed docs — matches the value used in other poison ITs against the same dump. */
    private static final long MIN_ALFRESCO_INDEX_DOCS = 10L;

    private static ReliabilityStack stack;
    private static DlqProbe dlqProbe;
    private static AlfrescoIndexHelper alfrescoIndex;
    private static WireMockControl wireMockControl;

    @BeforeAll
    static void startStack() throws IOException
    {
        stack = ReliabilityStack.builder(LOG, "batch-indexing-reliability-content-poison-compose.yaml")
                .env("CONTENT_POLLING_INTERVAL", POLLING_INTERVAL)
                .env("CONTENT_MAX_WINDOW", MAX_WINDOW)
                .customize(cc -> cc.withExposedService(WIREMOCK_SERVICE, WIREMOCK_PORT,
                        Wait.forHttp("/__admin/health")
                                .forStatusCode(200)
                                .forPort(WIREMOCK_PORT)
                                .withStartupTimeout(Duration.ofMinutes(2))))
                .start();

        LOG.info("[poison] Killing batch-indexing so we can register WireMock stubs before its first cycle");
        stack.serviceControl().kill(ReliabilityStack.BATCH_INDEXING_SERVICE);

        int wireMockPort = stack.compose().getServicePort(WIREMOCK_SERVICE, WIREMOCK_PORT);
        WatermarkProbe watermarkProbe = new WatermarkProbe(stack.openSearch().client());
        dlqProbe = new DlqProbe(stack.openSearch().client());
        alfrescoIndex = new AlfrescoIndexHelper(stack.openSearch().client());
        wireMockControl = new WireMockControl("localhost", wireMockPort);

        registerBadRequestStub();
        watermarkProbe.seedForFullHistoricalScan();
        LOG.info("[poison] Restarting batch-indexing — every content fetch should now hit the 400 stub");
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
    void perNodeContentFailuresLandInDlqWithContentWriterStage() throws IOException
    {
        LOG.info("[poison] Waiting up to {} for DLQ to fill from content-transform 4xx", DLQ_AVAILABILITY_TIMEOUT);
        await().atMost(DLQ_AVAILABILITY_TIMEOUT)
                .pollInterval(Duration.ofSeconds(2))
                .ignoreExceptions()
                .until(() -> dlqProbe.count() >= MIN_EXPECTED_DLQ_RECORDS);

        List<DlqProbe.Entry> entries = dlqProbe.readAll();
        LOG.info("[poison] DLQ contains {} records after the poisoned cycle", entries.size());
        assertThat(entries)
                .as("DLQ should contain at least %d CONTENT_WRITER entries after the poisoned cycle", MIN_EXPECTED_DLQ_RECORDS)
                .hasSizeGreaterThanOrEqualTo((int) MIN_EXPECTED_DLQ_RECORDS);

        DlqProbe.Entry sample = entries.get(0);
        LOG.info("[poison] Sample DLQ entry: {}", sample);

        assertThat(entries).allSatisfy(entry -> {
            assertThat(entry.failureStage())
                    .as("every DLQ record must be tagged CONTENT_WRITER (content-transform bridge)")
                    .isEqualTo("CONTENT_WRITER");
            assertThat(entry.failureType())
                    .as("failureType should carry the HTTP status classifier from the transform client")
                    .isEqualTo("TRANSFORM_HTTP_400");
            assertThat(entry.failureReason()).as("failureReason should carry the ACS response body").isNotBlank();
            assertThat(entry.dbId()).as("dbId comes from the ReindexingEvent that hit the transform failure").isNotNull();
            assertThat(entry.nodeRef()).as("nodeRef is the storeRef+resourceId string the writer built").isNotBlank();
            assertThat(entry.failureCount()).as("first-time failure").isEqualTo(1);
        });

        // Row 4 contract: metadata is still indexed for the failing nodes. Metadata and content
        // are separate writers in the composite; only the content writer sees the 4xx.
        long alfrescoDocs = alfrescoIndex.countDocuments();
        LOG.info("[poison] Alfresco index doc count after poison cycle: {}", alfrescoDocs);
        assertThat(alfrescoDocs)
                .as("metadata for content-failing nodes should still be indexed by the metadata writer")
                .isGreaterThanOrEqualTo(MIN_ALFRESCO_INDEX_DOCS);
    }

    /**
     * Stub the ACS textContent endpoint to return {@code 400 Bad Request} for every request. Matching on URL path (not query) covers all {@code nodeId=} values in one rule.
     */
    private static void registerBadRequestStub()
    {
        wireMockControl.client().register(get(urlPathEqualTo(TEXT_CONTENT_URL))
                .willReturn(aResponse()
                        .withStatus(400)
                        .withHeader("Content-Type", "text/plain")
                        .withBody("stub: transform not supported")));
        LOG.info("[poison] Registered WireMock stub: GET {} -> 400", TEXT_CONTENT_URL);
    }
}
