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
package org.alfresco.elasticsearch.batchindexing.reliability.lifecycle;

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

import org.alfresco.elasticsearch.batchindexing.reliability.harness.BaseReliabilityIT;
import org.alfresco.elasticsearch.batchindexing.reliability.harness.ReliabilityStack;
import org.alfresco.elasticsearch.batchindexing.reliability.harness.WatermarkProbe;

/**
 * When {@code autoCreateStateIndex=false} and the state index is missing at startup, the app must refuse to start with a non-zero exit rather than silently bootstrap fresh (which would silently drop everything indexed before the state was lost).
 * <p>
 * The "true" branch (default) is already covered end-to-end by {@link ColdStartFirstRunIT} and {@link BothIndicesEmptyFreshBootstrapIT}, both of which start with no state index and confirm the app auto-creates it. We do not ship a separate {@code AutoCreateStateIndexTrueBootstrapsIT} to keep the IT count and Docker cost down.
 * <p>
 * Assertions:
 * <ul>
 * <li>The batch-indexing container exits within a small deadline (does not sit spinning forever).</li>
 * <li>Exit code is non-zero (Spring Boot startup failure).</li>
 * <li>No watermark document appears in Elasticsearch — the app never got past bootstrap.</li>
 * </ul>
 */
@Tag("lifecycle")
public class AutoCreateStateIndexFalseRefusesToStartIT extends BaseReliabilityIT
{
    private static final Logger LOG = LoggerFactory.getLogger(AutoCreateStateIndexFalseRefusesToStartIT.class);

    private static ReliabilityStack stack;
    private static WatermarkProbe watermarkProbe;

    @BeforeAll
    static void startStack()
    {
        stack = ReliabilityStack.builder(LOG, "batch-indexing-reliability-lifecycle-compose.yaml")
                .overrideCompose("batch-indexing-reliability-lifecycle-autocreate-false-override.yaml")
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
    void refusesToStartWhenStateIndexMissingAndAutoCreateOff() throws IOException
    {
        LOG.info("[lifecycle] Waiting for batch-indexing to exit (autoCreateStateIndex=false, state index missing)");
        await().atMost(Duration.ofMinutes(2))
                .pollInterval(Duration.ofSeconds(2))
                .until(() -> !stack.serviceControl().isRunning(ReliabilityStack.BATCH_INDEXING_SERVICE));

        Integer exitCode = stack.serviceControl().exitCode(ReliabilityStack.BATCH_INDEXING_SERVICE);
        LOG.info("[lifecycle] batch-indexing exited with code {}", exitCode);

        assertThat(exitCode)
                .as("Refuse-to-start must produce a non-zero exit so operators / orchestrators notice.")
                .isNotNull()
                .isNotZero();

        assertThat(watermarkProbe.read())
                .as("No watermark doc should have been written — the app never got past bootstrap.")
                .isEmpty();
    }
}
