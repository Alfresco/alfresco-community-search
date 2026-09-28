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
package org.alfresco.indexing.content.mediatype;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;

import org.alfresco.content.mediatype.AcceptedContentMediaTypesCache;
import org.alfresco.content.mediatype.AcceptedContentMediaTypesCacheProperties;
import org.alfresco.indexing.shared.mtls.WebClientBuilderAdjuster;
import org.alfresco.indexing.shared.mtls.config.MTLSConfig;

@Timeout(10L)
@SpringBootTest(classes = MTLSConfig.class)
class AcceptedContentMediaTypesCacheMultiUrlTest
{

    private static final String TIKA_RESPONSE = "{\n"
            + "  \"transformers\": [{\n"
            + "    \"supportedSourceAndTargetList\": [{\n"
            + "      \"sourceMediaType\": \"application/pdf\",\n"
            + "      \"targetMediaType\": \"text/plain\",\n"
            + "      \"maxSourceSizeBytes\": -1,\n"
            + "      \"priority\": 50\n"
            + "    }]\n"
            + "  }]\n"
            + "}";

    private static final String IMAGEMAGICK_RESPONSE = "{\n"
            + "  \"transformers\": [{\n"
            + "    \"supportedSourceAndTargetList\": [{\n"
            + "      \"sourceMediaType\": \"image/png\",\n"
            + "      \"targetMediaType\": \"text/plain\",\n"
            + "      \"maxSourceSizeBytes\": -1,\n"
            + "      \"priority\": 50\n"
            + "    }]\n"
            + "  }]\n"
            + "}";

    @Autowired
    private WebClientBuilderAdjuster adjuster;

    private MockWebServer server1;
    private MockWebServer server2;

    @Test
    void multipleUrlsAllSucceed_aggregatesResults() throws IOException
    {
        server1 = startMockServer(TIKA_RESPONSE);
        server2 = startMockServer(IMAGEMAGICK_RESPONSE);

        AcceptedContentMediaTypesCacheProperties props = new AcceptedContentMediaTypesCacheProperties();
        props.setBaseUrl(serverUrl(server1) + "," + serverUrl(server2));
        props.setRetryMaxAttempts(1);

        AcceptedContentMediaTypesCache cache = new AcceptedContentMediaTypesCache(props, adjuster);
        cache.refresh();

        assertTrue(cache.isSupported("application/pdf"));
        assertTrue(cache.isSupported("image/png"));
        assertFalse(cache.isSupported("video/mp4"));
    }

    @Test
    void multipleUrlsOneDown_mergesWithPreviousCache() throws IOException
    {
        server1 = startMockServer(TIKA_RESPONSE);
        server2 = startMockServer(IMAGEMAGICK_RESPONSE);

        AcceptedContentMediaTypesCacheProperties props = new AcceptedContentMediaTypesCacheProperties();
        props.setBaseUrl(serverUrl(server1) + "," + serverUrl(server2));
        props.setRetryMaxAttempts(1);

        AcceptedContentMediaTypesCache cache = new AcceptedContentMediaTypesCache(props, adjuster);
        cache.refresh();

        assertTrue(cache.isSupported("application/pdf"));
        assertTrue(cache.isSupported("image/png"));

        // Now shut down server2, simulating T-engine failure
        server2.shutdown();
        server2 = null;

        cache.refresh();

        // pdf still available from server1, image/png preserved from previous cache
        assertTrue(cache.isSupported("application/pdf"));
        assertTrue(cache.isSupported("image/png"));
    }

    @Test
    void multipleUrlsAllDown_keepsPreviousCache() throws IOException
    {
        server1 = startMockServer(TIKA_RESPONSE);

        AcceptedContentMediaTypesCacheProperties props = new AcceptedContentMediaTypesCacheProperties();
        props.setBaseUrl(serverUrl(server1));
        props.setRetryMaxAttempts(1);

        AcceptedContentMediaTypesCache cache = new AcceptedContentMediaTypesCache(props, adjuster);
        cache.refresh();

        assertTrue(cache.isSupported("application/pdf"));
        assertFalse(cache.isSupported("video/mp4"));

        // Shut down the only server
        server1.shutdown();
        server1 = null;

        cache.refresh();

        // Previous cache is preserved
        assertTrue(cache.isSupported("application/pdf"));
        assertFalse(cache.isSupported("video/mp4"));
    }

    @Test
    void singleBaseUrl_backwardsCompatible() throws IOException
    {
        server1 = startMockServer(TIKA_RESPONSE);

        AcceptedContentMediaTypesCacheProperties props = new AcceptedContentMediaTypesCacheProperties();
        props.setBaseUrl(serverUrl(server1));
        props.setRetryMaxAttempts(1);

        AcceptedContentMediaTypesCache cache = new AcceptedContentMediaTypesCache(props, adjuster);
        cache.refresh();

        assertTrue(cache.isSupported("application/pdf"));
        assertFalse(cache.isSupported("image/png"));
    }

    @Test
    void allEndpointsFailOnFirstRefresh_cacheRemainsEmpty_isSupportedReturnsTrue()
    {
        AcceptedContentMediaTypesCacheProperties props = new AcceptedContentMediaTypesCacheProperties();
        props.setBaseUrl("http://127.0.0.1:19999/transform/config");
        props.setRetryMaxAttempts(1);

        AcceptedContentMediaTypesCache cache = new AcceptedContentMediaTypesCache(props, adjuster);
        cache.refresh();

        // Empty cache -> accept all (safe fallback)
        assertTrue(cache.isSupported("anything/at-all"));
    }

    @AfterEach
    void shutdown() throws IOException
    {
        if (server1 != null)
        {
            server1.shutdown();
        }
        if (server2 != null)
        {
            server2.shutdown();
        }
    }

    private MockWebServer startMockServer(String responseBody) throws IOException
    {
        MockWebServer server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override
            public @NotNull MockResponse dispatch(@NotNull RecordedRequest request)
            {
                return new MockResponse()
                        .setBody(responseBody)
                        .addHeader("Content-Type", "application/json")
                        .setResponseCode(HttpStatus.OK.value());
            }
        });
        server.start();
        return server;
    }

    private String serverUrl(MockWebServer server)
    {
        return "http://127.0.0.1:" + server.getPort() + "/transform/config";
    }
}
