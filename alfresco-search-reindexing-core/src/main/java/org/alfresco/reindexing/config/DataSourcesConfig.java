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

import javax.sql.DataSource;

import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.configuration.BatchConfigurationException;
import org.springframework.batch.core.configuration.support.JdbcDefaultBatchConfiguration;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.util.backoff.ExponentialBackOff;

import org.alfresco.metrics.ReindexingMetrics;
import org.alfresco.reindexing.retry.LoggingRetryListener;
import org.alfresco.reindexing.retry.RetryProxyFactory;

/**
 * Configuration overriding default beans for datasources, needed in {@link org.alfresco.reindexing.config.ElasticsearchDatasourceBatchConfig} for initializing batch job metadata with a script
 */
@Slf4j
@Configuration
public class DataSourcesConfig extends JdbcDefaultBatchConfiguration
{
    private final ReindexingCoreProperties reindexProperties;
    private final ExponentialBackOff exponentialBackOff;
    private final ReindexingMetrics reindexingMetrics;

    public DataSourcesConfig(ReindexingCoreProperties reindexProperties, ExponentialBackOff exponentialBackOff,
            ReindexingMetrics reindexingMetrics)
    {
        super();
        this.reindexProperties = reindexProperties;
        this.exponentialBackOff = exponentialBackOff;
        this.reindexingMetrics = reindexingMetrics;
    }

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource")
    public DataSourceProperties dataSourceProperties()
    {
        return new DataSourceProperties();
    }

    @Bean
    @ConfigurationProperties("spring.batch.datasource")
    public DataSourceProperties batchDataSourceProperties()
    {
        return new DataSourceProperties();
    }

    @Bean(name = "dataSource")
    @Primary
    @ConfigurationProperties("spring.datasource.hikari")
    public DataSource dataSource()
    {
        return dataSourceProperties().initializeDataSourceBuilder().build();
    }

    /**
     * Bean providing datasource for storing batch job metadata.
     * <p>
     * In standalone mode this is typically HSQLDB in-memory. In remote partitioning mode it is typically a shared database. The {@code @ConfigurationProperties} annotation allows HikariCP pool settings to be configured via {@code spring.batch.datasource.hikari.*} properties, which is essential for resilience when the batch datasource is a remote database behind a potentially unstable network.
     *
     * @return the batch DataSource
     */
    @Override
    @Bean(name = "batchDataSource")
    @ConfigurationProperties("spring.batch.datasource.hikari")
    protected DataSource getDataSource()
    {
        return batchDataSourceProperties().initializeDataSourceBuilder().build();
    }

    /**
     * Primary transaction manager backed by the database (Alfresco repository) DataSource.
     * <p>
     * This is used by the auto-configured {@link org.springframework.transaction.support.TransactionTemplate} and therefore by {@code MetadataRepositoryTransactionDecorator} which wraps every DB read in a transaction. Using a dedicated database transaction manager ensures that:
     * <ul>
     * <li>Each database read gets its own database connection from HikariCP, managed in an independent transaction from the chunk's HSQLDB transaction.</li>
     * <li>When a connection breaks due to transient network issues, the next retry starts a fresh transaction with a new connection instead of reusing the broken one that was bound to the outer chunk transaction's synchronization context.</li>
     * </ul>
     */
    @Bean(name = "transactionManager")
    @Primary
    public PlatformTransactionManager transactionManager()
    {
        return new DataSourceTransactionManager(dataSource());
    }

    /**
     * Transaction manager backed by the batch DataSource.
     * <p>
     * This overrides {@link JdbcDefaultBatchConfiguration#getTransactionManager()} so that the Spring Batch infrastructure (JobRepository, JobExplorer, etc.) uses the batch DataSource for its internal transaction management. In standalone mode this is HSQLDB in-memory; in remote partitioning mode this is a shared database.
     */
    @Override
    @Bean(name = "batchTransactionManager")
    public PlatformTransactionManager getTransactionManager()
    {
        return new DataSourceTransactionManager(getDataSource());
    }

    /**
     * Wraps the default {@link JobRepository} with a retry proxy to tolerate transient network failures against the shared batch database.
     * <p>
     * In remote partitioning mode the batch metadata database is a shared database instance accessed over the network. Transient connection issues (timeouts, resets) during JobRepository operations (e.g. updating step execution status) would otherwise cause the step to transition to an unrecoverable UNKNOWN state, breaking the entire partitioned job. The retry proxy transparently retries failed calls with exponential back-off, allowing the partitioning coordination to survive brief network disruptions.
     */
    @Override
    @Bean
    public JobRepository jobRepository() throws BatchConfigurationException
    {
        JobRepository delegate = super.jobRepository();

        if (reindexProperties.isRetryingEnabled())
        {
            return RetryProxyFactory.createRetryProxy(delegate, JobRepository.class, exponentialBackOff,
                    new LoggingRetryListener(null, reindexingMetrics));
        }

        return delegate;
    }

}
