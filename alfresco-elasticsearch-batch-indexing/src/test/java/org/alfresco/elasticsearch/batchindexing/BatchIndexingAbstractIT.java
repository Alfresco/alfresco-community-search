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

import static java.lang.Math.toIntExact;
import static java.util.concurrent.TimeUnit.MINUTES;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.CoreMatchers.equalTo;

import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import org.alfresco.database.connector.AlfrescoMetadataRepository;
import org.alfresco.database.connector.NodeParams;
import org.alfresco.database.connector.model.AlfrescoNode;
import org.alfresco.database.connector.model.QName;
import org.alfresco.elasticsearch.batchindexing.model.WatermarkDocument;
import org.alfresco.indexing.shared.ElasticsearchContainerApplicationContextInitializer;
import org.alfresco.indexing.shared.ElasticsearchTestTemplate;
import org.alfresco.indexing.shared.TransformCoreContainerApplicationContextInitializer;
import org.alfresco.indexing.shared.config.ElasticsearchIndexConfiguration;
import org.alfresco.indexing.shared.config.FilteringConfig;

/**
 * Abstract base class for batch-indexing integration tests. Boots {@link BatchIndexingApp} with Testcontainers for Elasticsearch, PostgreSQL and Transform Core. Continuous reindexing auto-start is disabled — tests trigger cycles manually.
 */
@ActiveProfiles("test")
@SpringBootTest(classes = {BatchIndexingApp.class, ElasticsearchIndexConfiguration.class})
@ContextConfiguration(initializers = {
        ElasticsearchContainerApplicationContextInitializer.class,
        PostgresWithAlfrescoDumpContainerApplicationContextInitializer.class,
        TransformCoreContainerApplicationContextInitializer.class,
        BatchIndexingAbstractIT.BatchIndexingPropertiesInitializer.class
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class BatchIndexingAbstractIT extends ElasticsearchTestTemplate
{

    @Autowired
    protected AlfrescoMetadataRepository metadataRepository;

    @Autowired
    protected FilteringConfig filteringConfig;

    @Autowired
    protected ContinuousReindexingService continuousReindexingService;

    @Autowired
    protected ElasticsearchWatermarkStore watermarkStore;

    protected boolean allDocumentsIndexed;

    protected int nodesDeletedInDatabaseTotalCount;
    protected int nodesWithContentCount;
    protected long expectedNodeCount;

    static class BatchIndexingPropertiesInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext>
    {
        private static final AtomicInteger CONTEXT_COUNTER = new AtomicInteger();

        @Override
        public void initialize(ConfigurableApplicationContext context)
        {
            int id = CONTEXT_COUNTER.incrementAndGet();
            TestPropertyValues.of(
                    "elasticsearch.indexName=test-batch-it-" + id,
                    "alfresco.reindex.continuous.watermarkIndexName=watermark-batch-it-" + id,
                    "alfresco.reindex.dead-letter.indexName=dead-letter-batch-it-" + id)
                    .applyTo(context);
        }
    }

    @BeforeAll
    void seedWatermarkAndResetState() throws WatermarkAccessException
    {
        LoggerFactory.getLogger("TEST").info("seedWatermarkAndResetState");
        allDocumentsIndexed = false;
        if (watermarkStore.read().isEmpty())
        {
            WatermarkDocument seed = new WatermarkDocument();
            seed.setLastSuccessfulToTimeEpochMs(1L);
            seed.setLastRunStatus("SEEDED");
            watermarkStore.write(seed);
        }
    }

    /**
     * Runs one continuous-reindexing cycle and waits until all expected documents appear in Elasticsearch.
     */
    protected void runCycleAndWaitForIndexing()
    {
        computeExpectedCounts();

        if (!allDocumentsIndexed)
        {
            continuousReindexingService.runCycleSafe();

            await().pollDelay(2, TimeUnit.SECONDS)
                    .timeout(2, MINUTES)
                    .until(this::count, equalTo(expectedNodeCount));

            allDocumentsIndexed = true;
        }
    }

    protected void computeExpectedCounts()
    {
        if (expectedNodeCount > 0)
        {
            return;
        }

        List<AlfrescoNode> allNodes = metadataRepository.getAlfrescoNodes(
                NodeParams.searchByIdRange(0, Long.MAX_VALUE).withArchive(true));

        // Reindex emits soft-deletes (ALIVE=false) for trashed nodes and nodes flagged cm:isIndexed=false,
        // and for archived nodes which are kept searchable in the archive store with ALIVE=false.
        Predicate<AlfrescoNode> isMarkedForDelete = n -> "deleted".equals(n.getType().getLocalName())
                || "archive".equals(n.getProtocol())
                || n.getNodeProperties().stream()
                        .anyMatch(p -> "isIndexed".equals(p.getPropertyKey().getLocalName())
                                && !p.getPropertyValue().getBooleanValue());

        nodesDeletedInDatabaseTotalCount = toIntExact(allNodes.stream().filter(isMarkedForDelete).count());
        assertThat(nodesDeletedInDatabaseTotalCount).isPositive();

        nodesWithContentCount = toIntExact(allNodes.stream()
                .map(AlfrescoNode::getContentData)
                .mapToInt(Set::size)
                .filter(s -> s > 0)
                .count());
        assertThat(nodesWithContentCount).isPositive();

        // Soft-delete events carry no aspects, so they bypass the aspect filter.
        Set<String> blacklistedAspects = getBlacklistedAspects();
        expectedNodeCount = allNodes.stream()
                .filter(n -> isMarkedForDelete.test(n)
                        || n.getAspects().stream().map(QName::getLocalName).noneMatch(blacklistedAspects::contains))
                .count();
        // sys:descriptor node is also indexed
        expectedNodeCount++;
        assertThat(expectedNodeCount).isPositive();
    }

    private Set<String> getBlacklistedAspects()
    {
        return filteringConfig.getNodeAspects().stream()
                .map(aspect -> aspect.substring(aspect.indexOf(':') + 1))
                .collect(Collectors.toUnmodifiableSet());
    }
}
