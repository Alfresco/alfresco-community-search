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

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.testcontainers.containers.ComposeContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * Standard reliability-IT bootstrap: base compose file (+ optional overrides), the usual env vars ({@code BATCH_INDEXING_TAG}, {@code ALFRESCO_DUMP_SQL_PATH}, {@code POSTGRES_DRIVER_PATH}), a batch-indexing log consumer, and Elasticsearch exposed with a cluster-health wait strategy. Returns a handle that owns the {@link ComposeContainer}, a ready {@link OpenSearchClientHolder}, and a {@link ComposeServiceControl}, all closed in one {@link #close()} call.
 *
 * <p>
 * ITs with special needs (extra exposed services, extra log consumers, wiremock/toxiproxy hookups) use {@link Builder#customize(Consumer)} for the last-mile tweaks.
 */
public final class ReliabilityStack implements AutoCloseable
{
    public static final String ELASTICSEARCH_SERVICE = "elasticsearch-1";
    public static final int ELASTICSEARCH_PORT = 9200;
    public static final String BATCH_INDEXING_SERVICE = "batch-indexing-1";

    private final ComposeContainer environment;
    private final OpenSearchClientHolder osHolder;
    private final ComposeServiceControl serviceControl;
    private final int esPort;

    private ReliabilityStack(ComposeContainer environment,
            OpenSearchClientHolder osHolder,
            ComposeServiceControl serviceControl,
            int esPort)
    {
        this.environment = environment;
        this.osHolder = osHolder;
        this.serviceControl = serviceControl;
        this.esPort = esPort;
    }

    public static Builder builder(Logger log, String baseComposeFile)
    {
        return new Builder(log, baseComposeFile);
    }

    public ComposeContainer compose()
    {
        return environment;
    }

    public OpenSearchClientHolder openSearch()
    {
        return osHolder;
    }

    public ComposeServiceControl serviceControl()
    {
        return serviceControl;
    }

    public int esPort()
    {
        return esPort;
    }

    @Override
    public void close() throws IOException
    {
        try
        {
            osHolder.close();
        }
        finally
        {
            environment.close();
        }
    }

    public static final class Builder
    {
        private final Logger log;
        private final List<File> composeFiles = new ArrayList<>();
        private final Map<String, String> extraEnv = new LinkedHashMap<>();
        private final List<Consumer<ComposeContainer>> customizers = new ArrayList<>();
        private Duration esStartupTimeout = Duration.ofMinutes(3);
        private boolean includeAlfrescoDumpSql = true;
        private boolean includePostgresDriver = true;

        private Builder(Logger log, String baseComposeFile)
        {
            this.log = log;
            this.composeFiles.add(DistributionEnv.composeFile(baseComposeFile));
        }

        /** Add a compose overlay applied on top of the base file (later overlays win). */
        public Builder overrideCompose(String name)
        {
            this.composeFiles.add(DistributionEnv.composeFile(name));
            return this;
        }

        /** Extra env var passed to compose (in addition to the always-wired standard set). */
        public Builder env(String key, String value)
        {
            this.extraEnv.put(key, value);
            return this;
        }

        /**
         * Last-mile hook for tests that need {@code withExposedService(...)}, {@code withLogConsumer(...)}, or other {@link ComposeContainer} calls the builder does not expose directly.
         */
        public Builder customize(Consumer<ComposeContainer> customizer)
        {
            this.customizers.add(customizer);
            return this;
        }

        public Builder esStartupTimeout(Duration timeout)
        {
            this.esStartupTimeout = timeout;
            return this;
        }

        /** Skip the {@code ALFRESCO_DUMP_SQL_PATH} env var — useful for ITs with an empty DB. */
        public Builder withoutAlfrescoDumpSql()
        {
            this.includeAlfrescoDumpSql = false;
            return this;
        }

        /** Skip the {@code POSTGRES_DRIVER_PATH} env var. */
        public Builder withoutPostgresDriver()
        {
            this.includePostgresDriver = false;
            return this;
        }

        public ReliabilityStack start()
        {
            ComposeContainer environment = new ComposeContainer(composeFiles.toArray(File[]::new))
                    .withEnv("BATCH_INDEXING_TAG", DistributionEnv.batchIndexingImageTag())
                    .withLogConsumer(BATCH_INDEXING_SERVICE,
                            new Slf4jLogConsumer(log).withPrefix("batch-indexing"))
                    .withExposedService(ELASTICSEARCH_SERVICE, ELASTICSEARCH_PORT,
                            Wait.forHttp("/_cluster/health")
                                    .forStatusCode(200)
                                    .forPort(ELASTICSEARCH_PORT)
                                    .withStartupTimeout(esStartupTimeout));

            if (includeAlfrescoDumpSql)
            {
                environment.withEnv("ALFRESCO_DUMP_SQL_PATH", DistributionEnv.alfrescoDumpSqlPath().toString());
            }
            if (includePostgresDriver)
            {
                environment.withEnv("POSTGRES_DRIVER_PATH", DistributionEnv.findPostgresDriver().getAbsolutePath());
            }

            extraEnv.forEach(environment::withEnv);
            customizers.forEach(c -> c.accept(environment));

            environment.start();

            int esPort = environment.getServicePort(ELASTICSEARCH_SERVICE, ELASTICSEARCH_PORT);
            OpenSearchClientHolder osHolder = new OpenSearchClientHolder("localhost", esPort);
            ComposeServiceControl serviceControl = new ComposeServiceControl(environment);
            return new ReliabilityStack(environment, osHolder, serviceControl, esPort);
        }
    }
}
