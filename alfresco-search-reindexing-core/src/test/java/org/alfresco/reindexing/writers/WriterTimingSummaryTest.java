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
package org.alfresco.reindexing.writers;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import org.alfresco.metrics.MetricsConstants;

class WriterTimingSummaryTest
{
    @Test
    void buildSummaryWhenEnabledProducesPerWriterStatsWithCorrectPercentiles()
    {
        WriterTimingSummary summary = new WriterTimingSummary(true);
        // 10 samples 10..100 -> count=10, total=550, min=10, max=100, avg=55.0,
        // nearest-rank: median(p50)=50, p95=100, p99=100
        for (int i = 1; i <= 10; i++)
        {
            summary.record(MetricsConstants.METADATA, i * 10L);
        }

        List<String> lines = summary.buildSummaryAndReset("workerStep (jobExecutionId=42)");

        assertThat(lines).hasSize(1);
        String line = lines.get(0);
        assertThat(line).contains("[ElasticsearchRepoEventItemWriter]");
        assertThat(line).contains("count=10");
        assertThat(line).contains("total=550");
        assertThat(line).contains("min=10");
        assertThat(line).contains("max=100");
        assertThat(line).contains("avg=55.0");
        assertThat(line).contains("median=50");
        assertThat(line).contains("p95=100");
        assertThat(line).contains("p99=100");
        assertThat(line).contains("workerStep (jobExecutionId=42)");
    }

    @Test
    void buildSummaryOrdersWritersMetadataThenPathThenContent()
    {
        WriterTimingSummary summary = new WriterTimingSummary(true);
        summary.record(MetricsConstants.CONTENT, 5L);
        summary.record(MetricsConstants.PATH, 5L);
        summary.record(MetricsConstants.METADATA, 5L);

        List<String> lines = summary.buildSummaryAndReset("ctx");

        assertThat(lines).hasSize(3);
        assertThat(lines.get(0)).contains("ElasticsearchRepoEventItemWriter");
        assertThat(lines.get(1)).contains("ElasticsearchPathWriter");
        assertThat(lines.get(2)).contains("ContentTransformEventWriter");
    }

    @Test
    void buildSummaryResetsSamplesSoASecondCallReturnsNothing()
    {
        WriterTimingSummary summary = new WriterTimingSummary(true);
        summary.record(MetricsConstants.PATH, 5L);
        summary.buildSummaryAndReset("first");

        assertThat(summary.buildSummaryAndReset("second")).isEmpty();
    }

    @Test
    void buildSummaryLabelsMergedWriterByClassNameNotRawKey()
    {
        WriterTimingSummary summary = new WriterTimingSummary(true);
        summary.record(MetricsConstants.MERGED, 5L);

        List<String> lines = summary.buildSummaryAndReset("ctx");

        assertThat(lines).hasSize(1);
        assertThat(lines.get(0)).contains("[ElasticsearchMergedReindexWriter]");
        assertThat(lines.get(0)).doesNotContain("[merged]");
    }

    @Test
    void whenDisabledRecordsNothingAndReturnsNoLines()
    {
        WriterTimingSummary summary = new WriterTimingSummary(false);
        summary.record(MetricsConstants.METADATA, 100L);

        assertThat(summary.buildSummaryAndReset("ctx")).isEmpty();
    }
}
