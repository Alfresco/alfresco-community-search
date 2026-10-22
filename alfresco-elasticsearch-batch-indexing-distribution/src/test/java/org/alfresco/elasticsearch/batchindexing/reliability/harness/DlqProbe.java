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
package org.alfresco.elasticsearch.batchindexing.reliability.harness;

import java.io.IOException;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.OpenSearchException;
import org.opensearch.client.opensearch.core.CountResponse;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.core.search.Hit;

/**
 * Reads the DLQ index written by the batch-indexing app. Kept alongside {@link WatermarkProbe} so poison / DLQ tests don't have to build search requests by hand.
 * <p>
 * Uses a local view record instead of the app's {@code DeadLetterDocument} to avoid dragging Spring Boot onto the distribution module's test classpath.
 */
public final class DlqProbe
{
    /** Default DLQ index name — matches {@code alfresco.reindex.dead-letter.indexName} in the batch-indexing application properties. */
    public static final String INDEX = "alfresco-reindex-dead-letter";

    private final OpenSearchClient client;

    public DlqProbe(OpenSearchClient client)
    {
        this.client = client;
    }

    /** Total DLQ record count; returns 0 if the index doesn't exist yet. */
    public long count() throws IOException
    {
        try
        {
            CountResponse response = client.count(c -> c.index(INDEX));
            return response.count();
        }
        catch (OpenSearchException e)
        {
            if (e.getMessage() != null && e.getMessage().contains("index_not_found_exception"))
            {
                return 0;
            }
            throw e;
        }
    }

    /** All DLQ records currently in the index. Returns an empty list if the index is missing. */
    public List<Entry> readAll() throws IOException
    {
        try
        {
            SearchResponse<Entry> response = client.search(
                    s -> s.index(INDEX).size(1000),
                    Entry.class);
            return response.hits().hits().stream()
                    .map(Hit::source)
                    .filter(java.util.Objects::nonNull)
                    .toList();
        }
        catch (OpenSearchException e)
        {
            if (e.getMessage() != null && e.getMessage().contains("index_not_found_exception"))
            {
                return List.of();
            }
            throw e;
        }
    }

    /**
     * Test-side view of a DLQ record. Only the fields the ITs assert on are modelled; unknown fields are ignored so schema evolution in batch-indexing doesn't silently break test reads.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Entry(
            Long dbId,
            String nodeRef,
            Long sourceCommitTimeMs,
            String firstFailedAt,
            String lastFailedAt,
            Integer failureCount,
            String failureStage,
            String failureType,
            String failureReason)
    {}
}
