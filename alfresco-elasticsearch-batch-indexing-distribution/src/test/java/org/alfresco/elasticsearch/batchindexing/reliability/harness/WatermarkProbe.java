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
package org.alfresco.elasticsearch.batchindexing.reliability.harness;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.OpenSearchException;
import org.opensearch.client.opensearch.core.GetRequest;
import org.opensearch.client.opensearch.core.GetResponse;
import org.opensearch.client.opensearch.core.IndexRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Seeds and reads the batch-indexing watermark document from Elasticsearch.
 *
 * <p>
 * A local view record is used instead of the production {@code WatermarkDocument} to avoid pulling the batch-indexing app + Spring Boot onto the distribution module's test classpath.
 */
public final class WatermarkProbe
{
    /** Index name used by batch-indexing to persist its cursor. */
    public static final String INDEX = "alfresco-reindex-state";
    /** Document id under {@link #INDEX} that holds the {@code reindexByDate} watermark. */
    public static final String DOC_ID = "reindexByDate-watermark";

    private static final Logger LOG = LoggerFactory.getLogger(WatermarkProbe.class);

    private final OpenSearchClient client;

    public WatermarkProbe(OpenSearchClient client)
    {
        this.client = client;
    }

    /**
     * Seed the watermark so batch-indexing runs a full historical scan on its next poll. Uses {@code lastSuccessfulToTimeEpochMs=1L} which is far enough in the past that the scanner will pick up everything in the dump / seed data.
     */
    public void seedForFullHistoricalScan() throws IOException
    {
        seed(1L, "SEEDED");
    }

    /** Seed with an explicit cursor time and status marker. */
    public void seed(long lastSuccessfulToTimeEpochMs, String lastRunStatus) throws IOException
    {
        Map<String, Object> watermark = Map.of(
                "schemaVersion", 1,
                "lastSuccessfulToTimeEpochMs", lastSuccessfulToTimeEpochMs,
                "lastRunStatus", lastRunStatus,
                "updatedAt", Instant.now().toString());

        client.index(IndexRequest.of(r -> r.index(INDEX).id(DOC_ID).document(watermark)));

        LOG.info("[reliability] Seeded watermark in '{}' with lastSuccessfulToTimeEpochMs={} status={}",
                INDEX, lastSuccessfulToTimeEpochMs, lastRunStatus);
    }

    /** Read the current watermark, or empty if the doc / index does not exist yet. */
    public Optional<Watermark> read() throws IOException
    {
        try
        {
            GetResponse<Watermark> response = client.get(
                    new GetRequest.Builder().index(INDEX).id(DOC_ID).build(),
                    Watermark.class);
            return response.found() ? Optional.ofNullable(response.source()) : Optional.empty();
        }
        catch (OpenSearchException e)
        {
            if (e.getMessage() != null && e.getMessage().contains("index_not_found_exception"))
            {
                return Optional.empty();
            }
            throw e;
        }
    }

    /**
     * Test-side view of the watermark document. Only the fields the ITs read are modelled; unknown fields are ignored so schema evolution in the batch-indexing module doesn't silently break test reads. Item-count fields are boxed so tests can distinguish "field missing" from "zero".
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Watermark(
            Integer schemaVersion,
            Long lastSuccessfulToTimeEpochMs,
            String lastRunStatus,
            Long lastRunReadCount,
            Long lastRunWriteCount,
            Long lastRunSkipCount,
            Long lastRunFilterCount,
            String updatedAt)
    {}
}
