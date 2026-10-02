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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.alfresco.metrics.MetricsConstants;

/**
 * Aggregates every terminal bulk-item failure of a step execution by writer and Elasticsearch error type and, when the step finishes, logs a single {@code WARN} line per writer summarising the failure counts (e.g. {@code total=4743, document_parsing_exception=4743}). Per-item failures are already logged individually by {@link org.alfresco.reindexing.opensearch.impl.ElasticSearchUpdateOperationsImpl}; this roll-up makes the failure profile of a run identifiable at a glance.
 * <p>
 * Counts are fed from the single failure choke-point ({@code recordFailure}) so retryable-but-exhausted items are counted exactly once, and are thread-safe because a multithreaded/concurrent step records from several writer threads concurrently. Controlled by {@code alfresco.reindex.failureSummaryLogEnabled}.
 */
public class FailureSummary
{
    private static final Logger LOGGER = LoggerFactory.getLogger(FailureSummary.class);

    private static final String UNKNOWN_ERROR_TYPE = "unknown";

    // Preserve a stable, readable ordering and map the internal module key to the writer class name.
    private static final Map<String, String> MODULE_LABELS = new LinkedHashMap<>();
    static
    {
        MODULE_LABELS.put(MetricsConstants.METADATA, "ElasticsearchRepoEventItemWriter");
        MODULE_LABELS.put(MetricsConstants.PATH, "ElasticsearchPathWriter");
        MODULE_LABELS.put(MetricsConstants.MERGED, "ElasticsearchMergedReindexWriter");
        MODULE_LABELS.put(MetricsConstants.CONTENT, "ContentTransformEventWriter");
    }

    private final boolean enabled;
    private final Map<String, Map<String, LongAdder>> countsByModule = new ConcurrentHashMap<>();

    public FailureSummary(boolean enabled)
    {
        this.enabled = enabled;
    }

    /**
     * Records one terminal failure for the given writer module and Elasticsearch error type. No-op when disabled.
     *
     * @param module
     *            the writer module key (e.g. {@code metadata}, {@code path}, {@code merged})
     * @param errorType
     *            the Elasticsearch error type (e.g. {@code document_parsing_exception}); {@code null} is recorded as {@code unknown}
     */
    public void record(String module, String errorType)
    {
        if (enabled)
        {
            countsByModule.computeIfAbsent(module, k -> new ConcurrentHashMap<>())
                    .computeIfAbsent(errorType == null ? UNKNOWN_ERROR_TYPE : errorType, k -> new LongAdder())
                    .increment();
        }
    }

    /**
     * Logs one {@code WARN} summary line per writer that recorded failures and clears the retained counts so the next step execution starts fresh. Nothing is logged for a clean run (no failures).
     *
     * @param context
     *            a short label (e.g. step name / job execution id) identifying which run these numbers belong to
     */
    public void logSummaryAndReset(String context)
    {
        for (String line : buildSummaryAndReset(context))
        {
            LOGGER.warn("{}", line);
        }
    }

    /**
     * Computes one summary line per writer that recorded failures and clears the retained counts. Returns an empty list when disabled or when nothing was recorded. Package-visible for testing.
     */
    List<String> buildSummaryAndReset(String context)
    {
        if (!enabled || countsByModule.isEmpty())
        {
            return List.of();
        }

        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, String> labelled : MODULE_LABELS.entrySet())
        {
            addLine(lines, context, labelled.getKey(), labelled.getValue());
        }
        // Include any module without a predefined label (defensive; keeps unexpected keys visible).
        for (String module : List.copyOf(countsByModule.keySet()))
        {
            addLine(lines, context, module, module);
        }
        countsByModule.clear();
        return lines;
    }

    private void addLine(List<String> lines, String context, String module, String label)
    {
        Map<String, LongAdder> byType = countsByModule.remove(module);
        if (byType == null || byType.isEmpty())
        {
            return;
        }

        long total = 0;
        for (LongAdder adder : byType.values())
        {
            total += adder.sum();
        }

        // Highest count first, then error type alphabetically, so the dominant failure is easy to spot.
        String breakdown = byType.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, LongAdder>> comparingLong(e -> e.getValue().sum()).reversed()
                        .thenComparing(Map.Entry::getKey))
                .map(e -> e.getKey() + "=" + e.getValue().sum())
                .collect(Collectors.joining(", "));

        lines.add(String.format(Locale.ROOT,
                "[%s] Write failure summary for %s: total=%d, %s",
                label, context, total, breakdown));
    }
}
