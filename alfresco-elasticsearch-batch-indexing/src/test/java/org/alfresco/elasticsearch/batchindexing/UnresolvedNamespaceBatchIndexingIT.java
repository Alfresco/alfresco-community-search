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

import static java.util.concurrent.TimeUnit.MINUTES;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import static org.alfresco.elasticsearch.batchindexing.PostgresWithCustomNamespaceContainerApplicationContextInitializer.CUSTOM_TYPE_LOCAL_NAME;
import static org.alfresco.elasticsearch.batchindexing.PostgresWithCustomNamespaceContainerApplicationContextInitializer.CUSTOM_TYPE_NODE_UUID;
import static org.alfresco.elasticsearch.batchindexing.PostgresWithCustomNamespaceContainerApplicationContextInitializer.CUSTOM_URI;
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.ASPECT;
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.PATH;
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.PROPERTIES;
import static org.alfresco.elasticsearch.shared.translator.AlfrescoQualifiedNameTranslator.encode;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import org.alfresco.elasticsearch.batchindexing.model.WatermarkDocument;
import org.alfresco.indexing.shared.ElasticsearchContainerApplicationContextInitializer;
import org.alfresco.indexing.shared.ElasticsearchTestTemplate;
import org.alfresco.indexing.shared.TransformCoreContainerApplicationContextInitializer;
import org.alfresco.indexing.shared.config.ElasticsearchIndexConfiguration;

/**
 * Indexing with the bundled prefix map, which is missing the namespaces the data uses (ACS-12851).
 * <p>
 * One cycle runs in setup; every test asserts on its result. The dump supplies the bulkobject namespace on an aspect and a property and {@code .empty} on a path element; the fixture adds a node whose own type uses bulkobject.
 */
@ActiveProfiles("test")
@SpringBootTest(classes = {BatchIndexingApp.class, ElasticsearchIndexConfiguration.class})
@ContextConfiguration(initializers = {
        ElasticsearchContainerApplicationContextInitializer.class,
        PostgresWithCustomNamespaceContainerApplicationContextInitializer.class,
        TransformCoreContainerApplicationContextInitializer.class,
        BatchIndexingAbstractIT.BatchIndexingPropertiesInitializer.class
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("Unresolved namespace during batch indexing")
class UnresolvedNamespaceBatchIndexingIT extends ElasticsearchTestTemplate
{
    private static final String EMPTY_URI = ".empty";

    /** cm:content node carrying the bulkobject aspect and property. */
    private static final String NODE_WITH_CUSTOM_ASPECT = "39d91419-de43-49d7-ba04-3dda305c21a2";
    /** Node reached through a child association in the .empty namespace. */
    private static final String NODE_UNDER_EMPTY_NAMESPACE = "ebe122de-89f5-4e63-b9c9-4d1d13a9a253";

    @Autowired
    private ContinuousReindexingService continuousReindexingService;

    @Autowired
    private ElasticsearchWatermarkStore watermarkStore;

    @Autowired
    private DlqReader dlqReader;

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeAll
    void indexOnce() throws WatermarkAccessException
    {
        shouldCleanIndex = false;
        seedWatermarkAtEpoch();

        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        logs.start();
        root.addAppender(logs);

        continuousReindexingService.runCycleSafe();
        await().pollDelay(2, TimeUnit.SECONDS)
                .timeout(2, MINUTES)
                .until(() -> getDoc(NODE_WITH_CUSTOM_ASPECT).isPresent());

        root.detachAppender(logs);
        logs.stop();
    }

    @Test
    @DisplayName("the aspect is absent from the document")
    void theAspectIsAbsentFromTheDocument()
    {
        assertThat(aspectsOf(NODE_WITH_CUSTOM_ASPECT))
                .isNotEmpty()
                .noneMatch(aspect -> aspect.startsWith("bulkobj:"));
    }

    @Test
    @DisplayName("the property is absent from the document")
    void thePropertyIsAbsentFromTheDocument()
    {
        assertThat(document(NODE_WITH_CUSTOM_ASPECT).get(encode("bulkobj:missingContentInfo"))).isNull();
        assertThat(propertiesOf(NODE_WITH_CUSTOM_ASPECT)).noneMatch(name -> name.startsWith("bulkobj:"));
    }

    @Test
    @DisplayName("the path element is written without a prefix")
    void thePathElementIsWrittenWithoutAPrefix()
    {
        // Mapped elements keep their prefix, the unresolved one does not.
        assertThat(pathsOf(NODE_UNDER_EMPTY_NAMESPACE))
                .containsExactly("/app:company_home/app:dictionary/app:smart_folders/smartfolder.sample");
    }

    @Test
    @DisplayName("the typed node is absent from the index")
    void theTypedNodeIsAbsentFromTheIndex()
    {
        assertThat(getDoc(CUSTOM_TYPE_NODE_UUID)).isEmpty();
    }

    @Test
    @DisplayName("the aspect loss is reported with its namespace URI")
    void theAspectLossIsReported()
    {
        assertReported("aspect", "missingContent");
    }

    @Test
    @DisplayName("the property loss is reported with its namespace URI")
    void thePropertyLossIsReported()
    {
        assertReported("property", "missingContentInfo");
    }

    @Test
    @DisplayName("the unprefixed path element is reported with its namespace URI")
    void thePathElementIsReported()
    {
        assertThat(warnings())
                .anySatisfy(message -> assertThat(message)
                        .containsIgnoringCase("path")
                        .contains(EMPTY_URI)
                        .contains("smartfolder.sample"));
    }

    @Test
    @DisplayName("the type loss is reported with its namespace URI")
    void theTypeLossIsReported()
    {
        assertReported("type", CUSTOM_TYPE_LOCAL_NAME);
    }

    @Test
    @DisplayName("the report says the node will not be indexed")
    void theReportSaysTheNodeWillNotBeIndexed()
    {
        assertThat(warnings())
                .anySatisfy(message -> assertThat(message)
                        .containsIgnoringCase("node")
                        .containsIgnoringCase("will not be indexed"));
    }

    @Test
    @DisplayName("the cycle reports success and the watermark advances")
    void theCycleReportsSuccess() throws WatermarkAccessException
    {
        WatermarkDocument watermark = watermarkStore.read().orElseThrow();

        assertThat(watermark.getLastRunStatus()).isEqualTo("COMPLETED");
        assertThat(watermark.getLastSuccessfulToTimeEpochMs()).isPositive();
    }

    @Test
    @DisplayName("the lost node is counted as filtered, not skipped")
    void theLostNodeIsCountedAsFiltered() throws WatermarkAccessException
    {
        WatermarkDocument watermark = watermarkStore.read().orElseThrow();

        assertThat(watermark.getLastRunSkipCount()).isZero();
        assertThat(watermark.getLastRunFilterCount()).isPositive();
    }

    @Test
    @DisplayName("the lost node is recorded in the dead-letter index so it can be recovered")
    void theLostNodeIsRecordedInTheDeadLetterIndex()
    {
        assertThat(dlqReader.scanEntries(1000))
                .anySatisfy(entry -> {
                    assertThat(entry.document().getNodeRef()).contains(CUSTOM_TYPE_NODE_UUID);
                    assertThat(entry.document().getFailureType()).isEqualTo("unresolved_namespace");
                    assertThat(entry.document().getFailureReason()).contains(CUSTOM_URI);
                });
    }

    private void assertReported(String kind, String localName)
    {
        assertThat(warnings())
                .as("a warning naming the %s, %s and %s", kind, CUSTOM_URI, localName)
                .anySatisfy(message -> assertThat(message)
                        .containsIgnoringCase(kind)
                        .contains(CUSTOM_URI)
                        .contains(localName));
    }

    private List<String> warnings()
    {
        return logs.list.stream()
                .filter(event -> event.getLevel().isGreaterOrEqual(Level.WARN))
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    private Map<String, Object> document(String uuid)
    {
        return getDoc(uuid).orElseThrow(() -> new AssertionError("document " + uuid + " is missing"));
    }

    @SuppressWarnings("unchecked")
    private List<String> aspectsOf(String uuid)
    {
        return (List<String>) document(uuid).get(encode(ASPECT));
    }

    @SuppressWarnings("unchecked")
    private List<String> propertiesOf(String uuid)
    {
        return (List<String>) document(uuid).get(PROPERTIES);
    }

    @SuppressWarnings("unchecked")
    private List<String> pathsOf(String uuid)
    {
        return (List<String>) document(uuid).get(encode(PATH));
    }

    /** Without this the first window starts at "now" and the dump's 2020 nodes are never read. */
    private void seedWatermarkAtEpoch() throws WatermarkAccessException
    {
        if (watermarkStore.read().isEmpty())
        {
            WatermarkDocument seed = new WatermarkDocument();
            seed.setLastSuccessfulToTimeEpochMs(1L);
            seed.setLastRunStatus("SEEDED");
            watermarkStore.write(seed);
        }
    }
}
