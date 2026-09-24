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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import org.alfresco.content.mediatype.AcceptedContentMediaTypesCache;
import org.alfresco.content.mediatype.AcceptedContentMediaTypesCacheProperties;
import org.alfresco.indexing.shared.TransformCoreMockServer;
import org.alfresco.indexing.shared.mtls.WebClientBuilderAdjuster;
import org.alfresco.indexing.shared.mtls.config.MTLSConfig;

/*
Testing a transform response where only pdf files can be transformed into plaintext.
Before the refresh method tries to connect to Transform Service multiple times it's better
to have a timeout in order to avoid useless long-running tests and highlight the issue in case of failing tests.
 */
@Timeout(10L)
@SpringBootTest(classes = MTLSConfig.class)
class AcceptedContentMediaTypesCacheTest
{

    private final int port = 9988;
    private final AcceptedContentMediaTypesCacheProperties properties = new AcceptedContentMediaTypesCacheProperties() {
        {
            setBaseUrl("http://127.0.0.1:" + port + "/transform/config");

        }
    };
    private final TransformCoreMockServer transformCoreMockServer = new TransformCoreMockServer(port);

    @Autowired
    private WebClientBuilderAdjuster adjuster;

    @Test
    public void isSupportedTrueTest() throws IOException
    {
        AcceptedContentMediaTypesCache acceptedContentMediaTypesCache = new AcceptedContentMediaTypesCache(properties, adjuster);

        initMockWebServer();
        acceptedContentMediaTypesCache.refresh();
        assertTrue(acceptedContentMediaTypesCache.isSupported("application/pdf"));
    }

    @Test
    public void isSupportedFalseTest() throws IOException
    {
        AcceptedContentMediaTypesCache acceptedContentMediaTypesCache = new AcceptedContentMediaTypesCache(properties, adjuster);

        initMockWebServer();
        acceptedContentMediaTypesCache.refresh();
        assertFalse(acceptedContentMediaTypesCache.isSupported("image/png"));
    }

    @Test
    public void returnsTrueWhenCacheNotInitialized() throws IOException
    {
        AcceptedContentMediaTypesCache acceptedContentMediaTypesCache = new AcceptedContentMediaTypesCache(properties, adjuster);

        initMockWebServer();
        assertTrue(acceptedContentMediaTypesCache.isSupported("image/png"));
        acceptedContentMediaTypesCache.refresh();
        assertFalse(acceptedContentMediaTypesCache.isSupported("image/png"));
    }

    /* The webserver isn't initialized at first so we get a connection error during the sleep. Webserver gets initialized but cache is still empty so first assertion will be true by default. Then refreshing cache so the correct assertion is done. */
    @Test
    public void checkRetryStrategy() throws InterruptedException
    {
        AcceptedContentMediaTypesCache acceptedContentMediaTypesCache = new AcceptedContentMediaTypesCache(properties, adjuster);

        AtomicReference<Exception> serverStartFailure = new AtomicReference<>();
        Thread starter = new Thread(() -> {
            try
            {
                Thread.sleep(1000L);
                initMockWebServer();
            }
            catch (IOException | InterruptedException e)
            {
                serverStartFailure.set(e);
            }
        });
        starter.start();

        assertTrue(acceptedContentMediaTypesCache.isSupported("image/png"));
        acceptedContentMediaTypesCache.refresh();
        starter.join();
        assertNull(serverStartFailure.get(), "Mock server failed to start");
        assertFalse(acceptedContentMediaTypesCache.isSupported("image/png"));
    }

    @AfterEach
    public void shutdown() throws IOException
    {
        transformCoreMockServer.shutdown();
    }

    private void initMockWebServer() throws IOException
    {
        transformCoreMockServer.start();
    }

}
