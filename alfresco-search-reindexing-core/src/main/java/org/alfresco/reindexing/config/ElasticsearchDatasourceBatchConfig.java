/*-
 * #%L
 * Alfresco Search Community :: Reindexing Core
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
package org.alfresco.reindexing.config;

import java.sql.SQLException;
import javax.sql.DataSource;
import jakarta.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/**
 * Configure Spring Batch to use a dedicated data source
 */
@Configuration
public class ElasticsearchDatasourceBatchConfig
{
    private static final String MYSQL_PRODUCT_NAME = "MySQL";
    private static final String MYSQL_SEQUENCE_RECONCILIATION = "classpath:/org/alfresco/reindexing/config/reconcile-mysql-job-instance-sequence.sql";

    private final Environment env;

    private final ResourceLoader resourceLoader;

    private final DataSource batchDataSource;

    public ElasticsearchDatasourceBatchConfig(Environment env,
            ResourceLoader resourceLoader,
            @Qualifier("batchDataSource") DataSource batchDataSource)
    {
        this.env = env;
        this.resourceLoader = resourceLoader;
        this.batchDataSource = batchDataSource;
    }

    @PostConstruct
    protected void batchDataSourceInitialize()
    {
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
        boolean mysql;
        try (var connection = batchDataSource.getConnection())
        {
            mysql = MYSQL_PRODUCT_NAME.equals(connection.getMetaData().getDatabaseProductName());
        }
        catch (SQLException exception)
        {
            throw new IllegalStateException("Unable to determine the batch database type for Spring Batch schema initialization", exception);
        }
        populator.addScript(resourceLoader.getResource(env.getProperty("spring.batch.schema.script")));
        populator.setContinueOnError(true);
        DatabasePopulatorUtils.execute(populator, batchDataSource);

        if (mysql)
        {
            // Preserve rollback compatibility and repair a Batch 6 sequence created at zero.
            ResourceDatabasePopulator reconciliation = new ResourceDatabasePopulator();
            reconciliation.addScript(resourceLoader.getResource(MYSQL_SEQUENCE_RECONCILIATION));
            DatabasePopulatorUtils.execute(reconciliation, batchDataSource);
        }
    }
}
