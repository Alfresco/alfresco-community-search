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
 * Long ACS outage → documented silent content loss beyond the overlap window.
 * <p>
 * Mirror of {@link AcsRestOutageIT} with two deliberate parameter flips:
 * <ul>
 * <li>{@code overlap=5s} — tight enough that once the watermark advances past the dump's max commit time, essentially no historical node stays in the re-scan window.</li>
 * <li>The 503 stub is held long enough for several polling cycles to complete and advance the watermark before the switch to 200. That's the "outage &gt; overlap" precondition.</li>
 * </ul>
 * Under those conditions the metadata + path writers happily commit the whole dump on cycle 1 (503 on content is swallowed to empty, watermark advances), and the recovery switch to 200 only helps future commits — not the ones that already fell out of {@code [lastSuccessfulTo - overlap, now]}.
 * <p>
 * <b>This is a spec test, not a bug hunt.</b> Content indexing is best-effort secondary indexing: correct for outages shorter than the overlap window, silent content loss for outages longer than it. Deleting or fixing the loss (e.g. a periodic full-history re-scan for missing-content docs) would be a design change; this test would need updating in lockstep.
 * <p>
 * The assertion is deliberately loose: we only require that {@code CONTENT_INDEXING_LAST_UPDATE} coverage stays well under the metadata doc count after "recovery". Exact numbers depend on the dump's commit-time distribution — see the class-JavaDoc of the sibling test for the timestamp math.
 */
@Tag("infra-recovery")
public class AcsRestLongOutageContentLossIT extends BaseReliabilityIT
{
    private static final Logger LOG = LoggerFactory.getLogger(AcsRestLongOutageContentLossIT.class);

    private static final String WIREMOCK_SERVICE = "wiremock-1";
    private static final int WIREMOCK_PORT = 8080;

    private static final String TEXT_CONTENT_URL = "/alfresco/service/api/solr/textContent";
    private static final String STUB_TEXT_BODY = "recovered content text after long outage";
    private static final String POLLING_INTERVAL = "3s";
    /** Deliberately short — this is the "outage &gt; overlap" case. */
    private static final String OVERLAP = "5s";
    private static final String MAX_WINDOW = "876000h";
    private static final Duration METADATA_BACKLOG_TIMEOUT = Duration.ofMinutes(2);
    /** Long enough that a few polling cycles complete and the watermark moves past the dump max. */
    private static final Duration OUTAGE_HOLD_AFTER_METADATA = Duration.ofSeconds(30);
    /** Long enough for post-recovery cycles to run without letting the test drag on forever. */
    private static final Duration RECOVERY_OBSERVATION = Duration.ofSeconds(45);
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
        LOG.info("[infra-recovery] Restarting batch-indexing with overlap={} — outage will span multiple cycles",
                OVERLAP);
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
    void contentDoesNotBackFillForNodesCommittedOutsideOverlap() throws IOException
    {
        LOG.info("[infra-recovery] Waiting for the metadata writer to fill the alfresco index during the outage");
        await().atMost(METADATA_BACKLOG_TIMEOUT)
                .pollInterval(Duration.ofSeconds(2))
                .until(() -> alfrescoIndex.countDocuments() >= MIN_ALFRESCO_INDEX_DOCS);

        long metadataDuringOutage = alfrescoIndex.countDocuments();
        long contentDuringOutage = alfrescoIndex.countDocumentsWithContentText();
        LOG.info("[infra-recovery] Mid-outage state: metadataDocs={}, contentText={}",
                metadataDuringOutage, contentDuringOutage);
        assertThat(contentDuringOutage)
                .as("content text must not be indexed while the 503 stub is active")
                .isZero();

        // Hold the outage past overlap. Sleep, not Awaitility — there's no signal to wait for
        // besides "enough time has passed for the watermark to advance and drop everything out of
        // the re-scan window".
        LOG.info("[infra-recovery] Holding the outage for {} so the watermark advances past overlap={}",
                OUTAGE_HOLD_AFTER_METADATA, OVERLAP);
        sleep(OUTAGE_HOLD_AFTER_METADATA);

        LOG.info("[infra-recovery] Recovering ACS: swapping the 503 stub for 200 with a text body");
        registerSuccessStub();

        LOG.info("[infra-recovery] Observing post-recovery for {}", RECOVERY_OBSERVATION);
        sleep(RECOVERY_OBSERVATION);

        long metadataAfterRecovery = alfrescoIndex.countDocuments();
        long contentAfterRecovery = alfrescoIndex.countDocumentsWithContentText();
        long dlqAfterRecovery = dlqProbe.count();
        LOG.info("[infra-recovery] Post-recovery state: metadataDocs={}, contentText={}, dlq={}",
                metadataAfterRecovery, contentAfterRecovery, dlqAfterRecovery);
        assertThat(metadataAfterRecovery)
                .as("metadata must survive the outage — the metadata bulk writer path is independent of content")
                .isGreaterThanOrEqualTo(metadataDuringOutage);
        assertThat(contentAfterRecovery)
                .as("content backfill must NOT reach the whole set — loss beyond overlap is the best-effort contract")
                .isLessThan(metadataAfterRecovery / 2);
        assertThat(dlqAfterRecovery).as("no 4xx were served, so no DLQ entries should ever appear").isZero();
    }

    private static void sleep(Duration duration)
    {
        try
        {
            Thread.sleep(duration.toMillis());
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while sleeping", e);
        }
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
