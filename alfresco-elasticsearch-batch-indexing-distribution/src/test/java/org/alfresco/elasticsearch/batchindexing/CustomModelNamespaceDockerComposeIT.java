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
package org.alfresco.elasticsearch.batchindexing;

import static java.net.http.HttpRequest.BodyPublishers.noBody;
import static java.net.http.HttpRequest.BodyPublishers.ofString;
import static java.nio.charset.StandardCharsets.UTF_8;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.ComposeContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * ACS-12851 end to end: a content model deployed through ACS, in a namespace the bundled prefix map does not contain.
 * <p>
 * The only test in which the namespace, the QNames and the nodes are created by the repository rather than by a SQL fixture, and the only one where ACS extends the index mapping itself.
 * <p>
 * Preconditions, as for {@link BatchIndexingDockerComposeIT}:
 * <ol>
 * <li>{@code mvn install -DskipTests -pl alfresco-elasticsearch-batch-indexing,alfresco-elasticsearch-batch-indexing-distribution -am -P docker-image}</li>
 * <li>{@code bash scripts/ci/buildDockerImagesCi.sh}</li>
 * </ol>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("ACS-12851: a custom content model namespace missing from the prefix map")
class CustomModelNamespaceDockerComposeIT
{
    private static final Logger LOG = LoggerFactory.getLogger(CustomModelNamespaceDockerComposeIT.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String ALFRESCO_SERVICE = "alfresco";
    private static final String ELASTICSEARCH_SERVICE = "elasticsearch";
    private static final String BATCH_INDEXING_SERVICE = "batch-indexing";
    private static final int ALFRESCO_PORT = 8080;
    private static final int ELASTICSEARCH_PORT = 9200;

    private static final String INDEX = "alfresco";
    private static final String CUSTOM_PROPERTY_FIELD = "acme%3AinvoiceNumber";
    private static final String CUSTOM_ASPECT = "acme:invoiceable";
    private static final String CUSTOM_TYPE = "acme:invoice";
    private static final String CUSTOM_NAMESPACE_URI = "http://acme.example.org/model/custom/1.0";
    private static final String INVOICE_NUMBER = "INV-0001";

    private static ComposeContainer environment;
    private static HttpClient http;
    private static String alfrescoBaseUrl;
    private static String elasticsearchBaseUrl;

    private static String typedNodeId;
    private static String aspectNodeId;

    @BeforeAll
    void deployModelAndIndex() throws IOException, InterruptedException
    {
        String batchIndexingTag = System.getProperty("batch-indexing.version");
        assertThat(batchIndexingTag)
                .as("System property 'batch-indexing.version' must be set (failsafe systemPropertyVariables)")
                .isNotBlank();

        File composeFile = new File("src/test/resources/docker/custom-model-e2e-compose.yaml");
        assertThat(composeFile).exists();

        environment = new ComposeContainer(composeFile)
                .withEnv("BATCH_INDEXING_TAG", batchIndexingTag)
                .withEnv("ALFRESCO_COMMUNITY_TAG", System.getProperty("alfresco.community.version", "26.2.0"))
                .withEnv("POSTGRES_DRIVER_PATH", findPostgresDriver().getAbsolutePath())
                .withLogConsumer(BATCH_INDEXING_SERVICE, new Slf4jLogConsumer(LOG).withPrefix("batch-indexing"))
                .withExposedService(ELASTICSEARCH_SERVICE, ELASTICSEARCH_PORT,
                        Wait.forHttp("/_cluster/health").forStatusCode(200).forPort(ELASTICSEARCH_PORT)
                                .withStartupTimeout(Duration.ofMinutes(3)))
                .withExposedService(ALFRESCO_SERVICE, ALFRESCO_PORT,
                        Wait.forHttp("/alfresco/api/-default-/public/alfresco/versions/1/probes/-ready-")
                                .forStatusCode(200).forPort(ALFRESCO_PORT)
                                .withStartupTimeout(Duration.ofMinutes(10)));

        environment.start();

        http = HttpClient.newHttpClient();
        alfrescoBaseUrl = "http://localhost:" + environment.getServicePort(ALFRESCO_SERVICE, ALFRESCO_PORT);
        elasticsearchBaseUrl = "http://localhost:"
                + environment.getServicePort(ELASTICSEARCH_SERVICE, ELASTICSEARCH_PORT);

        deployCustomModel();
        awaitMappingExtended();

        typedNodeId = createNode(null, "invoice-" + System.currentTimeMillis() + ".txt", CUSTOM_TYPE);
        aspectNodeId = createNode(null, "invoiced-" + System.currentTimeMillis() + ".txt", "cm:content");
        addAspectWithProperty(aspectNodeId);

        awaitIndexed(aspectNodeId);
    }

    @AfterAll
    void stopStack()
    {
        if (environment != null)
        {
            environment.stop();
        }
    }

    @Test
    @DisplayName("ACS extended the index mapping with the custom field")
    void acsExtendedTheIndexMapping() throws IOException, InterruptedException
    {
        assertThat(get(elasticsearchBaseUrl + "/" + INDEX + "/_mapping").body())
                .contains(CUSTOM_PROPERTY_FIELD);
    }

    @Test
    @DisplayName("the node whose type comes from the custom model is absent from the index")
    void theTypedNodeIsAbsentFromTheIndex() throws IOException, InterruptedException
    {
        assertThat(indexedDocument(typedNodeId)).isNull();
    }

    @Test
    @DisplayName("the cm:content node is indexed without the custom aspect and its property")
    void theAspectAndPropertyAreMissingFromTheDocument() throws IOException, InterruptedException
    {
        JsonNode source = indexedDocument(aspectNodeId);

        assertThat(source).isNotNull();
        assertThat(source.has(CUSTOM_PROPERTY_FIELD)).isFalse();
        assertThat(source.path("ASPECT").toString()).doesNotContain(CUSTOM_ASPECT);
    }

    @Test
    @DisplayName("the indexer reported the unresolved namespace and the lost node")
    void theIndexerReportedTheUnresolvedNamespace()
    {
        String logs = batchIndexingLogs();

        assertThat(logs).contains(CUSTOM_NAMESPACE_URI);
        assertThat(logs).contains("will not be indexed");
    }

    private String batchIndexingLogs()
    {
        return environment.getContainerByServiceName(BATCH_INDEXING_SERVICE + "-1")
                .or(() -> environment.getContainerByServiceName(BATCH_INDEXING_SERVICE + "_1"))
                .orElseThrow(() -> new AssertionError("batch-indexing container not found"))
                .getLogs();
    }

    // --- ACS ---

    private void deployCustomModel() throws IOException, InterruptedException
    {
        String modelXml = Files.readString(Path.of("src/test/resources/docker/acme-model.xml"), UTF_8);

        String modelNodeId = createNode("Data Dictionary/Models", "acme-model.xml", "cm:dictionaryModel");
        put(alfrescoBaseUrl + nodesPath() + "/" + modelNodeId + "/content", modelXml, "text/xml");
        put(alfrescoBaseUrl + nodesPath() + "/" + modelNodeId,
                "{\"properties\":{\"cm:modelActive\":true}}", "application/json");
    }

    private String createNode(String relativePath, String name, String nodeType)
            throws IOException, InterruptedException
    {
        StringBuilder body = new StringBuilder("{\"name\":\"").append(name)
                .append("\",\"nodeType\":\"").append(nodeType).append('"');
        if (relativePath != null)
        {
            body.append(",\"relativePath\":\"").append(relativePath).append('"');
        }
        body.append('}');

        HttpResponse<String> response = post(alfrescoBaseUrl + nodesPath() + "/-root-/children", body.toString());
        assertThat(response.statusCode())
                .as("create %s: %s", nodeType, response.body())
                .isEqualTo(201);
        return JSON.readTree(response.body()).path("entry").path("id").asText();
    }

    private void addAspectWithProperty(String nodeId) throws IOException, InterruptedException
    {
        put(alfrescoBaseUrl + nodesPath() + "/" + nodeId,
                "{\"aspectNames\":[\"cm:titled\",\"" + CUSTOM_ASPECT + "\"],"
                        + "\"properties\":{\"acme:invoiceNumber\":\"" + INVOICE_NUMBER + "\"}}",
                "application/json");
    }

    private static String nodesPath()
    {
        return "/alfresco/api/-default-/public/alfresco/versions/1/nodes";
    }

    // --- Elasticsearch ---

    private void awaitMappingExtended()
    {
        await().atMost(Duration.ofMinutes(3))
                .pollInterval(Duration.ofSeconds(5))
                .until(() -> get(elasticsearchBaseUrl + "/" + INDEX + "/_mapping").body()
                        .contains(CUSTOM_PROPERTY_FIELD));
    }

    private void awaitIndexed(String nodeId)
    {
        await().atMost(Duration.ofMinutes(3))
                .pollInterval(Duration.ofSeconds(5))
                .until(() -> indexedDocument(nodeId) != null);
    }

    private JsonNode indexedDocument(String nodeId) throws IOException, InterruptedException
    {
        HttpResponse<String> response = get(elasticsearchBaseUrl + "/" + INDEX + "/_doc/" + nodeId);
        if (response.statusCode() != 200)
        {
            return null;
        }
        JsonNode document = JSON.readTree(response.body());
        return document.path("found").asBoolean() ? document.path("_source") : null;
    }

    // --- HTTP ---

    private HttpResponse<String> get(String url) throws IOException, InterruptedException
    {
        return http.send(authorized(HttpRequest.newBuilder(URI.create(url))).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String url, String json) throws IOException, InterruptedException
    {
        HttpRequest request = authorized(HttpRequest.newBuilder(URI.create(url)))
                .header("Content-Type", "application/json")
                .POST(json == null ? noBody() : ofString(json, UTF_8))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private void put(String url, String body, String contentType) throws IOException, InterruptedException
    {
        HttpRequest request = authorized(HttpRequest.newBuilder(URI.create(url)))
                .header("Content-Type", contentType)
                .PUT(ofString(body, UTF_8))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("PUT %s: %s", url, response.body()).isEqualTo(200);
    }

    private static HttpRequest.Builder authorized(HttpRequest.Builder builder)
    {
        String credentials = Base64.getEncoder().encodeToString("admin:admin".getBytes(UTF_8));
        return builder.header("Authorization", "Basic " + credentials).timeout(Duration.ofSeconds(60));
    }

    /** Resolved from the driver on the test classpath, so no classifier jar can be picked by mistake. */
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
            throw new IllegalStateException("Could not locate the PostgreSQL driver jar", e);
        }
    }
}
