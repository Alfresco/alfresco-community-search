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

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.OpenSearchException;
import org.opensearch.client.opensearch.core.GetRequest;
import org.opensearch.client.opensearch.core.GetResponse;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import org.alfresco.database.connector.model.AlfrescoNode;
import org.alfresco.elasticsearch.batchindexing.config.DeadLetterProperties;
import org.alfresco.elasticsearch.batchindexing.model.DeadLetterDocument;
import org.alfresco.elasticsearch.batchindexing.model.DeadLetterDocument.FailureStage;
import org.alfresco.elasticsearch.batchindexing.model.WatermarkDocument;
import org.alfresco.reindexing.model.ReindexingEvent;

/**
 * Processor-side poison landing in the DLQ.
 * <p>
 * Spring integration test that exercises the full pipe {@link org.springframework.batch.core.listener.SkipListener#onSkipInProcess SkipListener.onSkipInProcess} → {@link org.alfresco.elasticsearch.batchindexing.listeners.DlqSkipListener} → {@link DlqWriter} → DLQ index with {@link FailureStage#PROCESSOR}. No production test hook: the composite processor bean (from {@code SharedBatchProcessingConfig#processors}) is spied via {@link MockitoSpyBean} and told to throw for one specific {@code dbId}, delegating to the real chain for every other node.
 * <p>
 * Complements the unit test {@code DlqSkipListenerTest.processSkipRecordsNodeIdAndDerivedFields} (listener contract in isolation) and the docker-compose reliability tests such as {@code MissingTagPoisonIT} which prove the metadata-writer side of the same DLQ pipe.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("processor poison → DLQ")
public class ProcessorPoisonSkipIT extends BatchIndexingAbstractIT
{
    /** Any leaf node from {@code alfresco-dump.sql}; 384 is reused from {@code MissingTagPoisonIT} for consistency. */
    private static final long POISON_DB_ID = 384L;
    private static final Duration DLQ_VISIBILITY_TIMEOUT = Duration.ofSeconds(30);

    @MockitoSpyBean(name = "processors")
    private ItemProcessor<AlfrescoNode, ReindexingEvent> processors;

    @Autowired
    private OpenSearchClient openSearchClient;

    @Autowired
    private DeadLetterProperties deadLetterProperties;

    @Test
    @DisplayName("processor throw is skipped as PROCESSOR and lands in the DLQ")
    void processorThrowIsSkippedAndLandsInDlqAsProcessor() throws Exception
    {
        doAnswer(invocation -> {
            AlfrescoNode node = invocation.getArgument(0);
            if (node != null && node.getId() != null && node.getId() == POISON_DB_ID)
            {
                throw new IllegalStateException("injected processor failure for dbId=" + POISON_DB_ID);
            }
            return invocation.callRealMethod();
        }).when(processors).process(any(AlfrescoNode.class));

        continuousReindexingService.runCycleSafe();

        await().atMost(DLQ_VISIBILITY_TIMEOUT)
                .pollInterval(Duration.ofSeconds(1))
                .ignoreExceptions()
                .until(() -> readDlqRecord(POISON_DB_ID).isPresent());

        DeadLetterDocument entry = readDlqRecord(POISON_DB_ID).orElseThrow();
        assertThat(entry.getDbId()).isEqualTo(POISON_DB_ID);
        assertThat(entry.getFailureStage())
                .as("Spring Batch must classify the processor throw as process-side skip")
                .isEqualTo(FailureStage.PROCESSOR);
        assertThat(entry.getFailureType()).isEqualTo("IllegalStateException");
        assertThat(entry.getFailureReason()).contains("injected processor failure");
        assertThat(entry.getFailureCount()).isEqualTo(1);

        WatermarkDocument watermark = watermarkStore.read().orElseThrow();
        assertThat(watermark.getLastRunStatus())
                .as("skip absorbs the poison — cycle completes, not fails")
                .isEqualTo("COMPLETED");
        assertThat(watermark.getLastRunSkipCount())
                .as("exactly one process-side skip must reach the watermark doc")
                .isEqualTo(1L);

        assertThat(count())
                .as("good items in the dump still land in the alfresco index")
                .isPositive();
    }

    private Optional<DeadLetterDocument> readDlqRecord(long dbId) throws IOException
    {
        try
        {
            openSearchClient.indices().refresh(r -> r.index(deadLetterProperties.getIndexName()));
            GetResponse<DeadLetterDocument> response = openSearchClient.get(
                    new GetRequest.Builder()
                            .index(deadLetterProperties.getIndexName())
                            .id(Long.toString(dbId))
                            .build(),
                    DeadLetterDocument.class);
            return response.found() ? Optional.ofNullable(response.source()) : Optional.empty();
        }
        catch (OpenSearchException e)
        {
            if (e.getMessage() != null && e.getMessage().contains("index_not_found_exception"))
            {
                return Optional.empty();
            }
            throw e;
        }
    }
}
