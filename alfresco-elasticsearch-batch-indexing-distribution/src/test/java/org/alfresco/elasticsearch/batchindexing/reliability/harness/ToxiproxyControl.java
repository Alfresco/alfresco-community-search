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
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import eu.rekawek.toxiproxy.model.Toxic;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thin wrapper over the Toxiproxy admin API for reliability tests. Assumes a Toxiproxy instance is running (either as a compose service reachable from the test host or a Testcontainers-managed container).
 *
 * <p>
 * Reliability tests use this to inject deterministic infra faults against Postgres, Elasticsearch and ACS. This wrapper deliberately exposes only the small toxic set we actually use; anything richer, drop to {@link #proxy(String)} and use the raw {@link Proxy} API.
 */
public final class ToxiproxyControl implements AutoCloseable
{
    private static final Logger LOG = LoggerFactory.getLogger(ToxiproxyControl.class);
    private static final String LATENCY_TOXIC = "latency";
    private static final String TIMEOUT_TOXIC = "timeout";
    private static final String BANDWIDTH_TOXIC = "bandwidth";

    private final ToxiproxyClient client;
    private final Map<String, Proxy> proxies = new LinkedHashMap<>();

    public ToxiproxyControl(String adminHost, int adminPort)
    {
        this.client = new ToxiproxyClient(adminHost, adminPort);
    }

    /**
     * Register an upstream proxy. {@code listen} is the address Toxiproxy binds inside its own container (e.g. {@code toxiproxy:5432}); {@code upstream} is the real backend (e.g. {@code postgres:5432}). The app-under-test should point at {@code listen}.
     */
    public Proxy register(String name, String listen, String upstream) throws IOException
    {
        Proxy proxy = client.createProxy(name, listen, upstream);
        proxies.put(name, proxy);
        LOG.info("[reliability] Toxiproxy registered proxy '{}' listen={} upstream={}", name, listen, upstream);
        return proxy;
    }

    /**
     * Adopt a proxy that toxiproxy already knows about (typically because the compose file mounted a {@code -config} JSON that pre-declared it). No admin call is made until a toxic is actually attached.
     */
    public Proxy useExisting(String name) throws IOException
    {
        Proxy proxy = client.getProxy(name);
        proxies.put(name, proxy);
        LOG.info("[reliability] Toxiproxy adopted existing proxy '{}'", name);
        return proxy;
    }

    /** Look up a previously registered proxy. */
    public Proxy proxy(String name)
    {
        Proxy proxy = proxies.get(name);
        if (proxy == null)
        {
            throw new IllegalArgumentException("Toxiproxy proxy not registered: " + name);
        }
        return proxy;
    }

    /** Take a proxy down entirely (all in-flight and new connections dropped/refused). */
    public void disable(String name) throws IOException
    {
        proxy(name).disable();
        LOG.info("[reliability] Toxiproxy proxy '{}' disabled", name);
    }

    /** Re-enable a previously {@link #disable(String) disabled} proxy. */
    public void enable(String name) throws IOException
    {
        proxy(name).enable();
        LOG.info("[reliability] Toxiproxy proxy '{}' enabled", name);
    }

    /** Add downstream latency (server-to-client) with optional jitter. Replaces any existing latency toxic. */
    public void addLatency(String name, Duration latency, Duration jitter) throws IOException
    {
        removeToxicIfPresent(name, LATENCY_TOXIC);
        proxy(name).toxics()
                .latency(LATENCY_TOXIC, ToxicDirection.DOWNSTREAM, latency.toMillis())
                .setJitter(jitter.toMillis());
        LOG.info("[reliability] Toxiproxy proxy '{}' latency={}ms jitter={}ms", name, latency.toMillis(), jitter.toMillis());
    }

    /** Stop passing traffic after N bytes then hang (simulates half-open sockets). */
    public void addTimeout(String name, Duration timeout) throws IOException
    {
        removeToxicIfPresent(name, TIMEOUT_TOXIC);
        proxy(name).toxics().timeout(TIMEOUT_TOXIC, ToxicDirection.DOWNSTREAM, timeout.toMillis());
        LOG.info("[reliability] Toxiproxy proxy '{}' timeout={}ms", name, timeout.toMillis());
    }

    /** Rate-limit downstream traffic. */
    public void addBandwidth(String name, long kilobytesPerSecond) throws IOException
    {
        removeToxicIfPresent(name, BANDWIDTH_TOXIC);
        proxy(name).toxics().bandwidth(BANDWIDTH_TOXIC, ToxicDirection.DOWNSTREAM, kilobytesPerSecond);
        LOG.info("[reliability] Toxiproxy proxy '{}' bandwidth={}KB/s", name, kilobytesPerSecond);
    }

    /** Remove every toxic from a proxy without disabling it. */
    public void clearToxics(String name) throws IOException
    {
        for (Toxic toxic : proxy(name).toxics().getAll())
        {
            toxic.remove();
        }
        LOG.info("[reliability] Toxiproxy proxy '{}' toxics cleared", name);
    }

    private void removeToxicIfPresent(String proxyName, String toxicName) throws IOException
    {
        for (Toxic toxic : proxy(proxyName).toxics().getAll())
        {
            if (toxic.getName().equals(toxicName))
            {
                toxic.remove();
                return;
            }
        }
    }

    @Override
    public void close()
    {
        // Best-effort: drop every proxy we registered so a following test starts clean even
        // when Toxiproxy is reused across the suite.
        for (Map.Entry<String, Proxy> entry : proxies.entrySet())
        {
            try
            {
                entry.getValue().delete();
            }
            catch (IOException e)
            {
                LOG.warn("[reliability] Failed to delete toxiproxy proxy '{}' on close: {}", entry.getKey(), e.getMessage());
            }
        }
        proxies.clear();
    }
}
