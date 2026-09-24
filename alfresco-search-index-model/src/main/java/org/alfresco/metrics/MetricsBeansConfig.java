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

import java.time.Duration;

import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.logging.LoggingMeterRegistry;
import io.micrometer.core.instrument.logging.LoggingRegistryConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MetricsBeansConfig
{

    /**
     * Number of most-recent reindex job executions whose per-job metrics are retained.
     * <p>
     * Continuous batch-indexing launches a new Spring Batch job (a new {@code jobId}) every polling cycle, so retaining every job's meters forever would leak memory; only the most recent N are kept. Defaults to {@value DefaultReindexingMetrics#DEFAULT_MAX_RETAINED_JOBS} when the property is absent (keep this literal in sync with that constant). Set to {@code 0} (or a negative value) to disable the cap and retain all jobs (unbounded).
     */
    @Value("${alfresco.metrics.reindex.max-retained-jobs:50}")
    private int maxRetainedReindexJobs;

    @Bean
    @ConditionalOnProperty(name = "alfresco.metrics.logger.enabled", havingValue = "true")
    public LoggingMeterRegistry loggingMeterRegistry()
    {
        LoggingRegistryConfig config = new LoggingRegistryConfig() {
            @Override
            public String get(String key)
            {
                return null;
            }

            @Override
            public Duration step()
            {
                return Duration.ofSeconds(30);
            }

            @Override
            public boolean logInactive()
            {
                return true;
            }
        };
        return new LoggingMeterRegistry(config, Clock.SYSTEM);
    }

    @Bean
    public ReindexingMetrics reindexingMetrics(MeterRegistry meterRegistry)
    {
        return new DefaultReindexingMetrics(meterRegistry, maxRetainedReindexJobs);
    }
}
