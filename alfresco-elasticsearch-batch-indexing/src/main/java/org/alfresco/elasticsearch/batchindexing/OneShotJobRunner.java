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

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import org.alfresco.reindexing.config.ReindexingCoreProperties;

/**
 * Runs one bounded reindex job and exits. Present only when {@code alfresco.reindex.jobName} is set (blank = continuous, the app default), so its mere existence means "one-shot run". Mirrors the reindexing module's {@code ReindexingApp}: launch the named job, wait for the final Prometheus scrape, then {@code System.exit} with a status reflecting the job outcome — using batch-indexing's own {@link JobLauncher} (proven by {@link ContinuousReindexingService}) instead of {@code JobOperator}.
 * <p>
 * The job is resolved by its Spring Batch name ({@code reindexByDate} / {@code reindexByIds} / {@code reindexDlq}), matching how the enterprise reindexing app selects work — so {@code alfresco.reindex.jobName} migrates across verbatim.
 */
@Component
@ConditionalOnExpression("!'${alfresco.reindex.jobName:}'.isEmpty()")
public class OneShotJobRunner implements ApplicationRunner
{
    private static final Logger LOGGER = LoggerFactory.getLogger(OneShotJobRunner.class);

    /** Kept in sync with the reindexing module's default so shutdown behaviour matches whether or not the property is set. */
    private static final long DEFAULT_SCRAPE_INTERVAL_SECONDS = 30L;
    private static final long SHUTDOWN_SCRAPE_BUFFER_SECONDS = 15L;

    private final ReindexingCoreProperties reindexingProperties;
    private final ConfigurableApplicationContext context;
    private final JobLauncher jobLauncher;
    private final List<Job> jobs;

    public OneShotJobRunner(
            ReindexingCoreProperties reindexingProperties,
            ConfigurableApplicationContext context,
            JobLauncher jobLauncher,
            List<Job> jobs)
    {
        this.reindexingProperties = reindexingProperties;
        this.context = context;
        this.jobLauncher = jobLauncher;
        this.jobs = jobs;
    }

    @Override
    public void run(ApplicationArguments args)
    {
        String jobName = reindexingProperties.getJobName();

        int outcome;
        try
        {
            outcome = launch(jobName);
        }
        catch (Exception e)
        {
            LOGGER.error("One-shot job '{}' failed", jobName, e);
            outcome = 1;
        }
        final int exitCode = outcome;

        waitForFinalPrometheusScrape(context.getEnvironment());
        System.exit(SpringApplication.exit(context, () -> exitCode));
    }

    // Package-private (not private) so tests can exercise job resolution/launch without
    // going through run()'s System.exit.
    @SuppressWarnings("PMD.SignatureDeclareThrowsException")
    int launch(String jobName) throws Exception
    {
        Job job = jobs.stream()
                .filter(j -> j.getName().equals(jobName))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No job named '" + jobName + "'. alfresco.reindex.jobName must be one of: "
                                + jobs.stream().map(Job::getName).sorted().collect(Collectors.joining(", "))));

        LOGGER.info("Starting one-shot job '{}'", jobName);
        JobExecution execution = jobLauncher.run(job, new JobParametersBuilder()
                .addLong("run.id", System.currentTimeMillis())
                .toJobParameters());

        BatchStatus status = execution.getStatus();
        LOGGER.info("One-shot job '{}' finished with status {}", jobName, status);
        return status == BatchStatus.COMPLETED ? 0 : 1;
    }

    private static void waitForFinalPrometheusScrape(Environment environment)
    {
        long waitSeconds = resolveShutdownWaitSeconds(environment);
        if (waitSeconds <= 0)
        {
            return;
        }

        LOGGER.info("One-shot job finished. Waiting {}s before shutdown so Prometheus can scrape final metrics.", waitSeconds);
        try
        {
            TimeUnit.SECONDS.sleep(waitSeconds);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
    }

    static long resolveShutdownWaitSeconds(Environment environment)
    {
        if (!isPrometheusEndpointExposed(environment))
        {
            return 0L;
        }

        long scrapeIntervalSeconds = environment.getProperty(
                "alfresco.reindex.prometheusScrapeIntervalSeconds", Long.class, DEFAULT_SCRAPE_INTERVAL_SECONDS);
        if (scrapeIntervalSeconds <= 0L)
        {
            return 0L;
        }

        return scrapeIntervalSeconds + SHUTDOWN_SCRAPE_BUFFER_SECONDS;
    }

    private static boolean isPrometheusEndpointExposed(Environment environment)
    {
        String included = environment.getProperty("management.endpoints.web.exposure.include");
        if (included == null || included.isBlank())
        {
            return false;
        }
        Set<String> exposed = Arrays.stream(included.split(","))
                .map(String::trim)
                .map(token -> token.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        return exposed.contains("*") || exposed.contains("prometheus");
    }
}
