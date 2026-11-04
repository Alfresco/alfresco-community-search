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
package org.alfresco.indexing.shared.config.searchengine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.apache.hc.client5.http.ConnectTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opensearch.client.Request;
import org.opensearch.client.Response;
import org.opensearch.client.RestClient;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * Verifies that the configured connection and socket/read timeouts reach the requests issued by the {@link RestClient} built by
 * {@link SearchEngineRestClientConfigurations.RestClientBuilderConfiguration}, instead of OpenSearch's per-request defaults.
 */
@SuppressWarnings("deprecation")
class SearchEngineRestClientTimeoutsTest
{
    private static final String UNREACHABLE_URI = "http://10.255.255.1:9200";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(BootConfiguration.class)
            .withPropertyValues("spring.elasticsearch.restclient.sniffer.enabled=false");

    private MockWebServer server;

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class BootConfiguration
    {}

    @BeforeEach
    void startServer() throws IOException
    {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void stopServer() throws IOException
    {
        server.shutdown();
    }

    @Test
    void socketTimeoutLongerThanOpenSearchDefaultIsApplied()
    {
        server.enqueue(new MockResponse().setBody("{}").setHeadersDelay(35, TimeUnit.SECONDS));

        contextRunner.withPropertyValues("spring.elasticsearch.uris=" + serverUri(),
                "spring.elasticsearch.socket-timeout=60s")
                .run(context -> {
                    RestClient client = context.getBean(RestClient.class);

                    Response response = client.performRequest(new Request("GET", "/"));

                    assertThat(response.getStatusLine().getStatusCode()).isEqualTo(200);
                });
    }

    @Test
    void deprecatedReadTimeoutIsApplied()
    {
        server.enqueue(new MockResponse().setBody("{}").setHeadersDelay(5, TimeUnit.SECONDS));

        contextRunner.withPropertyValues("spring.elasticsearch.rest.uris=" + serverUri(),
                "spring.elasticsearch.rest.read-timeout=2s")
                .run(context -> {
                    RestClient client = context.getBean(RestClient.class);
                    long start = System.nanoTime();

                    assertThatThrownBy(() -> client.performRequest(new Request("GET", "/")))
                            .isInstanceOf(SocketTimeoutException.class);

                    assertThat(elapsedSince(start)).isBetween(Duration.ofSeconds(2), Duration.ofMillis(4500));
                });
    }

    @Test
    void connectionTimeoutLongerThanOpenSearchDefaultIsApplied()
    {
        contextRunner.withPropertyValues("spring.elasticsearch.uris=" + UNREACHABLE_URI,
                "spring.elasticsearch.connection-timeout=4s")
                .run(context -> {
                    RestClient client = context.getBean(RestClient.class);
                    long start = System.nanoTime();

                    assertThatThrownBy(() -> client.performRequest(new Request("GET", "/")))
                            .isInstanceOf(ConnectTimeoutException.class);

                    assertThat(elapsedSince(start)).isBetween(Duration.ofMillis(3500), Duration.ofSeconds(10));
                });
    }

    private String serverUri()
    {
        return "http://" + server.getHostName() + ":" + server.getPort();
    }

    private static Duration elapsedSince(long startNanos)
    {
        return Duration.ofNanos(System.nanoTime() - startNanos);
    }
}
