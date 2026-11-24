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
package org.alfresco.elasticsearch.batchindexing.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.batch.infrastructure.item.support.CompositeItemWriter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import org.alfresco.elasticsearch.batchindexing.cascade.CascadeCollectorWriter;
import org.alfresco.elasticsearch.batchindexing.writers.ContentTransformDirectWriter;
import org.alfresco.reindexing.config.ReindexingCoreProperties;
import org.alfresco.reindexing.config.ReindexingCoreProperties.WriterMode;
import org.alfresco.reindexing.model.ReindexingEvent;
import org.alfresco.reindexing.writers.ChunkWriteMetricsAccumulator;
import org.alfresco.reindexing.writers.ElasticsearchMergedReindexWriter;
import org.alfresco.reindexing.writers.ElasticsearchPathWriter;
import org.alfresco.reindexing.writers.ElasticsearchRepoEventItemWriter;

/**
 * Community supports the sequential writer strategy only. Whatever strategy is configured, startup succeeds and the batch-indexing {@code writer} bean composes the sequential metadata, path and content writers; the merged writer is never composed even though it (and its dead-letter wiring) stays registered.
 */
@DisplayName("Batch-indexing writer composition is sequential whatever writer mode is configured")
// Asserts run inside the ApplicationContextRunner callback, which PMD cannot see.
@SuppressWarnings("PMD.UnitTestShouldIncludeAssert")
class BatchIndexingBatchConfigWriterModeTest
{
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Binding.class)
            .withPropertyValues(
                    "alfresco.reindex.metadataIndexingEnabled=true",
                    "alfresco.reindex.pathIndexingEnabled=true",
                    "alfresco.reindex.contentIndexingEnabled=true");

    private ElasticsearchRepoEventItemWriter metadataWriter;
    private ElasticsearchPathWriter pathWriter;
    private ContentTransformDirectWriter contentWriter;
    private ElasticsearchMergedReindexWriter mergedWriter;
    private CascadeCollectorWriter cascadeCollectorWriter;

    /** ApplicationContextRunner does not enable @ConfigurationProperties binding on its own. */
    @EnableConfigurationProperties(ReindexingCoreProperties.class)
    static class Binding
    {}

    @BeforeEach
    void setUp()
    {
        metadataWriter = mock(ElasticsearchRepoEventItemWriter.class);
        pathWriter = mock(ElasticsearchPathWriter.class);
        contentWriter = mock(ContentTransformDirectWriter.class);
        mergedWriter = mock(ElasticsearchMergedReindexWriter.class);
        cascadeCollectorWriter = mock(CascadeCollectorWriter.class);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "alfresco.reindex.writerMode=sequential",
            "alfresco.reindex.writerMode=concurrent",
            "alfresco.reindex.writerMode=merged",
            "alfresco.reindex.writersConcurrentEnabled=true",
            "alfresco.reindex.writersMergedEnabled=true",
            "alfresco.reindex.supportedWriterModes=merged,concurrent"
    })
    @DisplayName("composes metadata, path and content sequentially and never the merged writer")
    void composesSequentialWritersOnly(String writerModeProperty)
    {
        runner.withPropertyValues(writerModeProperty).run(context -> {
            assertThat(context).hasNotFailed();
            ReindexingCoreProperties properties = context.getBean(ReindexingCoreProperties.class);
            assertThat(properties.getEffectiveWriterMode()).isEqualTo(WriterMode.SEQUENTIAL);

            ItemWriter<ReindexingEvent> writer = buildWriter(properties);

            assertThat(writer).isExactlyInstanceOf(CompositeItemWriter.class);
            writer.write(new Chunk<>());
            verify(metadataWriter).write(any());
            verify(pathWriter).write(any());
            verify(contentWriter).write(any());
            verify(mergedWriter, never()).write(any());
        });
    }

    private ItemWriter<ReindexingEvent> buildWriter(ReindexingCoreProperties properties)
    {
        CascadeProperties cascadeProperties = new CascadeProperties();
        cascadeProperties.setEnabled(false);
        return new BatchIndexingBatchConfig().writer(properties, metadataWriter, contentWriter, pathWriter,
                mergedWriter, cascadeProperties, cascadeCollectorWriter, new ChunkWriteMetricsAccumulator());
    }
}
