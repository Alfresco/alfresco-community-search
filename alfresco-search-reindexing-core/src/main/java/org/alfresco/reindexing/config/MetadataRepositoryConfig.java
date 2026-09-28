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

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.backoff.ExponentialBackOff;

import org.alfresco.database.connector.AlfrescoMetadataRepository;
import org.alfresco.database.connector.MetadataRepositoryCachingDecorator;
import org.alfresco.database.connector.MetadataRepositoryTransactionDecorator;
import org.alfresco.database.connector.config.DatabaseConfig;
import org.alfresco.database.connector.config.DatabaseProperties;
import org.alfresco.metrics.ReindexingMetrics;
import org.alfresco.reindexing.retry.LoggingRetryListener;
import org.alfresco.reindexing.retry.RetryProxyFactory;

/**
 * Replaces the library's {@link DatabaseConfig} auto-configuration to control the full decoration chain of the {@link AlfrescoMetadataRepository} bean.
 * <p>
 * The resulting decoration order (outermost first) is:
 * <ol>
 * <li><strong>Caching</strong> — {@link MetadataRepositoryCachingDecorator} caches child associations and tags.</li>
 * <li><strong>Retry</strong> — transient failures (network blips, broken connections) are retried with exponential backoff.</li>
 * <li><strong>Transaction</strong> — {@link MetadataRepositoryTransactionDecorator} wraps each DB call in its own transaction, ensuring a fresh connection on each retry.</li>
 * <li><strong>DB call</strong> — the database-specific repository (Postgres/Oracle/SQL Server).</li>
 * </ol>
 * <p>
 * This ordering ensures that on each retry attempt, a brand-new transaction (and therefore a brand-new JDBC connection from HikariCP) is used, maximizing resilience against transient connection failures. The caching layer sits on top so that successful results are cached regardless of how many retries were needed.
 */
@Slf4j
@Configuration
public class MetadataRepositoryConfig
{
    @Bean
    public DatabaseProperties databaseProperties()
    {
        return new DatabaseProperties();
    }

    @Bean
    public AlfrescoMetadataRepository metadataRepository(JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate,
            DatabaseProperties databaseProperties,
            ReindexingCoreProperties reindexProperties,
            ExponentialBackOff exponentialBackOff,
            ReindexingMetrics reindexingMetrics)
    {
        AlfrescoMetadataRepository dbRepo = DatabaseConfig.createDbSpecificRepo(jdbcTemplate, databaseProperties);
        AlfrescoMetadataRepository transactional = new MetadataRepositoryTransactionDecorator(dbRepo, transactionTemplate);
        AlfrescoMetadataRepository retrying = applyRetryingWrapperIfEnabled(transactional, reindexProperties, exponentialBackOff, reindexingMetrics);
        return new MetadataRepositoryCachingDecorator(retrying, databaseProperties);
    }

    private static AlfrescoMetadataRepository applyRetryingWrapperIfEnabled(AlfrescoMetadataRepository metadataRepository,
            ReindexingCoreProperties reindexProperties,
            ExponentialBackOff exponentialBackOff,
            ReindexingMetrics reindexingMetrics)
    {
        if (reindexProperties.isRetryingEnabled())
        {
            return RetryProxyFactory.createRetryProxy(metadataRepository, AlfrescoMetadataRepository.class, exponentialBackOff,
                    new LoggingRetryListener(null, reindexingMetrics));
        }
        return metadataRepository;
    }
}
