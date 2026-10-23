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

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Small JDBC helper for lifecycle / infra ITs that need to shape Postgres state at test time (backdating timestamps, seeding rows, reading counts) rather than at DB init.
 * <p>
 * Requires the postgres compose service to expose port 5432 to the host — add {@code .withExposedService("postgres-1", 5432, ...)} to the {@link org.testcontainers.containers.ComposeContainer} builder before {@code .start()}.
 * <p>
 * Kept lightweight on purpose: no connection pool, no HikariCP. Each call opens and closes a connection. All reliability ITs run one call at a time.
 */
public final class PostgresProbe
{
    private static final Logger LOG = LoggerFactory.getLogger(PostgresProbe.class);

    private final String url;
    private final String user;
    private final String password;

    public PostgresProbe(String host, int mappedPort, String database, String user, String password)
    {
        this.url = "jdbc:postgresql://" + host + ":" + mappedPort + "/" + database;
        this.user = user;
        this.password = password;
    }

    /** Convenience: {@code alfresco} DB with the fixture user/password on {@code localhost}. */
    public static PostgresProbe forAlfresco(int mappedPort)
    {
        return new PostgresProbe("localhost", mappedPort, "alfresco", "alfresco", "alfresco");
    }

    /** Execute a statement (DDL or DML) and return the affected row count for the last update. */
    public int execute(String sql)
    {
        LOG.info("[postgres] execute: {}", sql);
        try (Connection conn = DriverManager.getConnection(url, user, password);
                Statement stmt = conn.createStatement())
        {
            stmt.execute(sql);
            return stmt.getUpdateCount();
        }
        catch (SQLException e)
        {
            throw new IllegalStateException("Postgres execute failed: " + sql, e);
        }
    }

    /** Run a query expected to return a single long value in the first column. */
    public long queryLong(String sql)
    {
        try (Connection conn = DriverManager.getConnection(url, user, password);
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery(sql))
        {
            if (!rs.next())
            {
                throw new IllegalStateException("Query returned no rows: " + sql);
            }
            return rs.getLong(1);
        }
        catch (SQLException e)
        {
            throw new IllegalStateException("Postgres queryLong failed: " + sql, e);
        }
    }
}
