/*-
 * #%L
 * Alfresco Search Community :: OpenSearch Client
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
package org.alfresco.indexing.shared.opensearch.adapter;

import static java.util.UUID.randomUUID;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Map;

import org.apache.hc.core5.http.HttpHost;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.opensearch.client.RestClient;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.Refresh;
import org.opensearch.client.opensearch.core.GetResponse;
import org.opensearch.client.transport.rest_client.RestClientTransport;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import org.alfresco.indexing.shared.ElasticsearchProperties;
import org.alfresco.indexing.shared.TransformStatus;
import org.alfresco.indexing.shared.opensearch.command.ContentClearCmd;
import org.alfresco.indexing.shared.opensearch.command.ContentIndexCmd;
import org.alfresco.indexing.shared.opensearch.request.ContentRequestBuilder;
import org.alfresco.indexing.shared.utils.OpenSearchJsonMapperFactory;

/**
 * Covers {@link OpensearchContentModifier#clearContents} against an Elasticsearch container: which fields a clear removes, that a repeated clear leaves the document version unchanged, that clearing an unindexed node creates no document, and that content indexed after the cutoff is kept.
 */
@Testcontainers
@DisplayName("Excluded content clear against Elasticsearch")
class ContentExclusionClearIT
{
    private static final String INDEX = "content-exclusion-clear-it";
    private static final String ENCODED_CONTENT = "cm%3Acontent";
    private static final String ENCODED_TR_STATUS = "cm%3Acontent%2Etr_status";
    private static final String ENCODED_TR_EX = "cm%3Acontent%2Etr_ex";
    private static final String ENCODED_TITLE = "cm%3Atitle";
    private static final String TIMESTAMP = "CONTENT_INDEXING_LAST_UPDATE";

    private static final long CONTENT_TIMESTAMP = 1000L;
    private static final long CONFLICT_CUTOFF = 5000L;
    private static final long AFTER_THE_CUTOFF = 9000L;
    private static final long BEFORE_THE_CLEAR = 500L;

    @Container
    private static final ElasticsearchContainer ELASTICSEARCH = new ElasticsearchContainer(
            DockerImageName.parse("docker.elastic.co/elasticsearch/elasticsearch:8.17.0")
                    .asCompatibleSubstituteFor("docker.elastic.co/elasticsearch/elasticsearch"))
                            .withEnv("xpack.security.enabled", "false")
                            .withEnv("ES_JAVA_OPTS", "-Xms1g -Xmx1g");

    private static RestClient restClient;
    private static OpenSearchClient client;
    private static OpensearchContentModifier modifier;

    @BeforeAll
    static void startClient() throws IOException, URISyntaxException
    {
        restClient = RestClient.builder(HttpHost.create(ELASTICSEARCH.getHttpHostAddress())).build();
        client = new OpenSearchClient(new RestClientTransport(restClient, OpenSearchJsonMapperFactory.createJsonpMapper()));
        client.indices().create(create -> create.index(INDEX));

        ElasticsearchProperties properties = new ElasticsearchProperties();
        properties.setIndexName(INDEX);
        properties.setRetryOnConflict(5);
        modifier = new OpensearchContentModifier(new ContentRequestBuilder(properties), new OpensearchUpdater(client));
    }

    @AfterAll
    static void closeClient() throws IOException
    {
        if (restClient != null)
        {
            restClient.close();
        }
    }

    @Test
    @DisplayName("Clears the indexed text and the transform status, and leaves the metadata alone")
    void clearRemovesContentAndTransformStatus() throws IOException
    {
        String id = randomUUID().toString();
        indexContentFor(id, CONTENT_TIMESTAMP);

        clear(id);

        assertThat(sourceOf(id))
                .doesNotContainKey(ENCODED_CONTENT)
                .doesNotContainKey(ENCODED_TR_STATUS)
                .doesNotContainKey(ENCODED_TR_EX)
                .containsEntry(ENCODED_TITLE, "a title")
                .containsEntry(TIMESTAMP, (int) CONTENT_TIMESTAMP);
    }

    @Test
    @DisplayName("Clearing a node that is already clear does not write, so repeated reindexes cost nothing")
    void repeatedClearDoesNotWrite() throws IOException
    {
        String id = randomUUID().toString();
        indexContentFor(id, CONTENT_TIMESTAMP);

        clear(id);
        long versionAfterFirstClear = versionOf(id);
        clear(id);
        clear(id);

        assertThat(versionOf(id))
                .as("document version after two further clears")
                .isEqualTo(versionAfterFirstClear);
    }

    @Test
    @DisplayName("Clearing a node that is not in the index does not create a stub document")
    void clearDoesNotCreateADocumentForAnUnindexedNode() throws IOException
    {
        String id = randomUUID().toString();

        clear(id);

        assertThat(client.get(get -> get.index(INDEX).id(id), Map.class).found())
                .as("document created by clearing a node that was never indexed")
                .isFalse();
    }

    @Test
    @DisplayName("Content indexed after the cutoff is newer than the exclusion and is kept")
    void contentIndexedAfterTheCutoffIsKept() throws IOException
    {
        String id = randomUUID().toString();
        indexContentFor(id, AFTER_THE_CUTOFF);

        clear(id);

        assertThat(sourceOf(id)).containsEntry(ENCODED_CONTENT, "some indexed text");
    }

    @Test
    @DisplayName("A node whose first transform is still in flight records the clear timestamp, so the late reply cannot index its text")
    void clearBlocksAReplyFromATransformStillInFlight() throws IOException
    {
        String id = randomUUID().toString();
        indexMetadataOnly(id);

        clear(id);

        // The transform was requested before the node became excluded, so its reply carries an older timestamp.
        modifier.upsertContent(ContentIndexCmd.withTextScript(id, BEFORE_THE_CLEAR, "text from the in-flight transform"));

        assertThat(sourceOf(id))
                .doesNotContainKey(ENCODED_CONTENT)
                .doesNotContainKey(ENCODED_TR_STATUS)
                .containsEntry(TIMESTAMP, (int) CONTENT_TIMESTAMP);
    }

    @Test
    @DisplayName("A late reply reporting a failed transform cannot put the transform status back after the clear")
    void clearBlocksAFailureReplyFromATransformStillInFlight() throws IOException
    {
        String id = randomUUID().toString();
        indexMetadataOnly(id);

        clear(id);

        modifier.upsertContent(ContentIndexCmd.withExceptionScript(
                id, BEFORE_THE_CLEAR, TransformStatus.TRANSFORM_FAILED, "transform blew up"));

        assertThat(sourceOf(id))
                .doesNotContainKey(ENCODED_TR_STATUS)
                .doesNotContainKey(ENCODED_TR_EX)
                .doesNotContainKey(ENCODED_CONTENT);
    }

    @Test
    @DisplayName("A node with nothing indexed records the clear timestamp rather than skipping the write")
    void clearRecordsTheTimestampWhenNothingIsIndexedYet() throws IOException
    {
        String id = randomUUID().toString();
        indexMetadataOnly(id);

        clear(id);

        assertThat(sourceOf(id)).containsEntry(TIMESTAMP, (int) CONTENT_TIMESTAMP);
    }

    private void clear(String id)
    {
        modifier.clearContents(List.of(new ContentClearCmd(id, CONTENT_TIMESTAMP, CONFLICT_CUTOFF)));
    }

    /** Indexes a document with metadata only: no content fields and no content timestamp, as a node looks while its first transform is in flight. */
    private void indexMetadataOnly(String id) throws IOException
    {
        client.index(index -> index.index(INDEX).id(id)
                .document(Map.of(ENCODED_TITLE, "a title"))
                .refresh(Refresh.True));
    }

    /** Indexes a document holding content text, a failed transform status, a transform error and a title, with the given content timestamp. */
    private void indexContentFor(String id, long contentIndexedAt) throws IOException
    {
        Map<String, Object> source = Map.of(
                TIMESTAMP, contentIndexedAt,
                ENCODED_CONTENT, "some indexed text",
                ENCODED_TR_STATUS, "TRANSFORM_FAILED",
                ENCODED_TR_EX, "transform blew up",
                ENCODED_TITLE, "a title");
        client.index(index -> index.index(INDEX).id(id).document(source).refresh(Refresh.True));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sourceOf(String id) throws IOException
    {
        GetResponse<Map> response = client.get(get -> get.index(INDEX).id(id).refresh(true), Map.class);
        assertThat(response.found()).as("document %s present", id).isTrue();
        return response.source();
    }

    private long versionOf(String id) throws IOException
    {
        GetResponse<Map> response = client.get(get -> get.index(INDEX).id(id).refresh(true), Map.class);
        assertThat(response.found()).as("document %s present", id).isTrue();
        return response.version();
    }
}
