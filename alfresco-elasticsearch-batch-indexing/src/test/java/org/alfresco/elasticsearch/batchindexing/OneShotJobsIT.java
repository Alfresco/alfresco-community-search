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
import static org.hamcrest.CoreMatchers.equalTo;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.Conflicts;
import org.opensearch.client.opensearch._types.Refresh;
import org.opensearch.client.opensearch._types.query_dsl.MatchAllQuery;
import org.opensearch.client.opensearch.core.DeleteByQueryRequest;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.test.annotation.DirtiesContext;

import org.alfresco.database.connector.NodeParams;
import org.alfresco.database.connector.model.AlfrescoNode;
import org.alfresco.elasticsearch.batchindexing.DlqReader.DlqEntry;
import org.alfresco.elasticsearch.batchindexing.config.DeadLetterProperties;
import org.alfresco.elasticsearch.batchindexing.model.DeadLetterDocument.FailureStage;

/**
 * Integration tests for the one-shot {@code reindexByIds} and {@code reindexDlq} jobs (ACS-12336). Exercises the real job/step Spring wiring (chunk step, {@code @StepScope} reader, {@code batchTransactionManager} qualifier, real DLQ scan/delete against OpenSearch) directly via {@link JobLauncher} — deliberately bypasses {@link OneShotJobRunner}, which calls {@code System.exit} and would kill the test JVM.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SuppressWarnings("PMD.SignatureDeclareThrowsException")
class OneShotJobsIT extends BatchIndexingAbstractIT
{
    @Autowired
    private JobLauncher jobLauncher;

    @Autowired
    @Qualifier("reindexByIdsJob") private Job reindexByIdsJob;

    @Autowired
    @Qualifier("reindexDlqJob") private Job reindexDlqJob;

    @Autowired
    private DlqWriter dlqWriter;

    @Autowired
    private DlqReader dlqReader;

    @Autowired
    private OpenSearchClient openSearchClient;

    @Autowired
    private DeadLetterProperties deadLetterProperties;

    private JobExecution launch(Job job) throws Exception
    {
        return jobLauncher.run(job, new JobParametersBuilder()
                .addLong("run.id", System.currentTimeMillis())
                .toJobParameters());
    }

    @Test
    void reindexByIdsJob_indexesAllNodesInDefaultRange() throws Exception
    {
        computeExpectedCounts();

        JobExecution execution = launch(reindexByIdsJob);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        await().pollDelay(2, TimeUnit.SECONDS)
                .timeout(2, TimeUnit.MINUTES)
                .until(this::count, equalTo(expectedNodeCount));
    }

    @Test
    void reindexDlqJob_drainsASeededNodeLevelEntryOnSuccess() throws Exception
    {
        List<AlfrescoNode> allNodes = metadataRepository.getAlfrescoNodes(NodeParams.searchByIdRange(0, Long.MAX_VALUE));
        AlfrescoNode node = allNodes.stream().findFirst().orElseThrow();
        long dbId = node.getId();
        String nodeRef = node.getNodeRef();

        dlqWriter.recordSkip(dbId, nodeRef, null, FailureStage.METADATA_WRITER, new RuntimeException("seeded by IT"));
        // ES writes aren't immediately searchable — poll for the seed to land before draining.
        await().pollDelay(1, TimeUnit.SECONDS)
                .timeout(30, TimeUnit.SECONDS)
                .until(() -> dlqReader.scanEntries(100).stream()
                        .anyMatch(e -> e.documentId().equals(String.valueOf(dbId))));

        JobExecution execution = launch(reindexDlqJob);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        await().pollDelay(1, TimeUnit.SECONDS)
                .timeout(1, TimeUnit.MINUTES)
                .until(() -> getDoc(nodeRef).isPresent());
        assertThat(dlqReader.scanEntries(100))
                .as("seeded entry cleared after a successful drain")
                .extracting(DlqEntry::documentId)
                .doesNotContain(String.valueOf(dbId));
    }

    /**
     * With a cap smaller than the DLQ, repeated scans must rotate: a picked-and-failed entry (higher failureCount) sinks to the back, and the next scan surfaces fresher entries — so every entry gets a turn (no starvation). Drives the real {@code scanEntries} query (failureCount+sourceCommitTimeMs sort, node-level exists filter) against OpenSearch with maxItems=2; window-level records are excluded and only counted.
     */
    @Test
    void reindexDlq_scanRotatesAcrossRunsUnderCap() throws Exception
    {
        clearDlq();

        // Seed 5 node-level entries (failureCount=1) with ascending sourceCommitTimeMs so the sort is
        // deterministic, plus one non-drainable window-level record.
        for (long dbId = 1; dbId <= 5; dbId++)
        {
            dlqWriter.recordSkip(dbId, "workspace://SpacesStore/dlq-" + dbId, dbId * 1000,
                    FailureStage.METADATA_WRITER, new RuntimeException("seed"));
        }
        dlqWriter.recordWindowFailure(1000L, 2000L, new RuntimeException("window"));
        refreshDlq();

        assertThat(dlqReader.countWindowLevelEntries()).as("one window-level record present").isEqualTo(1L);

        // Run 1 (cap 2): two lowest by failureCount then sourceCommitTimeMs → dbIds 1, 2.
        Set<Long> run1 = pickedNodeDbIds(2);
        assertThat(run1).containsExactlyInAnyOrder(1L, 2L);
        bumpFailure(1L, 2L);

        // Run 2: dbIds 1,2 rotated to the back (failureCount=2) → next fresh pair 3, 4.
        Set<Long> run2 = pickedNodeDbIds(2);
        assertThat(run2).containsExactlyInAnyOrder(3L, 4L);
        bumpFailure(3L, 4L);

        // Run 3: the never-yet-picked dbId 5 (still failureCount=1) now surfaces first.
        Set<Long> run3 = pickedNodeDbIds(2);
        assertThat(run3).contains(5L);

        // No starvation: every entry was covered across the three capped runs.
        Set<Long> covered = new HashSet<>();
        covered.addAll(run1);
        covered.addAll(run2);
        covered.addAll(run3);
        assertThat(covered).containsExactlyInAnyOrder(1L, 2L, 3L, 4L, 5L);

        clearDlq();
    }

    private Set<Long> pickedNodeDbIds(int maxItems)
    {
        List<DlqEntry> entries = dlqReader.scanEntries(maxItems);
        assertThat(entries).allSatisfy(e -> assertThat(e.document().getDbId())
                .as("scan returns node-level records only").isNotNull());
        return entries.stream().map(e -> e.document().getDbId()).collect(Collectors.toUnmodifiableSet());
    }

    private void bumpFailure(long... dbIds) throws Exception
    {
        for (long dbId : dbIds)
        {
            // sourceCommitTimeMs=null preserves the seeded value; recordSkip increments failureCount.
            dlqWriter.recordSkip(dbId, null, null, FailureStage.METADATA_WRITER, new RuntimeException("failed again"));
        }
        refreshDlq();
    }

    private void refreshDlq() throws Exception
    {
        openSearchClient.indices().refresh(r -> r.index(deadLetterProperties.getIndexName()));
    }

    private void clearDlq() throws Exception
    {
        openSearchClient.indices().refresh(r -> r.index(deadLetterProperties.getIndexName()));
        openSearchClient.deleteByQuery(new DeleteByQueryRequest.Builder()
                .index(deadLetterProperties.getIndexName())
                .conflicts(Conflicts.Proceed)
                .query(new MatchAllQuery.Builder().build().toQuery())
                .refresh(Refresh.True)
                .build());
    }
}
