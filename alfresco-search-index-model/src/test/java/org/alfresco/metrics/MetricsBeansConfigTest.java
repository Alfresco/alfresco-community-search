/*-
 * #%L
 * Alfresco Search Community :: Search Index Model
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
package org.alfresco.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.logging.LoggingMeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/** The naming convention for the tests is: MethodName_StateUnderTest_ExpectedBehavior */
// Assertions run inside the ApplicationContextRunner callback, which PMD's UnitTestShouldIncludeAssert cannot see.
@SuppressWarnings("PMD.UnitTestShouldIncludeAssert")
class MetricsBeansConfigTest
{
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PrimaryRegistryConfig.class, MetricsBeansConfig.class);

    @Test
    void contextLoads_byDefault_registersReindexingMetricsBean()
    {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(ReindexingMetrics.class);
            assertThat(context.getBean(ReindexingMetrics.class)).isInstanceOf(DefaultReindexingMetrics.class);
        });
    }

    @Test
    void loggingMeterRegistry_whenPropertyAbsent_isNotCreated()
    {
        contextRunner.run(context -> assertThat(context).doesNotHaveBean(LoggingMeterRegistry.class));
    }

    @Test
    void loggingMeterRegistry_whenLoggerEnabled_isCreated()
    {
        contextRunner
                .withPropertyValues("alfresco.metrics.logger.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(LoggingMeterRegistry.class));
    }

    /** Supplies the primary {@link MeterRegistry} that {@link MetricsBeansConfig} injects into its metric beans. */
    @Configuration
    static class PrimaryRegistryConfig
    {
        @Bean
        @Primary
        MeterRegistry meterRegistry()
        {
            return new SimpleMeterRegistry();
        }
    }
}
