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

import static org.assertj.core.api.Assertions.assertThat;

import static org.alfresco.elasticsearch.batchindexing.PostgresWithCustomNamespaceContainerApplicationContextInitializer.CUSTOM_TYPE_NODE_UUID;
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.TYPE;
import static org.alfresco.elasticsearch.shared.translator.AlfrescoQualifiedNameTranslator.encode;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import org.alfresco.elasticsearch.batchindexing.model.WatermarkDocument;
import org.alfresco.indexing.shared.ElasticsearchContainerApplicationContextInitializer;
import org.alfresco.indexing.shared.ElasticsearchTestTemplate;
import org.alfresco.indexing.shared.TransformCoreContainerApplicationContextInitializer;
import org.alfresco.indexing.shared.config.ElasticsearchIndexConfiguration;
import org.alfresco.reindexing.config.PrefixNamespaceConfig;
import org.alfresco.reindexing.domain.namespace.response.PrefixUriMapping;

/**
 * Repairing a node that was lost while its namespace was unresolved (ACS-12851).
 * <p>
 * Setup indexes with the bundled map and checks the node was filtered out, so both phases start from the reported state.
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
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("Recovering a node lost to an unresolved namespace")
class UnresolvedNamespaceRecoveryBatchIndexingIT extends ElasticsearchTestTemplate
{
    @Autowired
    private ContinuousReindexingService continuousReindexingService;

    @Autowired
    private ElasticsearchWatermarkStore watermarkStore;

    @Autowired
    private PrefixNamespaceConfig prefixNamespaceConfig;

    @BeforeAll
    void indexAndConfirmTheNodeWasFilteredOut() throws WatermarkAccessException
    {
        shouldCleanIndex = false;
        reseedAndRunCycle();

        assertThat(getDoc(CUSTOM_TYPE_NODE_UUID)).isEmpty();
        assertThat(watermarkStore.read().orElseThrow().getLastRunFilterCount()).isPositive();
    }

    @Test
    @Order(1)
    @DisplayName("adding the namespace does not repair the node")
    void addingTheNamespaceDoesNotRepairTheNode() throws IOException
    {
        prefixNamespaceConfig.setPrefixUriMap(mapIncludingCustomNamespace());

        continuousReindexingService.runCycleSafe();

        // The cursor only moves forward, and no node changed, so nothing is re-read.
        assertThat(getDoc(CUSTOM_TYPE_NODE_UUID)).isEmpty();
    }

    @Test
    @Order(2)
    @DisplayName("reseeding the cursor repairs the node")
    void reseedingTheCursorRepairsTheNode() throws WatermarkAccessException
    {
        reseedAndRunCycle();

        assertThat(getDoc(CUSTOM_TYPE_NODE_UUID)).isPresent();
        assertThat(getDoc(CUSTOM_TYPE_NODE_UUID).orElseThrow().get(encode(TYPE))).isEqualTo("bulkobj:invoice");
    }

    private void reseedAndRunCycle() throws WatermarkAccessException
    {
        WatermarkDocument seed = watermarkStore.read().orElseGet(WatermarkDocument::new);
        seed.setLastSuccessfulToTimeEpochMs(1L);
        seed.setLastRunStatus("SEEDED");
        watermarkStore.write(seed);

        continuousReindexingService.runCycleSafe();
    }

    private static Map<String, String> mapIncludingCustomNamespace() throws IOException
    {
        try (InputStream json = new ClassPathResource("reindex.prefixes-with-bulkobject.json").getInputStream())
        {
            return new ObjectMapper().readValue(json, PrefixUriMapping.class).prefixUriMap();
        }
    }
}
