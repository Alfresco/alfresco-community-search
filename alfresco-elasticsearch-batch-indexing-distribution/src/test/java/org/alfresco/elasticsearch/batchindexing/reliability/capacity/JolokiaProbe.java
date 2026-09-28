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
package org.alfresco.elasticsearch.batchindexing.reliability.capacity;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.NoHttpResponseException;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thin HTTP client over the Jolokia JVM agent. Reads JMX attributes through Jolokia's /read endpoint and returns the value node. Callers map it.
 */
final class JolokiaProbe implements AutoCloseable
{
    private static final Logger LOG = LoggerFactory.getLogger(JolokiaProbe.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    // Each value represents backoff in ms after a particular attempt has failed
    private static final long[] ATTEMPTS_BACKOFF_MS = {500, 1000, 2000, 5000};

    private final URI baseUri;
    private final CloseableHttpClient httpClient;

    JolokiaProbe(String host, int port)
    {
        this.baseUri = URI.create("http://" + host + ":" + port + "/jolokia");
        // 30s sample interval lets docker-proxy / JVM keep-alive close idle
        // pooled connections between calls. Skip the pool — fresh socket per
        // request, no half-closed reuse.
        this.httpClient = HttpClients.custom()
                .setConnectionReuseStrategy((request, response, context) -> false)
                .build();
    }

    HeapSnapshot readHeap() throws IOException
    {
        JsonNode value = read("java.lang:type=Memory", "HeapMemoryUsage");
        return new HeapSnapshot(
                value.path("init").asLong(),
                value.path("used").asLong(),
                value.path("committed").asLong(),
                value.path("max").asLong());
    }

    HeapSnapshot readNonHeap() throws IOException
    {
        JsonNode value = read("java.lang:type=Memory", "NonHeapMemoryUsage");
        return new HeapSnapshot(
                value.path("init").asLong(),
                value.path("used").asLong(),
                value.path("committed").asLong(),
                value.path("max").asLong());
    }

    /**
     * Reads all GarbageCollector MBeans via a pattern read and returns a map keyed by full ObjectName. Counts and times are cumulative since JVM start.
     */
    Map<String, GcStats> readGcStats() throws IOException
    {
        JsonNode value = read("java.lang:type=GarbageCollector,name=*", "CollectionCount,CollectionTime");
        Map<String, GcStats> result = new LinkedHashMap<>();
        value.fields().forEachRemaining(entry -> {
            JsonNode attrs = entry.getValue();
            result.put(entry.getKey(),
                    new GcStats(
                            attrs.path("CollectionCount").asLong(),
                            attrs.path("CollectionTime").asLong()));
        });
        return result;
    }

    /**
     * Asks the JVM to run a full GC via the standard MemoryMXBean.gc() operation. The call is a hint, not a guarantee, but G1 honors it as a Full GC. Use to clear transient first-pass allocations before steady-state sampling.
     */
    void triggerGc() throws IOException
    {
        String tag = "java.lang:type=Memory/gc";
        String body = httpGetWithRetry(buildExecUrl("java.lang:type=Memory", "gc"), tag);
        parseJolokiaValue(body, tag);
    }

    private JsonNode read(String mbean, String attribute) throws IOException
    {
        String tag = mbean + "/" + attribute;
        String body = httpGetWithRetry(buildReadUrl(mbean, attribute), tag);
        return parseJolokiaValue(body, tag);
    }

    private String buildReadUrl(String mbean, String attribute)
    {
        String encodedMbean = URLEncoder.encode(mbean, StandardCharsets.UTF_8).replace("+", "%20");
        String encodedAttr = URLEncoder.encode(attribute, StandardCharsets.UTF_8).replace("+", "%20");
        return baseUri + "/read/" + encodedMbean + "/" + encodedAttr;
    }

    private String buildExecUrl(String mbean, String operation)
    {
        String encodedMbean = URLEncoder.encode(mbean, StandardCharsets.UTF_8).replace("+", "%20");
        String encodedOp = URLEncoder.encode(operation, StandardCharsets.UTF_8).replace("+", "%20");
        return baseUri + "/exec/" + encodedMbean + "/" + encodedOp;
    }

    /** HTTP GET with retry on transport-level transients. Returns the response body on HTTP 200. */
    private String httpGetWithRetry(String url, String tag) throws IOException
    {
        IOException lastFailure = null;
        for (int attempt = 0; attempt <= ATTEMPTS_BACKOFF_MS.length; attempt++)
        {
            try
            {
                return httpClient.execute(new HttpGet(url), (ClassicHttpResponse response) -> {
                    int code = response.getCode();
                    String body = response.getEntity() == null ? "" : EntityUtils.toString(response.getEntity());
                    if (code != 200)
                    {
                        throw new IOException("Jolokia HTTP " + code + " for " + tag + ": " + body);
                    }
                    return body;
                });
            }
            catch (NoHttpResponseException | java.net.SocketException | java.net.SocketTimeoutException e)
            {
                lastFailure = e;
                if (attempt < ATTEMPTS_BACKOFF_MS.length)
                {
                    long backoffMs = ATTEMPTS_BACKOFF_MS[attempt];
                    LOG.warn("Jolokia probe attempt {}/{} failed for {}: {}. Retrying after {}ms.",
                            attempt + 1, ATTEMPTS_BACKOFF_MS.length + 1, tag, e, backoffMs);
                    sleepBeforeRetry(backoffMs);
                }
            }
        }
        throw new IOException("Jolokia probe failed for " + tag + " after " + ATTEMPTS_BACKOFF_MS.length
                + " attempts. Last cause: " + lastFailure, lastFailure);
    }

    /** Parse Jolokia's response envelope, validate the status field, return the value node. */
    private static JsonNode parseJolokiaValue(String body, String tag) throws IOException
    {
        JsonNode root = MAPPER.readTree(body);
        int status = root.path("status").asInt();
        if (status != 200)
        {
            throw new IOException("Jolokia status " + status + " for " + tag + ": " + body);
        }
        return root.path("value");
    }

    private static void sleepBeforeRetry(long backoffMs) throws IOException
    {
        try
        {
            Thread.sleep(backoffMs);
        }
        catch (InterruptedException ie)
        {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while retrying Jolokia probe", ie);
        }
    }

    @Override
    public void close() throws IOException
    {
        httpClient.close();
    }

    /** Snapshot of a single java.lang.management.MemoryUsage. Bytes. */
    record HeapSnapshot(long init, long used, long committed, long max)
    {
        long usedMb()
        {
            return used / (1024 * 1024);
        }

        long committedMb()
        {
            return committed / (1024 * 1024);
        }
    }

    /** Cumulative GC stats for one GarbageCollector MBean. */
    record GcStats(long count, long timeMs)
    {}
}
