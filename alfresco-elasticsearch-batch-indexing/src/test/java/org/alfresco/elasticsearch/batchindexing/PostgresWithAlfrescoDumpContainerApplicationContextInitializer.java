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
package org.alfresco.elasticsearch.batchindexing;

import static org.testcontainers.utility.DockerImageName.parse;

import java.time.Duration;

import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Starts a PostgreSQL container with Alfresco's SQL dump pre-loaded. The dump lives in the reindexing module — referenced via a relative path from the batch-indexing module directory (Maven sets CWD to the module root when running tests).
 */
public class PostgresWithAlfrescoDumpContainerApplicationContextInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext>
{

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(parse("postgres:16.5").asCompatibleSubstituteFor("postgres"))
            .withFileSystemBind("src/test/resources/docker/alfresco-dump.sql",
                    "/docker-entrypoint-initdb.d/init-postgres.sql", BindMode.READ_ONLY)
            .withPassword("alfresco")
            .withUsername("alfresco")
            .withDatabaseName("alfresco")
            .withCommand("-N 500")
            .withStartupTimeout(Duration.ofMinutes(2))
            .withReuse(true);

    @Override
    public void initialize(ConfigurableApplicationContext context)
    {
        if (!POSTGRES.isRunning())
        {
            POSTGRES.start();
        }

        TestPropertyValues.of(
                "spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "spring.datasource.username=" + POSTGRES.getUsername(),
                "spring.datasource.password=" + POSTGRES.getPassword())
                .applyTo(context);
    }
}
