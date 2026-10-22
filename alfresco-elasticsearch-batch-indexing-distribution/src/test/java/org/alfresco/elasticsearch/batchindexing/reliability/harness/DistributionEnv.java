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

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * System properties, filesystem paths and other environment bits shared across reliability ITs.
 */
public final class DistributionEnv
{
    private static final Path ALFRESCO_DUMP_SQL_PATH = Path
            .of("../alfresco-elasticsearch-batch-indexing/src/test/resources/docker/alfresco-dump.sql")
            .toAbsolutePath()
            .normalize();

    private DistributionEnv()
    {}

    /**
     * Version tag of the locally-built {@code alfresco-elasticsearch-batch-indexing} Docker image. Set by the {@code reliability-tests} Maven profile via failsafe system properties.
     */
    public static String batchIndexingImageTag()
    {
        return requireSystemProperty("batch-indexing.version");
    }

    /** Jolokia JVM agent jar copied by the {@code reliability-tests} profile. */
    public static Path jolokiaAgentPath()
    {
        Path path = Paths.get(requireSystemProperty("jolokia.agent.path"));
        assertThat(path.toFile())
                .as("Jolokia agent jar (expected at %s; copied by reliability-tests profile)", path)
                .exists();
        return path;
    }

    /** Directory for per-run CSVs and other artifacts. Created if missing. */
    public static Path outputDir() throws IOException
    {
        Path dir = Paths.get(System.getProperty("reliability.output.dir", "target/reliability"));
        Files.createDirectories(dir);
        return dir;
    }

    /** Small Alfresco DB dump used by the metadata-only reliability tests. */
    public static Path alfrescoDumpSqlPath()
    {
        assertThat(ALFRESCO_DUMP_SQL_PATH.toFile())
                .as("alfresco-dump.sql must exist in the batch-indexing module")
                .exists();
        return ALFRESCO_DUMP_SQL_PATH;
    }

    /**
     * PostgreSQL JDBC driver jar, located via the {@code org.postgresql.Driver} class' code source. Bind-mounted into the batch-indexing container at {@code /opt/db-drivers/}.
     */
    public static File findPostgresDriver()
    {
        try
        {
            File driverJar = new File(org.postgresql.Driver.class.getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI());
            assertThat(driverJar).as("PostgreSQL driver jar").exists();
            return driverJar;
        }
        catch (URISyntaxException e)
        {
            throw new IllegalStateException("Failed to locate PostgreSQL driver jar", e);
        }
    }

    /** Compose file under {@code src/test/resources/docker/}. */
    public static File composeFile(String relativeName)
    {
        File file = new File("src/test/resources/docker/" + relativeName);
        assertThat(file).as("compose file %s", file).exists();
        return file;
    }

    /**
     * A poison SQL seed shipped under {@code src/test/resources/docker/}. Mounted into Postgres' {@code /docker-entrypoint-initdb.d/} as {@code zz-poison.sql} so it runs after the dump.
     */
    public static Path poisonSqlPath(String relativeName)
    {
        return dockerResourcePath(relativeName);
    }

    /**
     * Absolute path to any file shipped under {@code src/test/resources/docker/}. Use this for supplementary compose init scripts (SQL, config) that aren't strictly "poison".
     */
    public static Path dockerResourcePath(String relativeName)
    {
        Path path = Paths.get("src/test/resources/docker/" + relativeName).toAbsolutePath().normalize();
        assertThat(path.toFile()).as("docker resource %s", path).exists();
        return path;
    }

    /** Toxiproxy proxy declarations mounted into the toxiproxy container in infra-recovery compose. */
    public static Path toxiproxyConfigPath()
    {
        Path path = Paths.get("src/test/resources/docker/toxiproxy-config.json").toAbsolutePath().normalize();
        assertThat(path.toFile()).as("toxiproxy config %s", path).exists();
        return path;
    }

    private static String requireSystemProperty(String key)
    {
        String value = System.getProperty(key);
        assertThat(value)
                .as("System property '%s' must be set (see the reliability-tests Maven profile)", key)
                .isNotBlank();
        return value;
    }
}
