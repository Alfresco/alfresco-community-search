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
package org.alfresco.elasticsearch.batchindexing.domain.transform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.DefaultUriBuilderFactory;
import org.springframework.web.util.UriBuilder;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class ContentTransformClientTest
{
    private static final Long NODE_DB_ID = 42L;
    private static final String TRANSFORMED_TEXT = "extracted text from document";
    private static final String URL_PATH = "/service/api/solr/textContent";
    private static final String SHARED_SECRET = "test-secret"; // pragma: allowlist secret
    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final int RETRY_MAX_ATTEMPTS = 2;
    private static final Duration RETRY_DELAY = Duration.ofMillis(50);

    @Mock
    private WebClient acsWebClient;
    @Mock
    private WebClient.RequestHeadersUriSpec requestHeadersUriSpec;
    @Mock
    private WebClient.RequestHeadersSpec requestHeadersSpec;
    @Mock
    private WebClient.ResponseSpec responseSpec;

    private ContentTransformClient client;

    @SuppressWarnings({"unchecked", "rawtypes"})
    @BeforeEach
    void setUp()
    {
        ContentTransformProperties properties = new ContentTransformProperties();
        properties.setUrlPath(URL_PATH);
        properties.setSharedSecret(SHARED_SECRET);
        properties.setTimeout(TIMEOUT);
        properties.setRetryMaxAttempts(RETRY_MAX_ATTEMPTS);
        properties.setRetryDelay(RETRY_DELAY);

        doReturn(requestHeadersUriSpec).when(acsWebClient).get();
        doReturn(requestHeadersSpec).when(requestHeadersUriSpec).uri(any(Function.class));
        doReturn(requestHeadersSpec).when(requestHeadersSpec).header(any(), any(String[].class));
        doReturn(responseSpec).when(requestHeadersSpec).retrieve();

        client = new ContentTransformClient(properties, acsWebClient);
    }

    @Nested
    @DisplayName("Successful responses")
    class SuccessfulResponses
    {
        @Test
        @DisplayName("Returns transformed text when ACS returns content")
        void returnsTransformedText()
        {
            when(responseSpec.bodyToMono(String.class)).thenReturn(Mono.just(TRANSFORMED_TEXT));

            ContentTransformResult result = client.transformContent(NODE_DB_ID);

            assertThat(result.text()).contains(TRANSFORMED_TEXT);
            assertThat(result.failure()).isEmpty();
        }

        @Test
        @DisplayName("Returns empty string content as-is (not treated as null)")
        void returnsEmptyStringContent()
        {
            when(responseSpec.bodyToMono(String.class)).thenReturn(Mono.just(""));

            ContentTransformResult result = client.transformContent(NODE_DB_ID);

            assertThat(result.text()).contains("");
            assertThat(result.failure()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Null / empty responses")
    class NullResponses
    {
        @Test
        @DisplayName("Empty result on null body (empty Mono) — treated as 'no content', not a failure")
        void returnsEmptyOnNullBody()
        {
            when(responseSpec.bodyToMono(String.class)).thenReturn(Mono.empty());

            ContentTransformResult result = client.transformContent(NODE_DB_ID);

            assertThat(result.text()).isEmpty();
            assertThat(result.failure()).isEmpty();
        }
    }

    @Nested
    @DisplayName("HTTP 4xx responses (surfaced as failure)")
    class HttpClientErrors
    {
        @Test
        @DisplayName("400 Bad Request surfaces as TRANSFORM_HTTP_400 failure")
        void surfacesOn400()
        {
            when(responseSpec.bodyToMono(String.class)).thenReturn(
                    Mono.error(WebClientResponseException.create(
                            HttpStatus.BAD_REQUEST.value(), "Bad Request",
                            null, "unsupported mimetype".getBytes(), null)));

            ContentTransformResult result = client.transformContent(NODE_DB_ID);

            assertThat(result.text()).isEmpty();
            assertThat(result.failure()).isPresent();
            assertThat(result.failure().get().failureType()).isEqualTo("TRANSFORM_HTTP_400");
            assertThat(result.failure().get().failureReason()).contains("unsupported mimetype");
        }

        @Test
        @DisplayName("404 Not Found surfaces as TRANSFORM_HTTP_404 failure")
        void surfacesOn404()
        {
            when(responseSpec.bodyToMono(String.class)).thenReturn(
                    Mono.error(WebClientResponseException.create(
                            HttpStatus.NOT_FOUND.value(), "Not Found",
                            null, null, null)));

            ContentTransformResult result = client.transformContent(NODE_DB_ID);

            assertThat(result.text()).isEmpty();
            assertThat(result.failure()).isPresent();
            assertThat(result.failure().get().failureType()).isEqualTo("TRANSFORM_HTTP_404");
        }

        @Test
        @DisplayName("401 Unauthorized surfaces as TRANSFORM_HTTP_401 failure")
        void surfacesOn401()
        {
            when(responseSpec.bodyToMono(String.class)).thenReturn(
                    Mono.error(WebClientResponseException.create(
                            HttpStatus.UNAUTHORIZED.value(), "Unauthorized",
                            null, null, null)));

            ContentTransformResult result = client.transformContent(NODE_DB_ID);

            assertThat(result.text()).isEmpty();
            assertThat(result.failure()).isPresent();
            assertThat(result.failure().get().failureType()).isEqualTo("TRANSFORM_HTTP_401");
        }
    }

    @Nested
    @DisplayName("HTTP 5xx / generic errors (swallowed, no failure surfaced)")
    class SwallowedErrors
    {
        @Test
        @DisplayName("500 Internal Server Error is swallowed to empty (retry will happen on the next cycle)")
        void swallowsOn500()
        {
            when(responseSpec.bodyToMono(String.class)).thenReturn(
                    Mono.error(WebClientResponseException.create(
                            HttpStatus.INTERNAL_SERVER_ERROR.value(), "Internal Server Error",
                            null, null, null)));

            ContentTransformResult result = client.transformContent(NODE_DB_ID);

            assertThat(result.text()).isEmpty();
            assertThat(result.failure()).isEmpty();
        }

        @Test
        @DisplayName("RuntimeException is swallowed to empty")
        void swallowsOnRuntimeException()
        {
            when(responseSpec.bodyToMono(String.class)).thenReturn(
                    Mono.error(new RuntimeException("connection refused")));

            ContentTransformResult result = client.transformContent(NODE_DB_ID);

            assertThat(result.text()).isEmpty();
            assertThat(result.failure()).isEmpty();
        }

        @Test
        @DisplayName("IOException wrapped in the reactive chain is swallowed to empty")
        void swallowsOnIOException()
        {
            when(responseSpec.bodyToMono(String.class)).thenReturn(
                    Mono.error(new java.io.IOException("network timeout")));

            ContentTransformResult result = client.transformContent(NODE_DB_ID);

            assertThat(result.text()).isEmpty();
            assertThat(result.failure()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Request construction")
    class RequestConstruction
    {
        @Test
        @DisplayName("Sends shared secret header with correct key and value")
        void sendsSharedSecretHeader()
        {
            when(responseSpec.bodyToMono(String.class)).thenReturn(Mono.just(TRANSFORMED_TEXT));

            client.transformContent(NODE_DB_ID);

            verify(requestHeadersSpec).header(eq("X-Alfresco-Search-Secret"), eq(SHARED_SECRET));
        }

        @Test
        @DisplayName("Uses GET method")
        void usesGetMethod()
        {
            when(responseSpec.bodyToMono(String.class)).thenReturn(Mono.just(TRANSFORMED_TEXT));

            client.transformContent(NODE_DB_ID);

            verify(acsWebClient).get();
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        @ParameterizedTest
        @DisplayName("Composes the request URL from the ACS base URL plus the fixed service path")
        @CsvSource({
                "http://acs:8080/alfresco, http://acs:8080/alfresco/service/api/solr/textContent?nodeId=42",
                "http://acs:8080/alfresco/, http://acs:8080/alfresco/service/api/solr/textContent?nodeId=42",
                "http://acs:8080/acs, http://acs:8080/acs/service/api/solr/textContent?nodeId=42",
                "http://acs:8080, http://acs:8080/service/api/solr/textContent?nodeId=42"
        })
        void composesRequestUrlFromBaseAndServicePath(String acsUrl, String expectedUrl)
        {
            when(responseSpec.bodyToMono(String.class)).thenReturn(Mono.just(TRANSFORMED_TEXT));

            client.transformContent(NODE_DB_ID);

            ArgumentCaptor<Function<UriBuilder, URI>> uriFunction = ArgumentCaptor.forClass(Function.class);
            verify(requestHeadersUriSpec).uri(uriFunction.capture());
            URI composed = uriFunction.getValue()
                    .apply(new DefaultUriBuilderFactory(ContentTransformConfig.normalizeBaseUrl(acsUrl)).builder());

            assertThat(composed).hasToString(expectedUrl);
        }
    }

    @Nested
    @DisplayName("Retry behavior")
    class RetryBehavior
    {
        @Test
        @DisplayName("Retries on 503 Service Unavailable and succeeds on subsequent attempt")
        void retriesOn503AndEventuallySucceeds()
        {
            AtomicInteger attempts = new AtomicInteger();
            when(responseSpec.bodyToMono(String.class)).thenReturn(
                    Mono.defer(() -> {
                        if (attempts.getAndIncrement() < 1)
                        {
                            return Mono.error(WebClientResponseException.create(
                                    HttpStatus.SERVICE_UNAVAILABLE.value(), "Service Unavailable",
                                    null, null, null));
                        }
                        return Mono.just(TRANSFORMED_TEXT);
                    }));

            ContentTransformResult result = client.transformContent(NODE_DB_ID);

            assertThat(result.text()).contains(TRANSFORMED_TEXT);
            assertThat(attempts).hasValue(2);
        }

        @Test
        @DisplayName("Returns empty when all 503 retry attempts are exhausted")
        void returnsEmptyAfterAllRetriesExhausted()
        {
            AtomicInteger attempts = new AtomicInteger();
            when(responseSpec.bodyToMono(String.class)).thenReturn(
                    Mono.defer(() -> {
                        attempts.incrementAndGet();
                        return Mono.error(WebClientResponseException.create(
                                HttpStatus.SERVICE_UNAVAILABLE.value(), "Service Unavailable",
                                null, null, null));
                    }));

            ContentTransformResult result = client.transformContent(NODE_DB_ID);

            assertThat(result.text()).isEmpty();
            assertThat(result.failure()).isEmpty();
            assertThat(attempts).hasValue(RETRY_MAX_ATTEMPTS + 1);
        }

        @Test
        @DisplayName("Does not retry on non-503 server errors (e.g. 500)")
        void doesNotRetryOnNon503ServerError()
        {
            AtomicInteger attempts = new AtomicInteger();
            when(responseSpec.bodyToMono(String.class)).thenReturn(
                    Mono.defer(() -> {
                        attempts.incrementAndGet();
                        return Mono.error(WebClientResponseException.create(
                                HttpStatus.INTERNAL_SERVER_ERROR.value(), "Internal Server Error",
                                null, null, null));
                    }));

            ContentTransformResult result = client.transformContent(NODE_DB_ID);

            assertThat(result.text()).isEmpty();
            assertThat(result.failure()).isEmpty();
            assertThat(attempts).hasValue(1);
        }

        @Test
        @DisplayName("Retries on timeout and succeeds on subsequent attempt")
        void retriesOnTimeoutAndEventuallySucceeds()
        {
            ContentTransformClient shortTimeoutClient = clientWithTimeout(Duration.ofMillis(50));

            AtomicInteger attempts = new AtomicInteger();
            when(responseSpec.bodyToMono(String.class)).thenReturn(
                    Mono.defer(() -> {
                        if (attempts.getAndIncrement() < 1)
                        {
                            return Mono.never();
                        }
                        return Mono.just(TRANSFORMED_TEXT);
                    }));

            ContentTransformResult result = shortTimeoutClient.transformContent(NODE_DB_ID);

            assertThat(result.text()).contains(TRANSFORMED_TEXT);
            assertThat(attempts).hasValue(2);
        }

        @Test
        @DisplayName("Returns empty when all timeout retry attempts are exhausted")
        void returnsEmptyAfterAllTimeoutRetriesExhausted()
        {
            ContentTransformClient shortTimeoutClient = clientWithTimeout(Duration.ofMillis(50));

            AtomicInteger attempts = new AtomicInteger();
            when(responseSpec.bodyToMono(String.class)).thenReturn(
                    Mono.defer(() -> {
                        attempts.incrementAndGet();
                        return Mono.never();
                    }));

            ContentTransformResult result = shortTimeoutClient.transformContent(NODE_DB_ID);

            assertThat(result.text()).isEmpty();
            assertThat(result.failure()).isEmpty();
            assertThat(attempts).hasValue(RETRY_MAX_ATTEMPTS + 1);
        }

        private ContentTransformClient clientWithTimeout(Duration timeout)
        {
            ContentTransformProperties props = new ContentTransformProperties();
            props.setUrlPath(URL_PATH);
            props.setSharedSecret(SHARED_SECRET);
            props.setTimeout(timeout);
            props.setRetryMaxAttempts(RETRY_MAX_ATTEMPTS);
            props.setRetryDelay(RETRY_DELAY);
            return new ContentTransformClient(props, acsWebClient);
        }
    }

    @Nested
    @DisplayName("Multiple calls")
    class MultipleCalls
    {
        @Test
        @DisplayName("Independent calls do not share state — failure followed by success works")
        void failureThenSuccess()
        {
            when(responseSpec.bodyToMono(String.class))
                    .thenReturn(Mono.error(new RuntimeException("first call fails")))
                    .thenReturn(Mono.just(TRANSFORMED_TEXT));

            ContentTransformResult first = client.transformContent(NODE_DB_ID);
            ContentTransformResult second = client.transformContent(NODE_DB_ID);

            assertThat(first.text()).isEmpty();
            assertThat(second.text()).contains(TRANSFORMED_TEXT);
        }

        @Test
        @DisplayName("Success followed by failure works independently")
        void successThenFailure()
        {
            when(responseSpec.bodyToMono(String.class))
                    .thenReturn(Mono.just(TRANSFORMED_TEXT))
                    .thenReturn(Mono.error(new RuntimeException("second call fails")));

            ContentTransformResult first = client.transformContent(99L);
            ContentTransformResult second = client.transformContent(100L);

            assertThat(first.text()).contains(TRANSFORMED_TEXT);
            assertThat(second.text()).isEmpty();
        }
    }
}
