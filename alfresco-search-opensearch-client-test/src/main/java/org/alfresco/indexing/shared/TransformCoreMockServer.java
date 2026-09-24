/*-
 * #%L
 * Alfresco Search Community :: OpenSearch Client :: Test
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
package org.alfresco.indexing.shared;

import java.io.IOException;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpStatus;

public class TransformCoreMockServer
{

    private String body = "{\n"
            + "  \"transformers\": [\n"
            + "    {\n"
            + "      \"supportedSourceAndTargetList\": [\n"
            + "        {\n"
            + "          \"sourceMediaType\": \"text/x-setext\",\n"
            + "          \"targetMediaType\": \"text/plain\",\n"
            + "          \"maxSourceSizeBytes\": -1,\n"
            + "          \"priority\": 50\n"
            + "        },\n"
            + "        {\n"
            + "          \"sourceMediaType\": \"image/png\",\n"
            + "          \"targetMediaType\": \"image/jpeg\",\n"
            + "          \"maxSourceSizeBytes\": -1,\n"
            + "          \"priority\": 50\n"
            + "        },\n"
            + "        {\n"
            + "          \"sourceMediaType\": \"application/pdf\",\n"
            + "          \"targetMediaType\": \"text/plain\",\n"
            + "          \"maxSourceSizeBytes\": -1,\n"
            + "          \"priority\": 50\n"
            + "        }\n"
            + "      ]\n"
            + "    }\n"
            + "  ]\n"
            + "}";

    private MockWebServer targetMediaTypeMockServer = new MockWebServer();
    private final int port;

    public TransformCoreMockServer(int port)
    {
        this.port = port;
    }

    public void start() throws IOException
    {
        this.targetMediaTypeMockServer.start(port);

        this.targetMediaTypeMockServer.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(@NotNull RecordedRequest recordedRequest)
            {
                return new MockResponse()
                        .setBody(body)
                        .addHeader("Content-Type", "application/json")
                        .setResponseCode(HttpStatus.OK.value());
            }
        });
    }

    public void shutdown() throws IOException
    {
        this.targetMediaTypeMockServer.shutdown();
    }
}
