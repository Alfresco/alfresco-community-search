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

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

import org.alfresco.reindexing.config.ReindexingCoreProperties;

@ExtendWith(MockitoExtension.class)
@DisplayName("OneShotJobRunner")
class OneShotJobRunnerTest
{
    @Mock
    private ConfigurableApplicationContext context;
    @Mock
    private JobLauncher jobLauncher;
    @Mock
    private Job reindexByDateJob;
    @Mock
    private JobExecution jobExecution;

    private OneShotJobRunner runnerWith(Job... jobs)
    {
        return new OneShotJobRunner(new ReindexingCoreProperties(), context, jobLauncher, List.of(jobs));
    }

    @Nested
    @DisplayName("launch(jobName)")
    class Launch
    {
        @Test
        @DisplayName("resolves the job by its Spring Batch name and returns 0 on COMPLETED")
        void completedJobReturnsZero() throws Exception
        {
            when(reindexByDateJob.getName()).thenReturn("reindexByDate");
            when(jobExecution.getStatus()).thenReturn(BatchStatus.COMPLETED);
            when(jobLauncher.run(eq(reindexByDateJob), any())).thenReturn(jobExecution);

            assertThat(runnerWith(reindexByDateJob).launch("reindexByDate")).isZero();
        }

        @Test
        @DisplayName("returns 1 on a non-COMPLETED status")
        void failedJobReturnsOne() throws Exception
        {
            when(reindexByDateJob.getName()).thenReturn("reindexByDate");
            when(jobExecution.getStatus()).thenReturn(BatchStatus.FAILED);
            when(jobLauncher.run(eq(reindexByDateJob), any())).thenReturn(jobExecution);

            assertThat(runnerWith(reindexByDateJob).launch("reindexByDate")).isEqualTo(1);
        }

        @Test
        @DisplayName("throws with the available job names when the requested job is unknown")
        void unknownJobThrows()
        {
            when(reindexByDateJob.getName()).thenReturn("reindexByDate");

            assertThatThrownBy(() -> runnerWith(reindexByDateJob).launch("reindexByIds"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("reindexByIds")
                    .hasMessageContaining("reindexByDate");
        }

        @Test
        @DisplayName("each run gets a unique run.id job parameter and no fromTime/toTime (reader falls back to configured bounds)")
        void passesOnlyRunId() throws Exception
        {
            when(reindexByDateJob.getName()).thenReturn("reindexByDate");
            when(jobExecution.getStatus()).thenReturn(BatchStatus.COMPLETED);
            when(jobLauncher.run(eq(reindexByDateJob), any())).thenReturn(jobExecution);

            runnerWith(reindexByDateJob).launch("reindexByDate");

            verify(jobLauncher).run(eq(reindexByDateJob), argThat(params -> params.getLong("run.id") != null
                    && params.getLong("fromTime") == null
                    && params.getLong("toTime") == null
                    && params.parameters().size() == 1));
        }
    }

    @Nested
    @DisplayName("resolveShutdownWaitSeconds")
    class ShutdownWait
    {
        @Test
        @DisplayName("0 when the Prometheus endpoint is not exposed")
        void zeroWhenNotExposed()
        {
            Environment environment = mock(Environment.class);
            when(environment.getProperty("management.endpoints.web.exposure.include")).thenReturn("health,info");

            assertThat(OneShotJobRunner.resolveShutdownWaitSeconds(environment)).isZero();
        }

        @Test
        @DisplayName("scrape interval + buffer when prometheus is exposed")
        void waitsWhenExposed()
        {
            Environment environment = mock(Environment.class);
            when(environment.getProperty("management.endpoints.web.exposure.include")).thenReturn("health,prometheus");
            when(environment.getProperty("alfresco.reindex.prometheusScrapeIntervalSeconds", Long.class, 30L)).thenReturn(30L);

            assertThat(OneShotJobRunner.resolveShutdownWaitSeconds(environment)).isEqualTo(45L);
        }
    }
}
