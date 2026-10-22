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

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.time.Duration;

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
 * Short ACS outage → content back-fills on recovery via overlap re-scan.
 * <p>
 * WireMock stubs {@code /alfresco/service/api/solr/textContent} to return {@code 503 Service Unavailable} while batch-indexing runs. The client's 5xx retry filter kicks in twice, then the writer swallows to {@code ContentTransformResult.empty()} — no DLQ entry, no upsert, no {@code CONTENT_INDEXING_LAST_UPDATE} on the ES doc. Metadata + path writers on the same nodes keep working, so the Alfresco index still fills up.
 * <p>
 * After the metadata backlog is drained, the test resets WireMock to {@code 200 OK} with a stub body. Overlap is set to 1 hour — every dump node's commit time is within {@code lastSuccessfulTo - overlap}, so the next cycle re-emits every node into the composite writer and the content step lands on the 200 stub. {@code CONTENT_INDEXING_LAST_UPDATE} now appears on the docs → back-fill observed.
 * <p>
 * <b>Why overlap=1h.</b> Dump commits span roughly 5 minutes ending at 2021-04-21T12:42:30Z; the watermark advances to that max after the first cycle. With overlap=1h every dump node sits inside {@code [max-1h, now]} and gets replayed on the next tick. The plan's "short outage &lt; overlap" contract is what's being validated — see {@link AcsRestLongOutageContentLossIT} for the mirror-image test where overlap is deliberately tighter than the outage.
 * <p>
 * <b>Contract-only tuning.</b> The DLQ assertion at the end is a defensive check: 5xx must never leak into the DLQ (only 4xx does under the new {@code TRANSFORM_HTTP_&lt;status&gt;} bridge). If someone tightens {@code ContentTransformClient} to also DLQ 5xx retries-exhausted, this test will flag it — that's a real contract change that needs a plan revision.
 */
@Tag("infra-recovery")
public class AcsRestOutageIT extends BaseReliabilityIT
{
    private static final Logger LOG = LoggerFactory.getLogger(AcsRestOutageIT.class);

    private static final String WIREMOCK_SERVICE = "wiremock-1";
    private static final int WIREMOCK_PORT = 8080;

    private static final String TEXT_CONTENT_URL = "/alfresco/service/api/solr/textContent";
    private static final String STUB_TEXT_BODY = "recovered content text after outage";
    private static final String POLLING_INTERVAL = "3s";
    /** Wide enough that every dump-era commit stays inside {@code [lastSuccessfulTo - overlap, now]}. */
    private static final String OVERLAP = "1h";
    private static final String MAX_WINDOW = "876000h";
    private static final Duration METADATA_BACKLOG_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration CONTENT_BACKFILL_TIMEOUT = Duration.ofMinutes(2);
    private static final long MIN_ALFRESCO_INDEX_DOCS = 10L;
    /**
     * Content-bearing floor after recovery. The dump has ~50 nodes with {@code cm:content}; five keeps us well below that and above zero.
     */
    private static final long MIN_CONTENT_AFTER_RECOVERY = 5L;

    private static ReliabilityStack stack;
    private static DlqProbe dlqProbe;
    private static AlfrescoIndexHelper alfrescoIndex;
    private static WireMockControl wireMockControl;

    @BeforeAll
    static void startStack() throws IOException
    {
        stack = ReliabilityStack.builder(LOG, "batch-indexing-reliability-content-poison-compose.yaml")
                .env("CONTENT_POLLING_INTERVAL", POLLING_INTERVAL)
                .env("CONTENT_OVERLAP", OVERLAP)
                .env("CONTENT_MAX_WINDOW", MAX_WINDOW)
                .customize(cc -> cc.withExposedService(WIREMOCK_SERVICE, WIREMOCK_PORT,
                        Wait.forHttp("/__admin/health")
                                .forStatusCode(200)
                                .forPort(WIREMOCK_PORT)
                                .withStartupTimeout(Duration.ofMinutes(2))))
                .start();

        LOG.info("[infra-recovery] Killing batch-indexing so we can register the 503 stub before its first cycle");
        stack.serviceControl().kill(ReliabilityStack.BATCH_INDEXING_SERVICE);

        int wireMockPort = stack.compose().getServicePort(WIREMOCK_SERVICE, WIREMOCK_PORT);
        WatermarkProbe watermarkProbe = new WatermarkProbe(stack.openSearch().client());
        dlqProbe = new DlqProbe(stack.openSearch().client());
        alfrescoIndex = new AlfrescoIndexHelper(stack.openSearch().client());
        wireMockControl = new WireMockControl("localhost", wireMockPort);

        registerServiceUnavailableStub();
        watermarkProbe.seedForFullHistoricalScan();
        LOG.info("[infra-recovery] Restarting batch-indexing — content fetches will now hit the 503 stub");
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
    void metadataConvergesDuringOutageAndContentBackFillsAfterRecovery() throws IOException
    {
        LOG.info("[infra-recovery] Waiting for the metadata writer to drain the backlog against the 503 content endpoint");
        await().atMost(METADATA_BACKLOG_TIMEOUT)
                .pollInterval(Duration.ofSeconds(2))
                .until(() -> alfrescoIndex.countDocuments() >= MIN_ALFRESCO_INDEX_DOCS);

        long metadataDuringOutage = alfrescoIndex.countDocuments();
        long contentDuringOutage = alfrescoIndex.countDocumentsWithContentText();
        long dlqDuringOutage = dlqProbe.count();
        LOG.info("[infra-recovery] Mid-outage state: metadataDocs={}, contentText={}, dlq={}",
                metadataDuringOutage, contentDuringOutage, dlqDuringOutage);
        assertThat(contentDuringOutage)
                .as("5xx retries-exhausted must not leak content text into the index during the outage")
                .isZero();
        assertThat(dlqDuringOutage)
                .as("5xx transform failures are swallowed, not DLQ'd — TRANSFORM_HTTP_*** only applies to 4xx")
                .isZero();

        LOG.info("[infra-recovery] Recovering ACS: swapping the 503 stub for 200 with a text body");
        registerSuccessStub();

        await().atMost(CONTENT_BACKFILL_TIMEOUT)
                .pollInterval(Duration.ofSeconds(2))
                .until(() -> alfrescoIndex.countDocumentsWithContentText() >= MIN_CONTENT_AFTER_RECOVERY);

        long metadataAfterRecovery = alfrescoIndex.countDocuments();
        long contentAfterRecovery = alfrescoIndex.countDocumentsWithContentText();
        long dlqAfterRecovery = dlqProbe.count();
        LOG.info("[infra-recovery] Post-recovery state: metadataDocs={}, contentText={}, dlq={}",
                metadataAfterRecovery, contentAfterRecovery, dlqAfterRecovery);
        assertThat(metadataAfterRecovery)
                .as("metadata must not regress after recovery — the overlap re-scan is additive, not destructive")
                .isGreaterThanOrEqualTo(metadataDuringOutage);
        assertThat(dlqAfterRecovery).as("no 4xx were served, so no DLQ entries should ever appear").isZero();
    }

    private static void registerServiceUnavailableStub()
    {
        wireMockControl.client().register(get(urlPathEqualTo(TEXT_CONTENT_URL))
                .willReturn(aResponse()
                        .withStatus(503)
                        .withHeader("Content-Type", "text/plain")
                        .withBody("stub: ACS content service unavailable")));
        LOG.info("[infra-recovery] Registered WireMock stub: GET {} -> 503", TEXT_CONTENT_URL);
    }

    // Higher priority than the 503 stub so it wins the moment it lands, without first removing the 503.
    // Removing then re-adding would briefly leave the URL unmapped, and WireMock's default 404 is a 4xx
    // that would be DLQ'd — spuriously breaking the "no 4xx served" assertion.
    private static void registerSuccessStub()
    {
        wireMockControl.client().register(get(urlPathEqualTo(TEXT_CONTENT_URL))
                .atPriority(1)
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/plain")
                        .withBody(STUB_TEXT_BODY)));
        LOG.info("[infra-recovery] Registered WireMock stub: GET {} -> 200 (text body)", TEXT_CONTENT_URL);
    }
}
