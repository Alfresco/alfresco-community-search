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
package org.alfresco.elasticsearch.batchindexing.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import org.alfresco.database.connector.AlfrescoMetadataRepository;
import org.alfresco.elasticsearch.batchindexing.ContinuousReindexingService;
import org.alfresco.elasticsearch.batchindexing.ElasticsearchWatermarkStore;

/**
 * The continuous scheduler beans are gated by {@code @ConditionalOnExpression("'${alfresco.reindex.jobName:}'.isEmpty()")} so a one-shot job never contends with the scheduler.
 */
@DisplayName("ContinuousReindexingConfig scheduler gating")
class ContinuousReindexingConfigGatingTest
{
    @Nested
    @DisplayName("a one-shot job leaves the scheduler beans out of the real configuration")
    class SchedulerSuppressed
    {
        // The gated beans (continuousReindexingService, elasticsearchWatermarkStore) are excluded when a job
        // is set, so their heavy collaborators (OpenSearchClient, JobLauncher, reindexByDateJob) are never needed.
        private final ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(ContinuousReindexingConfig.class)
                .withBean(AlfrescoMetadataRepository.class, () -> mock(AlfrescoMetadataRepository.class))
                .withBean(ContinuousReindexingProperties.class)
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new);

        private void assertSchedulerAbsent(String jobName)
        {
            runner.withPropertyValues("alfresco.reindex.jobName=" + jobName).run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean(ContinuousReindexingService.class);
                assertThat(context).doesNotHaveBean(ElasticsearchWatermarkStore.class);
            });
        }

        @Test
        @DisplayName("reindexByDate")
        void reindexByDate()
        {
            assertSchedulerAbsent("reindexByDate");
        }

        @Test
        @DisplayName("reindexByIds")
        void reindexByIds()
        {
            assertSchedulerAbsent("reindexByIds");
        }

        @Test
        @DisplayName("reindexDlq")
        void reindexDlq()
        {
            assertSchedulerAbsent("reindexDlq");
        }
    }

    @Nested
    @DisplayName("gating condition semantics (mirrors the expression on the scheduler beans)")
    class ConditionSemantics
    {
        private final ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(GatedMarkerConfig.class);

        // Assertions live in an assert-named helper (like SchedulerSuppressed#assertSchedulerAbsent) so PMD's
        // UnitTestShouldIncludeAssert sees the assertion, which it cannot when assertThat is inside the run() lambda.
        private void assertSchedulerEnabled(String... propertyValues)
        {
            runner.withPropertyValues(propertyValues).run(context -> assertThat(context).hasBean("schedulerMarker"));
        }

        private void assertSchedulerDisabled(String... propertyValues)
        {
            runner.withPropertyValues(propertyValues).run(context -> assertThat(context).doesNotHaveBean("schedulerMarker"));
        }

        @Test
        @DisplayName("jobName unset → scheduler enabled")
        void unsetEnables()
        {
            assertSchedulerEnabled();
        }

        @Test
        @DisplayName("jobName blank → scheduler enabled")
        void blankEnables()
        {
            assertSchedulerEnabled("alfresco.reindex.jobName=");
        }

        @Test
        @DisplayName("jobName set → scheduler disabled")
        void setDisables()
        {
            assertSchedulerDisabled("alfresco.reindex.jobName=reindexByDate");
        }
    }

    /** A stand-in for the scheduler beans carrying the same condition as {@link ContinuousReindexingConfig}. */
    @Configuration
    static class GatedMarkerConfig
    {
        @Bean
        @ConditionalOnExpression("'${alfresco.reindex.jobName:}'.isEmpty()")
        String schedulerMarker()
        {
            return "present";
        }
    }
}
