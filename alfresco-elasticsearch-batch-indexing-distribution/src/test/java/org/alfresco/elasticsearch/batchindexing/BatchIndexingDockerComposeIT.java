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
package org.alfresco.elasticsearch.batchindexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import org.apache.hc.core5.http.HttpHost;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opensearch.client.RestClient;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.OpenSearchException;
import org.opensearch.client.opensearch.core.IndexRequest;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.transport.rest_client.RestClientTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.ComposeContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;

import org.alfresco.elasticsearch.batchindexing.reliability.harness.AlfrescoIndexHelper;

/**
 * End-to-end integration test for the packaged batch-indexing Docker image. Boots the image alongside Postgres (seeded with an Alfresco DB dump), Elasticsearch, and Transform Core AIO via docker-compose, then asserts the indexer populates Elasticsearch with the expected nodes and fields.
 * <p>
 * Preconditions (must be done before running this IT):
 * <ol>
 * <li>{@code mvn install -DskipTests -pl alfresco-elasticsearch-batch-indexing,alfresco-elasticsearch-batch-indexing-distribution -am -P docker-image} — stages the Docker build context.</li>
 * <li>{@code bash scripts/ci/buildDockerImagesCi.sh} — builds the image {@code alfresco-elasticsearch-batch-indexing:<project.version>} locally.</li>
 * </ol>
 * <p>
 * Excluded from the default failsafe run; enabled by the {@code docker-compose-test} Maven profile.
 */
@SuppressWarnings("PMD.UnitTestShouldIncludeAssert")
public class BatchIndexingDockerComposeIT
{
    private static final Logger LOG = LoggerFactory.getLogger(BatchIndexingDockerComposeIT.class);

    private static final String ELASTICSEARCH_SERVICE = "elasticsearch-1";
    private static final int ELASTICSEARCH_PORT = 9200;
    private static final String INDEX_NAME = AlfrescoIndexHelper.INDEX_NAME;
    private static final String BATCH_INDEXING_SERVICE = "batch-indexing-1";
    private static final Path DUMP_SQL_PATH = Path.of(
            "../alfresco-elasticsearch-batch-indexing/src/test/resources/docker/alfresco-dump.sql").toAbsolutePath().normalize();

    private static ComposeContainer environment;
    private static RestClient httpClient;
    private static RestClientTransport transport;
    private static OpenSearchClient opensearchClient;

    @BeforeAll
    static void startStack() throws IOException
    {
        String batchIndexingTag = System.getProperty("batch-indexing.version");
        assertThat(batchIndexingTag)
                .as("System property 'batch-indexing.version' must be set (failsafe systemPropertyVariables)")
                .isNotBlank();

        File composeFile = new File("src/test/resources/docker/batch-indexing-e2e-compose.yaml");
        assertThat(composeFile).exists();
        assertThat(DUMP_SQL_PATH.toFile()).as("alfresco-dump.sql must exist in the batch-indexing module").exists();

        File postgresDriver = findPostgresDriver();

        environment = new ComposeContainer(composeFile)
                .withEnv("BATCH_INDEXING_TAG", batchIndexingTag)
                .withEnv("ALFRESCO_DUMP_SQL_PATH", DUMP_SQL_PATH.toString())
                .withEnv("POSTGRES_DRIVER_PATH", postgresDriver.getAbsolutePath())
                .withLogConsumer(BATCH_INDEXING_SERVICE, new Slf4jLogConsumer(LOG).withPrefix("batch-indexing"))
                .withExposedService(ELASTICSEARCH_SERVICE, ELASTICSEARCH_PORT,
                        Wait.forHttp("/_cluster/health")
                                .forStatusCode(200)
                                .forPort(ELASTICSEARCH_PORT)
                                .withStartupTimeout(Duration.ofMinutes(3)));

        environment.start();

        int esPort = environment.getServicePort(ELASTICSEARCH_SERVICE, ELASTICSEARCH_PORT);
        httpClient = RestClient.builder(new HttpHost("localhost", esPort)).build();
        transport = new RestClientTransport(httpClient, new JacksonJsonpMapper());
        opensearchClient = new OpenSearchClient(transport);

        new AlfrescoIndexHelper(opensearchClient).createIfMissing();
        seedWatermark();
    }

    @AfterAll
    static void tearDown() throws IOException
    {
        if (transport != null)
        {
            transport.close();
        }
        if (httpClient != null)
        {
            httpClient.close();
        }
        if (environment != null)
        {
            environment.close();
        }
    }

    @Test
    void batchIndexing_shouldIndexNodesFromDatabaseDump()
    {
        await().atMost(Duration.ofMinutes(3))
                .pollInterval(Duration.ofSeconds(5))
                .untilAsserted(() -> {
                    long docCount = countDocuments();
                    assertThat(docCount)
                            .as("Expected at least some documents to be indexed from the database dump")
                            .isGreaterThan(0);
                });
    }

    @Test
    void batchIndexing_shouldIndexExpectedNumberOfNodes()
    {
        await().atMost(Duration.ofMinutes(3))
                .pollInterval(Duration.ofSeconds(5))
                .untilAsserted(() -> {
                    long docCount = countDocuments();
                    assertThat(docCount)
                            .as("Expected roughly the same number of nodes as in the dump (minus deleted/filtered)")
                            .isGreaterThan(900);
                });
    }

    @Test
    void batchIndexing_shouldPopulateMetadataFields()
    {
        // Pin to cm:content so we get a metadata-rich node. sys:descriptor and
        // sys:store_root also land in the index but don't have PROPERTIES.
        await().atMost(Duration.ofMinutes(3))
                .pollInterval(Duration.ofSeconds(5))
                .untilAsserted(() -> {
                    SearchResponse<Map> response = opensearchClient.search(
                            SearchRequest.of(s -> s.index(INDEX_NAME)
                                    .query(q -> q.term(t -> t.field("TYPE")
                                            .value(v -> v.stringValue("cm:content"))))
                                    .size(1)),
                            Map.class);

                    assertThat(response.hits().hits()).isNotEmpty();
                    Map source = response.hits().hits().get(0).source();
                    assertThat(source).isNotNull();
                    assertThat(source).containsKey("TYPE");
                    assertThat(source).containsKey("PROPERTIES");
                });
    }

    private long countDocuments() throws IOException
    {
        try
        {
            return opensearchClient.count(c -> c.index(INDEX_NAME)).count();
        }
        catch (OpenSearchException e)
        {
            if (e.getMessage().contains("index_not_found_exception"))
            {
                return 0;
            }
            throw e;
        }
    }

    private static void seedWatermark() throws IOException
    {
        String watermarkIndex = "alfresco-reindex-state";
        String watermarkDocId = "reindexByDate-watermark";

        Map<String, Object> watermark = Map.of(
                "schemaVersion", 1,
                "lastSuccessfulToTimeEpochMs", 1L,
                "lastRunStatus", "SEEDED",
                "updatedAt", java.time.Instant.now().toString());

        opensearchClient.index(IndexRequest.of(r -> r
                .index(watermarkIndex)
                .id(watermarkDocId)
                .document(watermark)));

        LOG.info("Seeded watermark in '{}' with lastSuccessfulToTimeEpochMs=1 to trigger full historical scan",
                watermarkIndex);
    }

    private static File findPostgresDriver()
    {
        try
        {
            File driverJar = new File(org.postgresql.Driver.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            assertThat(driverJar).as("PostgreSQL driver jar").exists();
            return driverJar;
        }
        catch (URISyntaxException e)
        {
            throw new IllegalStateException("Failed to locate PostgreSQL driver jar", e);
        }
    }
}
