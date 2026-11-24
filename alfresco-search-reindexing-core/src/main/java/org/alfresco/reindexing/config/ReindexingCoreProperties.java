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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.format.annotation.DateTimeFormat;

import org.alfresco.metrics.MetricsConstants;

@Setter
@Getter
@ConfigurationProperties(prefix = "alfresco.reindex")
public class ReindexingCoreProperties implements InitializingBean
{
    private static final Logger LOGGER = LoggerFactory.getLogger(ReindexingCoreProperties.class);

    private String jobName;

    private int batchSize;

    private int pathCacheSize;

    private int pageSize;

    private int concurrentProcessors;

    private long fromId;

    private long toId;

    @DateTimeFormat(pattern = "yyyyMMddHHmm")
    private LocalDateTime fromTime;

    @DateTimeFormat(pattern = "yyyyMMddHHmm")
    private LocalDateTime toTime;

    protected boolean retryingEnabled;
    protected Integer retryingMaxCount;
    protected boolean multithreadedStepEnabled;

    private long retryingInitialDelay;
    private long retryingDelayIntervalMultiplier;
    private long retryingMaxDelay;
    private long writerRetryDelay;
    private int writerRetryCount;

    // Run metadata/path/content writers concurrently per chunk.
    private boolean writersConcurrentEnabled;
    // Thread-pool size backing the concurrent writer fan-out.
    private int writerConcurrency = 6;
    // Per-chunk upper bound (seconds) the concurrent writer waits for a delegate task to finish before
    // failing the chunk. 0 (default) means wait indefinitely, preserving the historical behaviour; set a
    // positive value only as a safety net against a task that never returns. Delegates already enforce
    // their own Elasticsearch socket timeout and retry budget, so keep this comfortably above
    // writerRetryCount * writerRetryDelay to avoid failing legitimately slow chunks.
    private long writerJoinTimeoutSeconds;
    // Build the path bulk as a scripted upsert so it no longer fails with document_missing_exception when
    // the metadata document is not yet present. Only effective while writersConcurrentEnabled is on.
    private boolean pathUpsertEnabled = true;

    // Merge the metadata and path writes into a single scripted-upsert bulk per chunk (one ES round-trip
    // instead of two). Replaces the metadata+path writers; content JMS dispatch stays separate.
    private boolean writersMergedEnabled;

    // Single selector for the writer strategy: sequential | concurrent | merged. This supersedes the
    // legacy writersConcurrentEnabled / writersMergedEnabled booleans. When left unset (the default) the
    // legacy booleans are still honoured for backward compatibility — see getRequestedWriterMode() — so
    // existing configurations keep working unchanged; set this to pick a strategy explicitly. A strategy
    // this edition does not support falls back to sequential — see getEffectiveWriterMode().
    private WriterMode writerMode;

    // Log a per-writer timing summary once each step finishes.
    private boolean writerTimingSummaryLogEnabled = true;
    // Log a per-writer roll-up of terminal bulk-item failures, grouped by Elasticsearch error type, once
    // each step finishes. Nothing is logged for a clean run.
    private boolean failureSummaryLogEnabled = true;

    // Enable to identify genuine (permanent) write failures and stop the reindex in those cases: when
    // per-item bulk failures remain after retries and no dead-letter listener is configured to capture
    // them, the chunk fails so the job ends FAILED instead of silently dropping nodes. Off by default
    // (log-and-continue). The batch-indexing app is unaffected (it wires its own dead-letter listener).
    private boolean failOnTerminalWriteErrors;

    private int skipLimit;

    /** What to do with a node whose type namespace has no prefix mapping. Reporting and dead-lettering happen either way. */
    private UnresolvedNamespaceAction onUnresolvedNamespace = UnresolvedNamespaceAction.FILTER;

    private boolean metadataIndexingEnabled;
    private boolean contentIndexingEnabled;
    private boolean pathIndexingEnabled;

    // Permissions that do not grant read access and should be considered non-read permissions.
    private Set<String> nonReadPermissions = Set.of();

    /**
     * Logs the resolved writer strategy once, at startup, after the properties have been bound. When the requested strategy is not supported by this edition a single warning names the request and the fallback; startup always continues.
     */
    @Override
    public void afterPropertiesSet()
    {
        WriterMode requested = getRequestedWriterMode();
        WriterMode effective = getEffectiveWriterMode();
        if (requested == effective)
        {
            LOGGER.info("Reindex writer mode: {}", effective);
        }
        else
        {
            LOGGER.warn("Reindex writer mode {} is not supported in this edition (supported: {}); falling back to {}. See docs/reindex-writer-configuration.md.",
                    requested, supportedWriterModes(), effective);
        }
    }

    /**
     * The writer strategies this edition supports. Community supports {@link WriterMode#SEQUENTIAL} only; editions that ship more strategies widen the set by overriding this method. Deliberately not a JavaBean accessor, so it cannot be bound (and therefore cannot be widened) from configuration.
     *
     * @return the supported writer modes
     */
    protected Set<WriterMode> supportedWriterModes()
    {
        return EnumSet.of(WriterMode.SEQUENTIAL);
    }

    /**
     * Resolves the writer strategy the configuration asks for, from the single {@code writerMode} selector, falling back to the legacy {@code writersMergedEnabled} / {@code writersConcurrentEnabled} booleans when {@code writerMode} is not set. Precedence is: explicit {@code writerMode} &gt; merged boolean &gt; concurrent boolean &gt; {@link WriterMode#SEQUENTIAL}. The requested strategy is not necessarily the one that runs; see {@link #getEffectiveWriterMode()}.
     *
     * @return the requested {@link WriterMode}, never {@code null}
     */
    public WriterMode getRequestedWriterMode()
    {
        if (writerMode != null)
        {
            return writerMode;
        }
        if (writersMergedEnabled)
        {
            return WriterMode.MERGED;
        }
        if (writersConcurrentEnabled)
        {
            return WriterMode.CONCURRENT;
        }
        return WriterMode.SEQUENTIAL;
    }

    /**
     * The writer strategy that actually runs: the {@link #getRequestedWriterMode() requested} strategy when this edition {@link #supportedWriterModes() supports} it, otherwise {@link WriterMode#SEQUENTIAL}. This is the single place the edition fallback is resolved; every writer-strategy accessor below, and therefore the writer composition and the write-metrics listeners, derive from it.
     *
     * @return the effective {@link WriterMode}, never {@code null}
     */
    public WriterMode getEffectiveWriterMode()
    {
        WriterMode requested = getRequestedWriterMode();
        return supportedWriterModes().contains(requested) ? requested : WriterMode.SEQUENTIAL;
    }

    /**
     * Whether the per-chunk writers should run concurrently. Derived from {@link #getEffectiveWriterMode()} so that the {@code writerMode} selector and the legacy {@code writersConcurrentEnabled} boolean resolve to a single answer. Overrides the Lombok-generated accessor for the backing field.
     *
     * @return {@code true} when the effective writer mode is {@link WriterMode#CONCURRENT}
     */
    public boolean isWritersConcurrentEnabled()
    {
        return getEffectiveWriterMode() == WriterMode.CONCURRENT;
    }

    /**
     * The path scripted-upsert only changes behaviour when the writers run concurrently. When writers run sequentially the metadata document is always written before the path write, so a plain path update is safe and the upsert setting is ignored. This keeps the default (sequential) behaviour unchanged even though {@code pathUpsertEnabled} defaults to {@code true}.
     *
     * @return {@code true} when the path bulk should be a scripted upsert, i.e. concurrent writers are enabled and the upsert has not been explicitly turned off
     */
    public boolean isPathUpsertEffective()
    {
        return isWritersConcurrentEnabled() && pathUpsertEnabled;
    }

    /**
     * The merged writer folds the metadata and path writes into a single bulk, so it can only be used when both metadata and path indexing are enabled. When either is disabled the per-writer toggles must still be honoured, so merged mode is suppressed and the standard per-flag composition is used instead.
     *
     * @return {@code true} when the merged metadata+path writer should be selected, i.e. the effective writer mode is merged and neither metadata nor path indexing has been turned off
     */
    public boolean isWritersMergedEffective()
    {
        return getEffectiveWriterMode() == WriterMode.MERGED && metadataIndexingEnabled && pathIndexingEnabled;
    }

    /**
     * The single source of truth for which logical writers are active in a reindex/batch-indexing step, in composition order. Merged mode collapses the metadata and path writes into one {@link MetricsConstants#MERGED} entry (see {@link #isWritersMergedEffective()}); otherwise the individual metadata and path writers are listed. Content is always appended last when content indexing is enabled. Both the per-module {@code writer} bean composition and the write-metrics/summary listeners derive their selection from this method, so the merged-vs-split precedence and the per-writer toggles live in exactly one place.
     *
     * @return an immutable, ordered list of writer keys drawn from {@link MetricsConstants#MERGED}, {@link MetricsConstants#METADATA}, {@link MetricsConstants#PATH} and {@link MetricsConstants#CONTENT}
     */
    public List<String> getEnabledWriterKeys()
    {
        List<String> keys = new ArrayList<>();
        if (isWritersMergedEffective())
        {
            keys.add(MetricsConstants.MERGED);
        }
        else
        {
            if (metadataIndexingEnabled)
            {
                keys.add(MetricsConstants.METADATA);
            }
            if (pathIndexingEnabled)
            {
                keys.add(MetricsConstants.PATH);
            }
        }
        if (contentIndexingEnabled)
        {
            keys.add(MetricsConstants.CONTENT);
        }
        return List.copyOf(keys);
    }

    @Override
    public String toString()
    {
        return "ReindexingCoreProperties{" +
                "jobName='" + jobName + '\'' +
                ", batchSize=" + batchSize +
                ", pageSize=" + pageSize +
                ", concurrentProcessors=" + concurrentProcessors +
                ", fromId=" + fromId +
                ", toId=" + toId +
                ", fromTime=" + fromTime +
                ", toTime=" + toTime +
                ", retryingEnabled=" + retryingEnabled +
                ", retryingMaxCount=" + retryingMaxCount +
                ", multithreadedStepEnabled=" + multithreadedStepEnabled +
                ", retryingInitialDelay=" + retryingInitialDelay +
                ", retryingDelayIntervalMultiplier=" + retryingDelayIntervalMultiplier +
                ", retryingMaxDelay=" + retryingMaxDelay +
                ", writerRetryDelay=" + writerRetryDelay +
                ", writerRetryCount=" + writerRetryCount +
                ", writersConcurrentEnabled=" + writersConcurrentEnabled +
                ", writerConcurrency=" + writerConcurrency +
                ", writerJoinTimeoutSeconds=" + writerJoinTimeoutSeconds +
                ", pathUpsertEnabled=" + pathUpsertEnabled +
                ", writersMergedEnabled=" + writersMergedEnabled +
                ", writerMode=" + writerMode +
                ", requestedWriterMode=" + getRequestedWriterMode() +
                ", effectiveWriterMode=" + getEffectiveWriterMode() +
                ", writerTimingSummaryLogEnabled=" + writerTimingSummaryLogEnabled +
                ", failureSummaryLogEnabled=" + failureSummaryLogEnabled +
                ", failOnTerminalWriteErrors=" + failOnTerminalWriteErrors +
                ", skipLimit=" + skipLimit +
                ", onUnresolvedNamespace=" + onUnresolvedNamespace +
                ", pathCacheSize=" + pathCacheSize +
                ", nonReadPermissions=" + nonReadPermissions +
                '}';
    }

    /**
     * The reindex writer strategy selected by {@code alfresco.reindex.writerMode}. A single selector that replaces the overlapping {@code writersConcurrentEnabled} / {@code writersMergedEnabled} booleans. Which strategies can run depends on the edition, see {@link #supportedWriterModes()}.
     */
    public enum WriterMode
    {
        /** Metadata, path and content writers run one after another through a {@code CompositeItemWriter}. */
        SEQUENTIAL,
        /** Metadata, path and content writers run concurrently per chunk. */
        CONCURRENT,
        /** Metadata and path writes are folded into a single scripted-upsert bulk; content stays separate. */
        MERGED
    }

}
