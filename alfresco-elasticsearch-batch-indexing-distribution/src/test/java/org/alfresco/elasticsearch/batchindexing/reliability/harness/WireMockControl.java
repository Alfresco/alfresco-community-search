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

import com.github.tomakehurst.wiremock.client.WireMock;

/**
 * Thin wrapper around a remote {@link WireMock} standalone server (running as a compose service). Provides a single-shot {@link #resetAll()} that also clears request journal and scenario state — {@code resetToDefaultMappings()} alone leaves scenarios pinned to their last state, which is the usual cross-test contamination trap.
 *
 * <p>
 * Everything else, use {@link #client()} to get the raw {@link WireMock} and drive the standard DSL: {@code control.client().register(get("/foo").willReturn(aResponse().withStatus(500)))}.
 */
public final class WireMockControl
{
    private final WireMock wireMock;

    public WireMockControl(String host, int port)
    {
        this.wireMock = WireMock.create().host(host).port(port).build();
    }

    /** Raw WireMock DSL client. */
    public WireMock client()
    {
        return wireMock;
    }

    /** Wipe stubs, journalled requests and scenario state. */
    public void resetAll()
    {
        wireMock.resetToDefaultMappings();
        wireMock.resetRequests();
        wireMock.resetScenarios();
    }
}
