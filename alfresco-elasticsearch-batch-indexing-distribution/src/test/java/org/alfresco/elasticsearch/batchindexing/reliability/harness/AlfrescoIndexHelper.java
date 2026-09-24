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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.OpenSearchException;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.opensearch.core.CountResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.alfresco.indexing.shared.ElasticIndexService;

/**
 * Pre-creates the Alfresco index with the shared schema and counts docs in it.
 */
public final class AlfrescoIndexHelper
{
    /** Default Alfresco index name used across the whole connector. */
    public static final String INDEX_NAME = "alfresco";

    private static final Logger LOG = LoggerFactory.getLogger(AlfrescoIndexHelper.class);
    private static final String SCHEMA_RESOURCE = "/elasticsearch/conf/schema.json";

    private final OpenSearchClient client;
    private final ElasticIndexService indexService;

    public AlfrescoIndexHelper(OpenSearchClient client)
    {
        this.client = client;
        this.indexService = new ElasticIndexService(client);
    }

    /**
     * Create the {@link #INDEX_NAME} index if it doesn't exist, using the schema from the {@code live-indexing-shared-test} module. No-op if already there.
     */
    public void createIfMissing() throws IOException
    {
        if (indexService.existsIndex(INDEX_NAME))
        {
            return;
        }
        try (InputStream is = getClass().getResourceAsStream(SCHEMA_RESOURCE))
        {
            if (is == null)
            {
                throw new IllegalStateException(
                        "schema.json missing from classpath (expected from live-indexing-shared-test): "
                                + SCHEMA_RESOURCE);
            }
            String schema = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            boolean created = indexService.createIndex(INDEX_NAME, schema);
            if (!created)
            {
                throw new IllegalStateException("Failed to create Elasticsearch index '" + INDEX_NAME + "'");
            }
            LOG.info("[reliability] Created Elasticsearch index '{}' with schema from live-indexing-shared-test",
                    INDEX_NAME);
        }
    }

    /** Doc count in the Alfresco index; returns 0 if the index doesn't exist yet. */
    public long countDocuments() throws IOException
    {
        try
        {
            CountResponse response = client.count(c -> c.index(INDEX_NAME));
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

    /**
     * Node count in the Alfresco index, excluding the {@code sys:descriptor} document that {@code RepositoryDescriptorItemWriter} writes on the first successful cycle to record the repository UUID. Use this when the assertion is about repository content, not connector bookkeeping.
     */
    public long countNodeDocuments() throws IOException
    {
        try
        {
            Query notDescriptor = Query.of(q -> q.bool(b -> b.mustNot(mn -> mn.term(t -> t
                    .field("TYPE")
                    .value(v -> v.stringValue("sys:descriptor"))))));
            CountResponse response = client.count(c -> c.index(INDEX_NAME).query(notDescriptor));
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

    /**
     * Docs where the content writer has successfully written extracted text. Discriminates on the presence of the encoded {@code cm:content} field, which {@code ContentRequestBuilder} only sets on the TEXT_SCRIPT path (successful transform with a non-empty body).
     * <p>
     * NB: {@code CONTENT_INDEXING_LAST_UPDATE} does <b>not</b> work as a discriminator — the metadata writer pre-populates it via {@code createContentUpsertRequest} the moment it sees a content-bearing node, before the content step even runs.
     */
    public long countDocumentsWithContentText() throws IOException
    {
        try
        {
            Query exists = Query.of(q -> q.exists(e -> e.field("cm%3Acontent")));
            CountResponse response = client.count(c -> c.index(INDEX_NAME).query(exists));
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
}
