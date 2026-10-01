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

import static org.alfresco.elasticsearch.shared.translator.AlfrescoQualifiedNameTranslator.encode;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.mapping.DynamicMapping;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import org.alfresco.elasticsearch.batchindexing.DlqReader.DlqEntry;
import org.alfresco.elasticsearch.batchindexing.model.WatermarkDocument;
import org.alfresco.indexing.shared.ElasticsearchContainerApplicationContextInitializer;
import org.alfresco.indexing.shared.ElasticsearchTestTemplate;
import org.alfresco.indexing.shared.TransformCoreContainerApplicationContextInitializer;
import org.alfresco.indexing.shared.config.ElasticsearchIndexConfiguration;
import org.alfresco.reindexing.config.PrefixNamespaceConfig;
import org.alfresco.reindexing.domain.namespace.response.PrefixUriMapping;

/**
 * Resolving a namespace the index mapping does not know about.
 * <p>
 * This test sets the mapping to strict itself; it is not the shipped default. ACS creates the index from basicFields.json with {@code dynamic: false}, so an unknown field is stored but not indexed and the node still lands — silently unsearchable rather than rejected. Strict is a configuration an operator can choose, and under it a single unknown field costs the whole document.
 * <p>
 * Setup indexes once with the mapping open so every legitimate field is registered, closes it, then resolves the custom namespace so only that field is unknown.
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
@DisplayName("Strict mapping and a newly resolved namespace")
class StrictMappingBatchIndexingIT extends ElasticsearchTestTemplate
{
    /** cm:content node whose bulkobject property starts resolving once the namespace is added. */
    private static final String NODE_WITH_CUSTOM_PROPERTY = "39d91419-de43-49d7-ba04-3dda305c21a2";
    private static final String CUSTOM_PROPERTY_FIELD = encode("bulkobj:missingContentInfo");

    @Autowired
    private ContinuousReindexingService continuousReindexingService;

    @Autowired
    private ElasticsearchWatermarkStore watermarkStore;

    @Autowired
    private PrefixNamespaceConfig prefixNamespaceConfig;

    @Autowired
    private OpenSearchClient openSearch;

    @Autowired
    private DlqReader dlqReader;

    @BeforeAll
    void resolveTheNamespaceAgainstAStrictMapping() throws WatermarkAccessException, IOException
    {
        shouldCleanIndex = false;

        // Open the mapping for the first pass so the node's ordinary fields are registered.
        setDynamicMapping(DynamicMapping.True);
        reseedAndRunCycle();

        // Written by the path writer and not picked up by dynamic mapping, so map it by hand;
        // otherwise it is the field Elasticsearch names first and the custom one is masked.
        mapAsKeyword(encode("cm:categories"));

        setDynamicMapping(DynamicMapping.Strict);
        prefixNamespaceConfig.setPrefixUriMap(mapIncludingCustomNamespace());
        reseedAndRunCycle();

        openSearch.indices().refresh(request -> request.index(index));
    }

    @Test
    @DisplayName("the update is rejected, so the document keeps its earlier incomplete form")
    void theUpdateIsRejected()
    {
        assertThat(document(NODE_WITH_CUSTOM_PROPERTY)).doesNotContainKey(CUSTOM_PROPERTY_FIELD);
    }

    @Test
    @DisplayName("the rejection is recorded in the dead-letter index as a strict-mapping failure")
    void theRejectionIsRecordedInTheDeadLetterIndex()
    {
        List<DlqEntry> entries = dlqReader.scanEntries(1000);

        assertThat(entries)
                .isNotEmpty()
                .anySatisfy(entry -> {
                    assertThat(entry.document().getNodeRef()).contains(NODE_WITH_CUSTOM_PROPERTY);
                    assertThat(entry.document().getFailureType()).isEqualTo("strict_dynamic_mapping_exception");
                    assertThat(entry.document().getFailureReason()).contains(CUSTOM_PROPERTY_FIELD);
                });
    }

    private Map<String, Object> document(String uuid)
    {
        return getDoc(uuid).orElseThrow(() -> new AssertionError("document " + uuid + " is missing"));
    }

    private void setDynamicMapping(DynamicMapping dynamic) throws IOException
    {
        openSearch.indices().putMapping(request -> request.index(index).dynamic(dynamic));
    }

    private void mapAsKeyword(String field) throws IOException
    {
        openSearch.indices().putMapping(request -> request
                .index(index)
                .properties(field, property -> property.keyword(keyword -> keyword)));
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
