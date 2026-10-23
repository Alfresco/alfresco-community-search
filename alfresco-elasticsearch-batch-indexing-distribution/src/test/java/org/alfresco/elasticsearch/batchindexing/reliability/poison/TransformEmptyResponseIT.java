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
package org.alfresco.elasticsearch.batchindexing.reliability.poison;

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
 * ACS 200 empty body on the content-transform endpoint → <b>no</b> DLQ entry.
 * <p>
 * Documents the contract that a 200-empty response is "no content this cycle", not a failure. WireMock returns {@code 200 OK} with an empty body for every {@code /alfresco/service/api/solr/textContent} call; {@code ContentTransformClient} returns a plain empty result (neither text nor failure), the writer logs a debug line and moves on, and the {@code ContentTransformFailureListener} bridge stays quiet — so the DLQ index never sees a per-node record from the content step.
 * <p>
 * Metadata and path writers run in the same composite and are unaffected, so the Alfresco index still gets populated — this half of the assertion catches regressions where "empty body" is accidentally reclassified as a failure that fails the whole item.
 * <p>
 * Startup mirrors {@link TransformClientErrorIT} exactly (kill → stub → seed → restart) so the two tests share reasoning about race hazards. Difference is one stub value and the inverted DLQ assertion.
 */
@Tag("poison")
public class TransformEmptyResponseIT extends BaseReliabilityIT
{
    private static final Logger LOG = LoggerFactory.getLogger(TransformEmptyResponseIT.class);

    private static final String WIREMOCK_SERVICE = "wiremock-1";
    private static final int WIREMOCK_PORT = 8080;

    private static final String TEXT_CONTENT_URL = "/alfresco/service/api/solr/textContent";
    private static final String POLLING_INTERVAL = "5s";
    private static final String MAX_WINDOW = "876000h";
    /**
     * How long we wait for the cycle to complete before asserting the DLQ is empty. Fixed observation window (not "wait for a signal") because "no DLQ record" is the assertion — Awaitility can't wait for the absence of an event.
     */
    private static final Duration CYCLE_OBSERVATION_WINDOW = Duration.ofSeconds(90);
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

        registerEmptyBodyStub();
        watermarkProbe.seedForFullHistoricalScan();
        LOG.info("[poison] Restarting batch-indexing — every content fetch should now hit the 200-empty stub");
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
    void emptyBodyDoesNotProduceDlqRecordButMetadataStillIndexed() throws IOException
    {
        LOG.info("[poison] Waiting up to {} for the metadata writer to populate the Alfresco index", CYCLE_OBSERVATION_WINDOW);
        await().atMost(CYCLE_OBSERVATION_WINDOW)
                .pollInterval(Duration.ofSeconds(2))
                .ignoreExceptions()
                .until(() -> alfrescoIndex.countDocuments() >= MIN_ALFRESCO_INDEX_DOCS);

        long alfrescoDocs = alfrescoIndex.countDocuments();
        LOG.info("[poison] Alfresco index doc count: {}", alfrescoDocs);
        assertThat(alfrescoDocs)
                .as("metadata should still be indexed even though the content endpoint returns empty bodies")
                .isGreaterThanOrEqualTo(MIN_ALFRESCO_INDEX_DOCS);

        // Absence assertion — nothing to wait for, so sleep out the remainder of the observation
        // window and read once. If the writer had misclassified empty-body as a failure, the DLQ
        // would already have entries by now.
        try
        {
            Thread.sleep(CYCLE_OBSERVATION_WINDOW.dividedBy(3).toMillis());
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the observation window", e);
        }
        long dlqCount = dlqProbe.count();
        LOG.info("[poison] DLQ count after the empty-body cycle: {}", dlqCount);
        assertThat(dlqCount)
                .as("empty transform bodies must NOT produce DLQ records — empty is not a failure")
                .isZero();
    }

    private static void registerEmptyBodyStub()
    {
        wireMockControl.client().register(get(urlPathEqualTo(TEXT_CONTENT_URL))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/plain")
                        .withBody("")));
        LOG.info("[poison] Registered WireMock stub: GET {} -> 200 (empty body)", TEXT_CONTENT_URL);
    }
}
