/*-
 * #%L
 * Alfresco Elasticsearch Connector :: Batch Indexing Module
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
package org.alfresco.elasticsearch.batchindexing.config;

import java.time.Duration;
import jakarta.annotation.PostConstruct;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration properties for continuous reindexing mode (live indexing via polling).
 */
@Getter
@Setter
@ToString
@Configuration
@ConfigurationProperties(prefix = "alfresco.reindex.continuous")
public class ContinuousReindexingProperties
{
    /** Delay between polling cycles under normal (non-catch-up) operation. */
    private Duration pollingInterval = Duration.ofSeconds(30);

    /** Maximum delay between polling cycles when exponential backoff is applied after failures. */
    private Duration maxPollingInterval = Duration.ofMinutes(5);

    /** Delay between consecutive chunks when draining a catch-up backlog. */
    private Duration catchUpPollingInterval = Duration.ofSeconds(1);

    /** How far to look back behind the last watermark to account for late DB writes. */
    private Duration overlap = Duration.ofMinutes(10);

    /** Name of the Elasticsearch index used to persist the reindexing watermark state. */
    private String watermarkIndexName = "alfresco-reindex-state";

    /** Maximum time window per reindexing chunk. */
    private Duration maxWindow = Duration.ofMinutes(30);

    /**
     * Maximum age of the watermark before it is considered a gap and fast-forwarded. {@link Duration#ZERO} disables the gap guard.
     */
    private Duration maxGapAge = Duration.ofHours(24);

    /** Maximum number of retry attempts for watermark read/write operations against Elasticsearch. */
    private int watermarkRetryMaxAttempts = 3;

    /** Initial delay between watermark read/write operation retry attempts. */
    private Duration watermarkRetryDelay = Duration.ofSeconds(2);

    /**
     * Maximum time between two signs of life from the scheduler before the app reports itself DOWN on the liveness probe. Signs of life are cycle start, cycle finish, and each committed chunk during a cycle. Must be greater than worst-case single-chunk wall-clock time so that a legitimate exponential backoff after failures does not trigger a spurious DOWN.
     */
    private Duration stuckThreshold = Duration.ofMinutes(10);

    /**
     * Report DOWN once this many cycles have failed in a row. The idle-based check cannot see this: a failing cycle still ticks activity, and the retry backoff caps below {@code stuckThreshold}, so a pod that indexes nothing stays UP indefinitely. Zero disables the check.
     */
    private int unhealthyAfterConsecutiveFailures = 3;

    /** Whether the continuous reindexing service starts automatically with the Spring context. */
    private boolean autoStart = true;

    /**
     * When {@code true} (default), the app creates the watermark state index on startup if it is missing. When {@code false}, the app refuses to start if the state index is missing and requires the operator to restore it from backup or seed a watermark doc manually. Guards against silent first-run bootstrap when the state has been lost after previously running, at the cost of manual intervention.
     */
    private boolean autoCreateStateIndex = true;

    /**
     * When {@code true}, the app refuses to start if the Alfresco index is missing while the state doc still records a non-sentinel {@code lastSuccessfulToTimeEpochMs}. Default {@code false} tolerates a wiped Alfresco index (legitimate on fresh installs). Turn on when an operator wants to catch the "someone dropped the Alfresco index while the batch-indexer's cursor looks healthy" scenario before hours of writes stream past a healthy-looking watermark.
     */
    private boolean requireAlfrescoIndex;

    /**
     * When {@code true} (default), on missing state doc the app queries {@code MAX(METADATA_INDEXING_LAST_UPDATE)} on the Alfresco index and bootstraps the cursor to {@code max − overlap} instead of the first-run default {@code now − overlap}. Near-zero silent data loss when the state has been lost while the Alfresco index is still alive; falls through to the first-run default if the Alfresco index is missing or empty.
     */
    private boolean bootstrapFromAlfrescoIndex = true;

    /**
     * Smart-seed, opt-in (default {@code false}). On missing state doc, seed the cursor from the <em>DB's</em> earliest node commit time ({@code min − overlap}) instead of {@link #bootstrapFromAlfrescoIndex}'s ES-max-based seed, so normal catch-up walks forward and backfills everything. Takes priority over {@code bootstrapFromAlfrescoIndex} when both are enabled; falls through to it if the database has no nodes. Pair with {@code maxGapAge=0} for a full backfill on an empty watermark.
     */
    private boolean seedFromEarliest;

    @PostConstruct
    public void validate()
    {
        validateMaxWindow();
        validateCatchUpPollingInterval();
        validateMaxGapAge();
        validateWatermarkRetryMaxAttempts();
        validateWatermarkRetryDelay();
        validateStuckThreshold();
        validateUnhealthyAfterConsecutiveFailures();
    }

    private void validateMaxWindow()
    {
        if (maxWindow.compareTo(pollingInterval) <= 0)
        {
            throw new IllegalArgumentException(String.format(
                    "alfresco.reindex.continuous.maxWindow (%s) must be greater than "
                            + "alfresco.reindex.continuous.pollingInterval (%s), otherwise the job can never catch up.",
                    maxWindow, pollingInterval));
        }
    }

    private void validateCatchUpPollingInterval()
    {
        if (catchUpPollingInterval.compareTo(pollingInterval) >= 0)
        {
            throw new IllegalArgumentException(String.format(
                    "alfresco.reindex.continuous.catchUpPollingInterval (%s) must be less than "
                            + "alfresco.reindex.continuous.pollingInterval (%s), otherwise catch-up mode offers no benefit.",
                    catchUpPollingInterval, pollingInterval));
        }
    }

    private void validateMaxGapAge()
    {
        if (maxGapAge.isNegative())
        {
            throw new IllegalArgumentException(String.format(
                    "alfresco.reindex.continuous.maxGapAge must be >= 0 but was %s", maxGapAge));
        }
    }

    private void validateUnhealthyAfterConsecutiveFailures()
    {
        if (unhealthyAfterConsecutiveFailures < 0)
        {
            throw new IllegalArgumentException(String.format(
                    "alfresco.reindex.continuous.unhealthyAfterConsecutiveFailures must be >= 0 but was %d. "
                            + "Use 0 to disable the check; a negative value would disable it silently.",
                    unhealthyAfterConsecutiveFailures));
        }
    }

    private void validateWatermarkRetryMaxAttempts()
    {
        if (watermarkRetryMaxAttempts < 1)
        {
            throw new IllegalArgumentException(String.format(
                    "alfresco.reindex.continuous.watermarkRetryMaxAttempts must be >= 1 but was %d", watermarkRetryMaxAttempts));
        }
    }

    private void validateWatermarkRetryDelay()
    {
        if (watermarkRetryDelay.isNegative())
        {
            throw new IllegalArgumentException(String.format(
                    "alfresco.reindex.continuous.watermarkRetryDelay must be >= 0 but was %s", watermarkRetryDelay));
        }
    }

    private void validateStuckThreshold()
    {
        if (stuckThreshold.compareTo(maxPollingInterval) <= 0)
        {
            throw new IllegalArgumentException(String.format(
                    "alfresco.reindex.continuous.stuckThreshold (%s) must be greater than "
                            + "alfresco.reindex.continuous.maxPollingInterval (%s), otherwise a legitimate "
                            + "backoff after failures will trigger a spurious DOWN on the liveness probe.",
                    stuckThreshold, maxPollingInterval));
        }
    }
}
