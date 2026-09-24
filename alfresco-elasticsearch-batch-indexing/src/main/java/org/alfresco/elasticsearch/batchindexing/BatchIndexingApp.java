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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Import;

import org.alfresco.elasticsearch.batchindexing.config.ContinuousReindexingProperties;
import org.alfresco.reindexing.config.DataSourcesConfig;
import org.alfresco.reindexing.config.ElasticsearchDatasourceBatchConfig;
import org.alfresco.reindexing.config.ElasticsearchReindexByDateJobConfig;
import org.alfresco.reindexing.config.ElasticsearchReindexByIdJobConfig;
import org.alfresco.reindexing.config.MetadataRepositoryConfig;
import org.alfresco.reindexing.config.PrefixNamespaceConfig;
import org.alfresco.reindexing.config.ReindexingCoreProperties;
import org.alfresco.reindexing.config.RetryBackOffConfig;
import org.alfresco.reindexing.config.StandaloneReindexNodeRole;
import org.alfresco.reindexing.writers.RepositoryDescriptorItemWriter;

// scanBasePackages keeps enterprise reindexing @Configuration classes (like
// ElasticsearchReindexBatchConfig and JmsConfig) out of the context;
// what we need from the reindexing module is pulled in via @Import below.
@SpringBootApplication(
        scanBasePackages = "org.alfresco.elasticsearch.batchindexing",
        exclude = {DataSourceAutoConfiguration.class})
@EnableConfigurationProperties(ReindexingCoreProperties.class)
@Import({
        StandaloneReindexNodeRole.class,
        ElasticsearchReindexByDateJobConfig.class,
        ElasticsearchReindexByIdJobConfig.class,
        PrefixNamespaceConfig.class,
        RetryBackOffConfig.class,
        DataSourcesConfig.class,
        ElasticsearchDatasourceBatchConfig.class,
        MetadataRepositoryConfig.class,
        RepositoryDescriptorItemWriter.class,
})
public class BatchIndexingApp implements ApplicationRunner
{
    private static final Logger LOGGER = LoggerFactory.getLogger(BatchIndexingApp.class);

    private final ReindexingCoreProperties reindexingProperties;
    private final ContinuousReindexingProperties continuousReindexingProperties;

    public BatchIndexingApp(
            ReindexingCoreProperties reindexingProperties,
            ContinuousReindexingProperties continuousReindexingProperties)
    {
        this.reindexingProperties = reindexingProperties;
        this.continuousReindexingProperties = continuousReindexingProperties;
    }

    public static void main(String[] args)
    {
        new SpringApplicationBuilder(BatchIndexingApp.class)
                .web(WebApplicationType.SERVLET)
                .run(args);
    }

    @Override
    public void run(ApplicationArguments args)
    {
        LOGGER.info("Settings: {}", reindexingProperties);
        LOGGER.info("Continuous mode settings: {}", continuousReindexingProperties);
    }
}
