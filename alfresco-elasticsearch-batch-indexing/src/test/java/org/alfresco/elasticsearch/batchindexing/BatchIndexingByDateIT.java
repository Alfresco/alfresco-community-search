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
import static org.assertj.core.api.InstanceOfAssertFactories.list;

import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.ACCESS_DATE_FIELD;
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.ALIVE;
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.ASPECT;
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.CONTENT_ENCODING;
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.CONTENT_MIME_TYPE;
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.CONTENT_SIZE;
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.CREATION_DATE_FIELD;
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.METADATA_INDEXING_LAST_UPDATE;
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.MODIFICATION_DATE_FIELD;
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.NAME;
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.PATH;
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.PATH_INDEXING_LAST_UPDATE;
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.PRIMARY_HIERARCHY;
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.PROPERTIES;
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.READER;
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.TAG;
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.TYPE;
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.USER_CREATOR;
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.USER_MODIFIER;
import static org.alfresco.elasticsearch.shared.translator.AlfrescoQualifiedNameTranslator.encode;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.annotation.DirtiesContext;

import org.alfresco.indexing.shared.model.ElasticSearchQueryDto;
import org.alfresco.reindexing.beans.RepositoryDescriptor;

/**
 * Integration tests for the batch-indexing module using the {@code reindexByDate} job. Reuses the same Alfresco SQL dump as the reindexing module and verifies metadata, path, content and permission fields are indexed correctly.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public class BatchIndexingByDateIT extends BatchIndexingAbstractIT
{

    @BeforeEach
    void ensureIndexed()
    {
        shouldCleanIndex = false;
        runCycleAndWaitForIndexing();
    }

    @Test
    void allExpectedNodes_shouldBeIndexed()
    {
        assertThat(count()).isEqualTo(expectedNodeCount);
    }

    @Test
    void deletedDocuments_shouldBeMarkedAsDeleted()
    {
        long deletedDocumentsCount = getHitsNumberBy(ElasticSearchQueryDto.builder()
                .queryAttributes(Map.of(encode(ALIVE), false))
                .build());
        assertThat(deletedDocumentsCount).isEqualTo(nodesDeletedInDatabaseTotalCount);
    }

    @Test
    void basicFields_areIndexedCorrectly()
    {
        Optional<Map<String, Object>> getByID = getDoc("dd6203fd-7bd1-4af9-8d93-f3d108509a7c");
        assertThat(getByID).isPresent();
        Map<String, Object> document = getByID.get();

        assertThat(document.get(encode(NAME))).isEqualTo("Apr nt response.docx");
        assertThat(document.get(encode(TYPE))).isEqualTo("cm:content");
        assertThat(document.get(encode(MODIFICATION_DATE_FIELD))).isEqualTo("2021-04-21T12:42:30.413Z");
        assertThat(document.get(encode(CREATION_DATE_FIELD))).isEqualTo("2020-12-24T23:42:39.000Z");
        assertThat(document.get(encode(USER_CREATOR))).isEqualTo("User2");
        assertThat(document.get(encode(ACCESS_DATE_FIELD))).isEqualTo("2020-01-02T03:16:00.000Z");
        assertThat(document.get(encode(USER_MODIFIER))).isEqualTo("admin");
        assertThat(document.get(encode(METADATA_INDEXING_LAST_UPDATE))).isEqualTo(1_619_008_950_450L);
        assertThat(document.get(encode(PATH_INDEXING_LAST_UPDATE))).isEqualTo(1_619_008_950_450L);
    }

    @Test
    void propertiesWithNonMappedNamespace_shouldBeIgnored()
    {
        Optional<Map<String, Object>> getByID = getDoc("60da7b8d-88cf-4fee-abe7-9183f9261aff");
        assertThat(getByID).isPresent();

        Map<String, Object> document = getByID.get();

        assertThat((List<String>) document.get(PROPERTIES))
                .hasSameElementsAs(List.of("cm:title",
                        "cm:to",
                        "cm:description",
                        "cm:from",
                        "cm:publisher",
                        "cm:identifier",
                        "cm:accessed",
                        "cm:hits",
                        "cm:type",
                        "cm:contributor"));

        assertThat(document.get(encode("bulkobj:missingContentInfo"))).isNull();
        assertThat(document.get(encode("cm:from"))).isNotNull();
    }

    @Test
    void numericFields_areIndexedCorrectly()
    {
        List<Map<String, Object>> documents = searchDocs(ElasticSearchQueryDto.builder()
                .queryAttributes(Map.of(encode("cm:hits"), 90))
                .build());
        assertThat(documents).hasSize(1);
    }

    @Test
    void textFields_areIndexedCorrectly()
    {
        List<Map<String, Object>> documents = searchDocs(ElasticSearchQueryDto.builder()
                .queryAttributes(Map.of(encode("cm:publisher"), "Penguin"))
                .build());
        assertThat(documents).hasSize(10);
    }

    @Test
    void nodeRefs_areIndexedCorrectly()
    {
        Optional<Map<String, Object>> getDocumentById = getDoc("06717873-38e5-40c5-ba47-ddd7a8d81bce");
        assertThat(getDocumentById).isPresent();

        Map<String, Object> document = getDocumentById.get();
        assertThat(document.get(encode("cm:homeFolder"))).isEqualTo("ddf90839-2914-4b07-81c5-b66ced26d99f");
    }

    @Test
    void contentMetadata_areIndexedCorrectly()
    {
        Optional<Map<String, Object>> getByID = getDoc("d1ddf3fc-ffd5-4877-b181-8f681f1f1ee8");
        assertThat(getByID).isPresent();
        Map<String, Object> document = getByID.get();

        assertThat(document.get(encode(CONTENT_MIME_TYPE))).isEqualTo("text/plain");
        assertThat(document.get(encode(CONTENT_SIZE))).isEqualTo(923);
        assertThat(document.get(encode(CONTENT_ENCODING))).isEqualTo(StandardCharsets.UTF_8.name());
    }

    @Test
    void contentMetadataWithSingleTag_isIndexedCorrectly()
    {
        Optional<Map<String, Object>> getByID = getDoc("7b60e395-225d-44fd-a292-4220f4c5cc29");
        assertThat(getByID).isPresent();
        Map<String, Object> document = getByID.get();

        assertThat(document.get(encode(TAG)))
                .asInstanceOf(list(String.class))
                .containsExactly("tag1");
    }

    @Test
    void contentMetadataWithMultipleTags_isIndexedCorrectly()
    {
        Optional<Map<String, Object>> getByID = getDoc("d1ddf3fc-ffd5-4877-b181-8f681f1f1ee8");
        assertThat(getByID).isPresent();
        Map<String, Object> document = getByID.get();

        assertThat(document.get(encode(TAG)))
                .asInstanceOf(list(String.class))
                .containsExactlyInAnyOrder("tag1", "tag2");
    }

    @Test
    void multiValueFields_areIndexedCorrectly()
    {
        List<Map<String, Object>> documents = searchDocs(ElasticSearchQueryDto.builder()
                .queryAttributes(Map.of(encode("cm:addressees"), "e@e"))
                .build());
        assertThat(documents).hasSize(2);
        for (Map<String, Object> document : documents)
        {
            assertThat((List<String>) document.get(encode("cm:addressees"))).hasSize(2);
        }
    }

    @Test
    void aspects_areIndexedCorrectly()
    {
        List<Map<String, Object>> documents = searchDocs(ElasticSearchQueryDto.builder()
                .queryAttributes(Map.of(encode(NAME), "uploaded"))
                .build());
        assertThat(documents).hasSize(1);
        assertThat((List<String>) documents.get(0).get(ASPECT))
                .hasSameElementsAs(List.of("app:inlineeditable",
                        "cm:thumbnailModification",
                        "cm:titled",
                        "rn:renditioned",
                        "cm:auditable"));
    }

    @Test
    void permissions_areIndexedCorrectly()
    {
        List<Map<String, Object>> documents = searchDocs(ElasticSearchQueryDto.builder()
                .queryAttributes(Map.of(encode(READER), "mjackson"))
                .build());
        assertThat(documents).hasSize(2);
        documents.forEach(d -> assertThat((List<String>) d.get(READER))
                .contains("ROLE_OWNER", "GROUP_EVERYONE", "mjackson"));
    }

    @Test
    void databaseDescriptor_shouldBeIndexed()
    {
        Map<String, Object> descriptor = searchDocs(ElasticSearchQueryDto.builder()
                .queryAttributes(Map.of("TYPE", RepositoryDescriptor.TYPE))
                .build()).get(0);
        assertThat(descriptor.get("sys:licenseMode")).isEqualTo("ENTERPRISE");
        assertThat(descriptor.get("sys:name")).isEqualTo("Main Repository");
        assertThat(descriptor.get("sys:versionMajor")).isEqualTo("7");
        assertThat(descriptor.get("sys:versionMinor")).isEqualTo("1");
        assertThat(descriptor.get("sys:versionSchema")).isEqualTo(15000);
    }

    @Test
    void pathAndPrimaryHierarchy_shouldBeIndexed()
    {
        Optional<Map<String, Object>> getByID = getDoc("dd6203fd-7bd1-4af9-8d93-f3d108509a7c");
        assertThat(getByID).isPresent();
        Map<String, Object> document = getByID.get();

        assertThat(document.get(encode(PATH)))
                .isEqualTo(List.of("/app:company_home/cm:acl-benchmark/cm:folder-1/cm:folder-0/"
                        + "cm:f2627085-5a7b-4abe-89f2-aa6b82b3f291-json/cm:Apr nt response.docx"));

        assertThat((List<String>) document.get(encode(PRIMARY_HIERARCHY)))
                .hasSize(6)
                .containsExactly(
                        "4385ec15-5a8d-4afe-ad83-1c4d19940d65",
                        "e6787327-2ed6-4e28-a8de-2dd0e2ea03c5",
                        "f4cc5952-1efd-4537-81f1-a4b639696c72",
                        "db222ab8-d7b8-473c-b680-ac9272bbb9c1",
                        "952b5f79-98b7-4ed7-aa61-81f6ddfb2151",
                        "9bede7ad-b219-4bcf-a2e7-22a953807930");
    }

    @Test
    void notIndexedPropertyEqualsFalse_shouldBeMarkedAsDeleted()
    {
        // Nodes with cm:isIndexed=false are soft-deleted in ES (ALIVE=false), mirroring live-indexing (ACS-11904).
        List<String> notIndexedNodeIds = List.of(
                "4be01f24-2353-4dd4-989d-8c3f447a28e2",
                "be32fbac-106f-456a-800b-99c108ca5b9d",
                "a1f014db-b5a4-4ccd-8305-edc12e4a6f7b",
                "d6ce061c-7276-4eb0-8301-be80d16cacb5",
                "4cc7d3b9-ba16-4ddb-b723-7e8f2ee4a586",
                "aa62394d-b2db-4489-8a1c-5120ab61a6a5");

        for (String id : notIndexedNodeIds)
        {
            assertThat(getDoc(id))
                    .as("nodeId %s", id)
                    .hasValueSatisfying(doc -> assertThat(doc).containsEntry(encode(ALIVE), false));
        }
    }

    @Test
    void nodeWithDatabaseIdAboveIntegerRangeWasIncluded()
    {
        String id = "9c724bbd-9249-4a96-9661-2855e623da80";
        Optional<Map<String, Object>> document = getDoc(id);
        assertThat(document).isPresent();
    }

    @Test
    void blackListedAspect_shouldNotBeIndexed()
    {
        List<Map<String, Object>> documents = searchDocs(ElasticSearchQueryDto.builder()
                .queryAttributes(Map.of(encode("ASPECT"), "sys:hidden"))
                .build());
        assertThat(documents).isEmpty();
    }
}
