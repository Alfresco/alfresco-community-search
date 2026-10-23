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

import static org.alfresco.elasticsearch.batchindexing.PostgresWithCustomNamespaceContainerApplicationContextInitializer.CUSTOM_PROPERTY_VALUE;
import static org.alfresco.elasticsearch.batchindexing.PostgresWithCustomNamespaceContainerApplicationContextInitializer.CUSTOM_TYPE_NODE_UUID;
import static org.alfresco.elasticsearch.batchindexing.PostgresWithCustomNamespaceContainerApplicationContextInitializer.CUSTOM_URI;
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.ASPECT;
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.TYPE;
import static org.alfresco.elasticsearch.shared.translator.AlfrescoQualifiedNameTranslator.encode;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;

import org.alfresco.elasticsearch.batchindexing.model.WatermarkDocument;
import org.alfresco.indexing.shared.ElasticsearchContainerApplicationContextInitializer;
import org.alfresco.indexing.shared.ElasticsearchTestTemplate;
import org.alfresco.indexing.shared.TransformCoreContainerApplicationContextInitializer;
import org.alfresco.indexing.shared.config.ElasticsearchIndexConfiguration;
import org.alfresco.indexing.shared.model.ElasticSearchQueryDto;
import org.alfresco.reindexing.config.PrefixNamespaceConfig;
import org.alfresco.reindexing.domain.namespace.response.PrefixUriMapping;

/**
 * Indexing with a prefixes file that carries the custom namespace, so every field resolves (ACS-12851).
 * <p>
 * The test index is created with {@code dynamic: false}, so a field absent from the mapping is kept in {@code _source} and never indexed. Production mappings come from ACS, which extends them on model activation.
 * <p>
 * Phases 6 onwards share the index, the mapping and the prefix map, so they run in order.
 */
@ActiveProfiles("test")
@SpringBootTest(classes = {BatchIndexingApp.class, ElasticsearchIndexConfiguration.class})
@ContextConfiguration(initializers = {
        ElasticsearchContainerApplicationContextInitializer.class,
        PostgresWithCustomNamespaceContainerApplicationContextInitializer.class,
        TransformCoreContainerApplicationContextInitializer.class,
        BatchIndexingAbstractIT.BatchIndexingPropertiesInitializer.class
})
@TestPropertySource(properties = "alfresco.reindex.prefixes-file=classpath:reindex.prefixes-with-bulkobject.json")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("Resolved namespace during batch indexing")
class ResolvedNamespaceBatchIndexingIT extends ElasticsearchTestTemplate
{
    private static final String OLD_PROPERTY_FIELD = encode("bulkobj:invoiceNumber");
    private static final String NEW_PROPERTY_FIELD = encode("acme:invoiceNumber");

    /** cm:content node carrying the bulkobject aspect and property. */
    private static final String NODE_WITH_CUSTOM_ASPECT = "39d91419-de43-49d7-ba04-3dda305c21a2";

    @Autowired
    private ContinuousReindexingService continuousReindexingService;

    @Autowired
    private ElasticsearchWatermarkStore watermarkStore;

    @Autowired
    private PrefixNamespaceConfig prefixNamespaceConfig;

    @Autowired
    private OpenSearchClient openSearch;

    @BeforeAll
    void indexOnce() throws WatermarkAccessException, IOException
    {
        shouldCleanIndex = false;
        reseedAndRunCycle();
    }

    @Test
    @Order(1)
    @DisplayName("the node is indexed with its custom type")
    void theNodeIsIndexedWithItsCustomType()
    {
        assertThat(document(CUSTOM_TYPE_NODE_UUID)).containsEntry(encode(TYPE), "bulkobj:invoice");
    }

    @Test
    @Order(2)
    @DisplayName("the aspect is present on the document")
    void theAspectIsPresentOnTheDocument()
    {
        assertThat(aspectsOf(NODE_WITH_CUSTOM_ASPECT)).contains("bulkobj:missingContent");
    }

    @Test
    @Order(3)
    @DisplayName("the property is present on the document")
    void thePropertyIsPresentOnTheDocument()
    {
        assertThat(document(NODE_WITH_CUSTOM_ASPECT)).containsKey(encode("bulkobj:missingContentInfo"));
    }

    @Test
    @Order(4)
    @DisplayName("the custom type is searchable, because TYPE is in the shipped mapping")
    void theCustomTypeIsSearchable()
    {
        assertThat(hitsFor(encode(TYPE), "bulkobj:invoice")).isEqualTo(1);
    }

    @Test
    @Order(5)
    @DisplayName("the custom property is stored but not searchable while the field is unmapped")
    void theCustomPropertyIsStoredButNotSearchable()
    {
        assertThat(document(CUSTOM_TYPE_NODE_UUID))
                .containsEntry(OLD_PROPERTY_FIELD, CUSTOM_PROPERTY_VALUE)
                .doesNotContainKey(NEW_PROPERTY_FIELD);

        assertThat(hitsFor(OLD_PROPERTY_FIELD, CUSTOM_PROPERTY_VALUE)).isZero();
    }

    @Test
    @Order(6)
    @DisplayName("the custom property is searchable once the field is mapped and the node reindexed")
    void theCustomPropertyIsSearchableOnceMapped() throws IOException, WatermarkAccessException
    {
        openSearch.indices().putMapping(request -> request
                .index(index)
                .properties(OLD_PROPERTY_FIELD, property -> property.keyword(keyword -> keyword)));

        reseedAndRunCycle();

        assertThat(hitsFor(OLD_PROPERTY_FIELD, CUSTOM_PROPERTY_VALUE)).isEqualTo(1);
    }

    @Test
    @Order(7)
    @DisplayName("changing the prefix leaves both property fields on the document")
    void changingThePrefixLeavesBothPropertyFields() throws IOException, WatermarkAccessException
    {
        prefixNamespaceConfig.setPrefixUriMap(mapWithPrefix("acme"));

        reseedAndRunCycle();

        // Metadata is assigned field by field, so the old field name survives the rename.
        assertThat(document(CUSTOM_TYPE_NODE_UUID))
                .containsEntry(NEW_PROPERTY_FIELD, CUSTOM_PROPERTY_VALUE)
                .containsEntry(OLD_PROPERTY_FIELD, CUSTOM_PROPERTY_VALUE);
    }

    @Test
    @Order(8)
    @DisplayName("the type is replaced, because its field name does not change")
    void theTypeIsReplaced()
    {
        assertThat(document(CUSTOM_TYPE_NODE_UUID)).containsEntry(encode(TYPE), "acme:invoice");
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

    private long hitsFor(String field, String value)
    {
        return getHitsNumberBy(ElasticSearchQueryDto.builder()
                .queryAttributes(Map.of(field, value))
                .build());
    }

    /** Searches are not realtime, so the index is refreshed before any hit count is asserted. */
    private void reseedAndRunCycle() throws WatermarkAccessException, IOException
    {
        WatermarkDocument seed = watermarkStore.read().orElseGet(WatermarkDocument::new);
        seed.setLastSuccessfulToTimeEpochMs(1L);
        seed.setLastRunStatus("SEEDED");
        watermarkStore.write(seed);

        continuousReindexingService.runCycleSafe();
        openSearch.indices().refresh(request -> request.index(index));
    }

    private static Map<String, String> mapWithPrefix(String prefix) throws IOException
    {
        try (InputStream json = new ClassPathResource("reindex.prefixes-with-bulkobject.json").getInputStream())
        {
            Map<String, String> map = new HashMap<>(
                    new ObjectMapper().readValue(json, PrefixUriMapping.class).prefixUriMap());
            map.put(CUSTOM_URI, prefix);
            return map;
        }
    }
}
