/*-
 * #%L
 * Alfresco Search Community :: Batch Indexing
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

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;

import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * PostgreSQL with Alfresco's dump, plus a custom namespace on one node: its type repointed, and a string property added. The dump has neither, so ACS-12851's node-loss case needs them.
 * <p>
 * Its own container, not the shared reusable one: the fixture mutates {@code alf_node} and would otherwise change the node counts other integration tests assert on.
 */
public class PostgresWithCustomNamespaceContainerApplicationContextInitializer
        implements ApplicationContextInitializer<ConfigurableApplicationContext>
{
    /** cm:content node in the dump, retyped by the fixture. */
    static final String CUSTOM_TYPE_NODE_UUID = "93124e0d-94b9-442d-ad44-31df381c621f";
    static final String CUSTOM_TYPE_LOCAL_NAME = "invoice";
    static final String CUSTOM_URI = "http://www.alfresco.org/model/consulting/bulkobject/1.0";
    static final String CUSTOM_PROPERTY_LOCAL_NAME = "invoiceNumber";
    static final String CUSTOM_PROPERTY_VALUE = "INV-0001";

    private static final long BULKOBJECT_NAMESPACE_ID = 21;
    private static final long FIXTURE_QNAME_ID = 9001;
    private static final long FIXTURE_PROPERTY_QNAME_ID = 9002;
    private static final long CUSTOM_TYPE_NODE_ID = 1043;

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            parse("postgres:16.5").asCompatibleSubstituteFor("postgres"))
                    .withFileSystemBind("src/test/resources/docker/alfresco-dump.sql",
                            "/docker-entrypoint-initdb.d/init-postgres.sql", BindMode.READ_ONLY)
                    .withPassword("alfresco")
                    .withUsername("alfresco")
                    .withDatabaseName("alfresco")
                    .withCommand("-N 500")
                    .withStartupTimeout(Duration.ofMinutes(2));

    private static boolean fixtureApplied;

    @Override
    public void initialize(ConfigurableApplicationContext context)
    {
        if (!POSTGRES.isRunning())
        {
            POSTGRES.start();
        }
        applyFixture();

        TestPropertyValues.of(
                "spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "spring.datasource.username=" + POSTGRES.getUsername(),
                "spring.datasource.password=" + POSTGRES.getPassword())
                .applyTo(context);
    }

    private static synchronized void applyFixture()
    {
        if (fixtureApplied)
        {
            return;
        }
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement())
        {
            statement.execute("insert into alf_qname (id, version, ns_id, local_name) values ("
                    + FIXTURE_QNAME_ID + ", 0, " + BULKOBJECT_NAMESPACE_ID + ", '" + CUSTOM_TYPE_LOCAL_NAME + "')");
            statement.execute("update alf_node set type_qname_id = " + FIXTURE_QNAME_ID
                    + " where uuid = '" + CUSTOM_TYPE_NODE_UUID + "'");

            // A single-valued string property, so searchability can be asserted on a predictable value.
            statement.execute("insert into alf_qname (id, version, ns_id, local_name) values ("
                    + FIXTURE_PROPERTY_QNAME_ID + ", 0, " + BULKOBJECT_NAMESPACE_ID + ", '"
                    + CUSTOM_PROPERTY_LOCAL_NAME + "')");
            statement.execute("insert into alf_node_properties"
                    + " (node_id, actual_type_n, persisted_type_n, boolean_value, long_value, float_value,"
                    + " double_value, string_value, qname_id, list_index, locale_id) values ("
                    + CUSTOM_TYPE_NODE_ID + ", 6, 6, false, 0, 0, 0, '" + CUSTOM_PROPERTY_VALUE + "', "
                    + FIXTURE_PROPERTY_QNAME_ID + ", -1, 1)");
        }
        catch (SQLException e)
        {
            throw new IllegalStateException("Could not apply the unmapped-type fixture", e);
        }
        fixtureApplied = true;
    }
}
