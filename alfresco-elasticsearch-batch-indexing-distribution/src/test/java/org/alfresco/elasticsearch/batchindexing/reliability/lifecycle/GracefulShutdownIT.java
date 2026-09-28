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
 * Graceful shutdown honours SIGTERM within the SmartLifecycle timeout.
 * <p>
 * A {@code docker stop} sends SIGTERM to PID 1. The batch-indexing app's Spring context is registered as a {@link org.springframework.context.SmartLifecycle}, so it gets a chance to drain the in-flight cycle before the runtime exits. The Docker daemon waits 10s (default) before escalating to SIGKILL. If the app respects the signal it exits well under that ceiling with code 143 (JVM default for SIGTERM after shutdown hooks run) or 0 (if the app calls {@code System.exit(0)} explicitly). If it ignores SIGTERM and Docker has to escalate, exit code is 137 — that is the failure signal this test guards.
 */
@Tag("lifecycle")
public class GracefulShutdownIT extends BaseReliabilityIT
{
    private static final Logger LOG = LoggerFactory.getLogger(GracefulShutdownIT.class);

    private static final Duration SHUTDOWN_DEADLINE = Duration.ofSeconds(20);
    // Docker's default SIGTERM->SIGKILL grace is 10s. A drain that finishes
    // meaningfully under that proves the shutdown hook actually ran.
    private static final Duration DRAIN_CEILING = Duration.ofSeconds(9);
    private static final int SIGKILL_EXIT_CODE = 137;

    private static ReliabilityStack stack;
    private static WatermarkProbe watermarkProbe;

    @BeforeAll
    static void startStack() throws IOException
    {
        stack = ReliabilityStack.builder(LOG, "batch-indexing-reliability-lifecycle-compose.yaml").start();
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
    void sigtermExitsCleanlyWithinDeadline() throws IOException
    {
        LOG.info("[lifecycle] Waiting for the first cursor to be written before shutting down");
        await().atMost(Duration.ofMinutes(2))
                .pollInterval(Duration.ofSeconds(1))
                .ignoreExceptions()
                .until(() -> watermarkProbe.read().isPresent());

        Instant sigTermSentAt = Instant.now();
        LOG.info("[lifecycle] Sending SIGTERM to {} at {}", ReliabilityStack.BATCH_INDEXING_SERVICE, sigTermSentAt);
        stack.serviceControl().stop(ReliabilityStack.BATCH_INDEXING_SERVICE);

        await().atMost(SHUTDOWN_DEADLINE)
                .pollInterval(Duration.ofMillis(500))
                .until(() -> !stack.serviceControl().isRunning(ReliabilityStack.BATCH_INDEXING_SERVICE));

        Duration shutdownDuration = Duration.between(sigTermSentAt, Instant.now());
        Integer exitCode = stack.serviceControl().exitCode(ReliabilityStack.BATCH_INDEXING_SERVICE);
        LOG.info("[lifecycle] Container stopped in {} with exit code {}", shutdownDuration, exitCode);

        assertThat(exitCode)
                .as("Exit code %d indicates Docker escalated to SIGKILL because SIGTERM was ignored "
                        + "or the drain overshot the 10s grace window.", exitCode)
                .isNotEqualTo(SIGKILL_EXIT_CODE);

        assertThat(shutdownDuration)
                .as("Drain took %s which is at or past the 10s Docker default grace window — "
                        + "the app almost certainly got escalated to SIGKILL just before we noticed.",
                        shutdownDuration)
                .isLessThan(DRAIN_CEILING);
    }
}
