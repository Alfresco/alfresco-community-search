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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;

import org.alfresco.reindexing.config.ReindexingCoreProperties.WriterMode;

class ReindexingCorePropertiesTest
{
    /** Stands in for an edition that supports every writer strategy (e.g. Enterprise). */
    private static final class AllWriterModesProperties extends ReindexingCoreProperties
    {
        @Override
        protected Set<WriterMode> supportedWriterModes()
        {
            return EnumSet.allOf(WriterMode.class);
        }
    }

    private static ReindexingCoreProperties allWriterModes()
    {
        return new AllWriterModesProperties();
    }

    @Test
    void pathUpsertIsEnabledByDefaultButConcurrencyIsOff()
    {
        var props = new ReindexingCoreProperties();

        // The path scripted-upsert defaults ON, but writers run sequentially by default, so the
        // upsert has no effect and the historical sequential behaviour is preserved.
        assertThat(props.isPathUpsertEnabled()).isTrue();
        assertThat(props.isWritersConcurrentEnabled()).isFalse();
        assertThat(props.isPathUpsertEffective()).isFalse();
    }

    @Test
    void writerConcurrencyAndJoinTimeoutHaveSafeDefaults()
    {
        var props = new ReindexingCoreProperties();

        // Fan-out defaults to 6 threads; the per-chunk join timeout defaults to 0 = wait indefinitely,
        // which preserves the historical unbounded behaviour until an operator opts into a safety cap.
        assertThat(props.getWriterConcurrency()).isEqualTo(6);
        assertThat(props.getWriterJoinTimeoutSeconds()).isZero();
    }

    /**
     * The path scripted-upsert must only take effect when writers run concurrently. When writers run sequentially the metadata document is always written before the path write, so a plain path update is safe and the upsert setting is intentionally ignored.
     */
    @ParameterizedTest(name = "concurrent={0}, upsert={1} -> effective={2}")
    @CsvSource({
            "false, false, false",
            "false, true,  false",
            "true,  false, false",
            "true,  true,  true"
    })
    void pathUpsertIsEffectiveOnlyWhenConcurrentAndEnabled(boolean concurrent, boolean upsert, boolean expected)
    {
        var props = allWriterModes();
        props.setWritersConcurrentEnabled(concurrent);
        props.setPathUpsertEnabled(upsert);

        assertThat(props.isPathUpsertEffective()).isEqualTo(expected);
    }

    /**
     * The merged writer folds the metadata and path writes into a single bulk, so it must only be selected when both metadata and path indexing are enabled. If either is turned off the per-writer toggles must still be honoured, so merged mode is suppressed.
     */
    @ParameterizedTest(name = "merged={0}, metadata={1}, path={2} -> effective={3}")
    @CsvSource({
            "false, true,  true,  false",
            "true,  false, true,  false",
            "true,  true,  false, false",
            "true,  false, false, false",
            "true,  true,  true,  true"
    })
    void mergedIsEffectiveOnlyWhenMergedEnabledAndMetadataAndPathEnabled(boolean merged, boolean metadata, boolean path,
            boolean expected)
    {
        var props = allWriterModes();
        props.setWritersMergedEnabled(merged);
        props.setMetadataIndexingEnabled(metadata);
        props.setPathIndexingEnabled(path);

        assertThat(props.isWritersMergedEffective()).isEqualTo(expected);
    }

    /**
     * {@code getEnabledWriterKeys()} is the single source of truth for writer composition and metrics. Merged mode collapses metadata+path into a single {@code merged} key; otherwise the individual metadata/path keys appear per their toggles. Content, when enabled, is always appended last.
     */
    @ParameterizedTest(name = "merged={0}, metadata={1}, path={2}, content={3} -> keys={4}")
    @CsvSource(delimiter = '|', value = {
            "false | true  | true  | true  | metadata,path,content",
            "false | true  | true  | false | metadata,path",
            "false | true  | false | true  | metadata,content",
            "false | false | true  | true  | path,content",
            "false | false | false | false | ",
            "true  | true  | true  | true  | merged,content",
            "true  | true  | true  | false | merged",
            "true  | false | true  | true  | path,content",
            "true  | true  | false | true  | metadata,content"
    })
    void enabledWriterKeysReflectMergedPrecedenceAndPerWriterToggles(boolean merged, boolean metadata, boolean path,
            boolean content, String expectedCsv)
    {
        var props = allWriterModes();
        props.setWritersMergedEnabled(merged);
        props.setMetadataIndexingEnabled(metadata);
        props.setPathIndexingEnabled(path);
        props.setContentIndexingEnabled(content);

        var expected = (expectedCsv == null || expectedCsv.isBlank())
                ? List.<String> of()
                : List.of(expectedCsv.split(","));
        assertThat(props.getEnabledWriterKeys()).containsExactlyElementsOf(expected);
    }

    @Test
    void writerModeDefaultsToSequentialAndFallsBackToLegacyBooleans()
    {
        var props = new ReindexingCoreProperties();

        // The single selector is unset by default, so the effective mode is SEQUENTIAL and the historical
        // behaviour (no concurrency, no merge) is preserved unless an operator opts in.
        assertThat(props.getWriterMode()).isNull();
        assertThat(props.getEffectiveWriterMode()).isEqualTo(WriterMode.SEQUENTIAL);
        assertThat(props.isWritersConcurrentEnabled()).isFalse();
        assertThat(props.isWritersMergedEffective()).isFalse();
    }

    /**
     * The {@code writerMode} selector, when set, takes precedence over the legacy {@code writersConcurrentEnabled} / {@code writersMergedEnabled} booleans. When it is unset the booleans are consulted (merged wins over concurrent), and with neither set the mode is sequential. This keeps existing configurations working while providing a single place to pick a strategy. In an edition that supports every strategy the requested mode is also the effective one.
     */
    @ParameterizedTest(name = "writerMode={0}, concurrent={1}, merged={2} -> requested={3}")
    @CsvSource(delimiter = '|', value = {
            "           | false | false | SEQUENTIAL",
            "           | true  | false | CONCURRENT",
            "           | false | true  | MERGED",
            "           | true  | true  | MERGED",
            "SEQUENTIAL | true  | true  | SEQUENTIAL",
            "CONCURRENT | false | true  | CONCURRENT",
            "MERGED     | true  | false | MERGED"
    })
    void requestedWriterModeResolvesSelectorThenLegacyBooleans(String writerMode, boolean concurrent, boolean merged,
            WriterMode expected)
    {
        var props = allWriterModes();
        if (writerMode != null && !writerMode.isBlank())
        {
            props.setWriterMode(WriterMode.valueOf(writerMode.trim()));
        }
        props.setWritersConcurrentEnabled(concurrent);
        props.setWritersMergedEnabled(merged);

        assertThat(props.getRequestedWriterMode()).isEqualTo(expected);
        assertThat(props.getEffectiveWriterMode()).isEqualTo(expected);
    }

    @Test
    void writerModeConcurrentEnablesConcurrencyAndPathUpsert()
    {
        var props = allWriterModes();
        props.setWriterMode(WriterMode.CONCURRENT);

        // pathUpsertEnabled defaults true, so selecting concurrent mode makes the scripted upsert effective.
        assertThat(props.isWritersConcurrentEnabled()).isTrue();
        assertThat(props.isPathUpsertEffective()).isTrue();
    }

    @Test
    void writerModeMergedSelectsMergedKeyAndSuppressesConcurrency()
    {
        var props = allWriterModes();
        props.setWriterMode(WriterMode.MERGED);
        props.setMetadataIndexingEnabled(true);
        props.setPathIndexingEnabled(true);
        props.setContentIndexingEnabled(true);

        assertThat(props.isWritersMergedEffective()).isTrue();
        assertThat(props.isWritersConcurrentEnabled()).isFalse();
        assertThat(props.getEnabledWriterKeys()).containsExactly("merged", "content");
    }

    @Test
    void communitySupportsSequentialOnly()
    {
        assertThat(new ReindexingCoreProperties().supportedWriterModes()).containsExactly(WriterMode.SEQUENTIAL);
    }

    /**
     * Community supports the sequential strategy only. Whether concurrent or merged is requested through the {@code writerMode} selector or the legacy booleans, the requested mode is still reported but every writer-strategy accessor resolves to sequential: no concurrency, no path upsert and no merged key.
     */
    @ParameterizedTest(name = "writerMode={0}, concurrent={1}, merged={2} -> requested={3}, effective=SEQUENTIAL")
    @CsvSource(delimiter = '|', value = {
            "SEQUENTIAL | false | false | SEQUENTIAL",
            "CONCURRENT | false | false | CONCURRENT",
            "MERGED     | false | false | MERGED",
            "           | true  | false | CONCURRENT",
            "           | false | true  | MERGED",
            "           | true  | true  | MERGED"
    })
    void communityFallsBackToSequentialForUnsupportedWriterModes(String writerMode, boolean concurrent, boolean merged,
            WriterMode expectedRequested)
    {
        var props = new ReindexingCoreProperties();
        if (writerMode != null && !writerMode.isBlank())
        {
            props.setWriterMode(WriterMode.valueOf(writerMode.trim()));
        }
        props.setWritersConcurrentEnabled(concurrent);
        props.setWritersMergedEnabled(merged);
        props.setMetadataIndexingEnabled(true);
        props.setPathIndexingEnabled(true);
        props.setContentIndexingEnabled(true);

        assertThat(props.getRequestedWriterMode()).isEqualTo(expectedRequested);
        assertThat(props.getEffectiveWriterMode()).isEqualTo(WriterMode.SEQUENTIAL);
        assertThat(props.isWritersConcurrentEnabled()).isFalse();
        assertThat(props.isPathUpsertEffective()).isFalse();
        assertThat(props.isWritersMergedEffective()).isFalse();
        assertThat(props.getEnabledWriterKeys()).containsExactly("metadata", "path", "content");
    }

    @ParameterizedTest(name = "writerMode={0}")
    @CsvSource({"CONCURRENT", "MERGED"})
    void unsupportedWriterModeLogsOneWarningAndNeverFails(WriterMode requested)
    {
        var props = new ReindexingCoreProperties();
        props.setWriterMode(requested);

        List<ILoggingEvent> events = captureLogs(() -> assertThatCode(props::afterPropertiesSet).doesNotThrowAnyException());

        assertThat(events).hasSize(1);
        assertThat(events.get(0).getLevel()).isEqualTo(Level.WARN);
        assertThat(events.get(0).getFormattedMessage())
                .contains(requested.name())
                .contains("not supported")
                .contains("falling back to SEQUENTIAL");
    }

    @Test
    void supportedWriterModeLogsResolvedModeWithoutWarning()
    {
        var props = allWriterModes();
        props.setWriterMode(WriterMode.MERGED);

        List<ILoggingEvent> events = captureLogs(props::afterPropertiesSet);

        assertThat(events).hasSize(1);
        assertThat(events.get(0).getLevel()).isEqualTo(Level.INFO);
        assertThat(events.get(0).getFormattedMessage()).isEqualTo("Reindex writer mode: MERGED");
    }

    @Test
    void communitySequentialLogsResolvedModeWithoutWarning()
    {
        var props = new ReindexingCoreProperties();
        props.setWriterMode(WriterMode.SEQUENTIAL);

        List<ILoggingEvent> events = captureLogs(props::afterPropertiesSet);

        assertThat(events).hasSize(1);
        assertThat(events.get(0).getLevel()).isEqualTo(Level.INFO);
        assertThat(events.get(0).getFormattedMessage()).isEqualTo("Reindex writer mode: SEQUENTIAL");
    }

    private static List<ILoggingEvent> captureLogs(Runnable action)
    {
        Logger logger = (Logger) LoggerFactory.getLogger(ReindexingCoreProperties.class);
        Level originalLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
        try
        {
            action.run();
        }
        finally
        {
            logger.detachAppender(appender);
            logger.setLevel(originalLevel);
            appender.stop();
        }
        return List.copyOf(appender.list);
    }
}
