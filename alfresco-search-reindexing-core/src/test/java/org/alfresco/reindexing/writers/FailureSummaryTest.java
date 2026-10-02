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

class FailureSummaryTest
{
    @Test
    void buildSummaryWhenEnabledAggregatesCountsPerModuleAndType()
    {
        FailureSummary summary = new FailureSummary(true);
        for (int i = 0; i < 4743; i++)
        {
            summary.record(MetricsConstants.METADATA, "document_parsing_exception");
        }

        List<String> lines = summary.buildSummaryAndReset("workerStep (jobExecutionId=42)");

        assertThat(lines).hasSize(1);
        String line = lines.get(0);
        assertThat(line).contains("[ElasticsearchRepoEventItemWriter]");
        assertThat(line).contains("total=4743");
        assertThat(line).contains("document_parsing_exception=4743");
        assertThat(line).contains("workerStep (jobExecutionId=42)");
    }

    @Test
    void buildSummaryOrdersErrorTypesByCountDescendingThenName()
    {
        FailureSummary summary = new FailureSummary(true);
        summary.record(MetricsConstants.PATH, "version_conflict_engine_exception");
        summary.record(MetricsConstants.PATH, "document_missing_exception");
        summary.record(MetricsConstants.PATH, "document_missing_exception");

        List<String> lines = summary.buildSummaryAndReset("ctx");

        assertThat(lines).hasSize(1);
        String line = lines.get(0);
        assertThat(line).contains("[ElasticsearchPathWriter]");
        assertThat(line).contains("total=3");
        // Highest count first: document_missing_exception=2 before version_conflict_engine_exception=1.
        assertThat(line.indexOf("document_missing_exception=2"))
                .isLessThan(line.indexOf("version_conflict_engine_exception=1"));
    }

    @Test
    void buildSummaryOrdersModulesMetadataThenPathThenMergedThenContent()
    {
        FailureSummary summary = new FailureSummary(true);
        summary.record(MetricsConstants.CONTENT, "err");
        summary.record(MetricsConstants.MERGED, "err");
        summary.record(MetricsConstants.PATH, "err");
        summary.record(MetricsConstants.METADATA, "err");

        List<String> lines = summary.buildSummaryAndReset("ctx");

        assertThat(lines).hasSize(4);
        assertThat(lines.get(0)).contains("ElasticsearchRepoEventItemWriter");
        assertThat(lines.get(1)).contains("ElasticsearchPathWriter");
        assertThat(lines.get(2)).contains("ElasticsearchMergedReindexWriter");
        assertThat(lines.get(3)).contains("ContentTransformEventWriter");
    }

    @Test
    void nullErrorTypeIsRecordedAsUnknown()
    {
        FailureSummary summary = new FailureSummary(true);
        summary.record(MetricsConstants.MERGED, null);

        List<String> lines = summary.buildSummaryAndReset("ctx");

        assertThat(lines).hasSize(1);
        assertThat(lines.get(0)).contains("total=1");
        assertThat(lines.get(0)).contains("unknown=1");
    }

    @Test
    void buildSummaryResetsCountsSoASecondCallReturnsNothing()
    {
        FailureSummary summary = new FailureSummary(true);
        summary.record(MetricsConstants.PATH, "err");
        summary.buildSummaryAndReset("first");

        assertThat(summary.buildSummaryAndReset("second")).isEmpty();
    }

    @Test
    void whenDisabledRecordsNothingAndReturnsNoLines()
    {
        FailureSummary summary = new FailureSummary(false);
        summary.record(MetricsConstants.METADATA, "err");

        assertThat(summary.buildSummaryAndReset("ctx")).isEmpty();
    }

    @Test
    void cleanRunProducesNoLines()
    {
        FailureSummary summary = new FailureSummary(true);

        assertThat(summary.buildSummaryAndReset("ctx")).isEmpty();
    }
}
