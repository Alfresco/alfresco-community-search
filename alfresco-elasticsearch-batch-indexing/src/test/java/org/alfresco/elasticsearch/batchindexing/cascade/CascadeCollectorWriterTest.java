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
package org.alfresco.elasticsearch.batchindexing.cascade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;

import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.CM_CATEGORY;
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.TYPE_DELETED;

import java.io.Serializable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.batch.infrastructure.item.Chunk;

import org.alfresco.reindexing.model.ReindexingEvent;
import org.alfresco.repo.event.v1.model.EventData;
import org.alfresco.repo.event.v1.model.EventType;
import org.alfresco.repo.event.v1.model.NodeResource;
import org.alfresco.repo.event.v1.model.RepoEvent;

class CascadeCollectorWriterTest
{
    private static final String NODE_REF_A = "workspace://SpacesStore/uuid-A";
    private static final String NODE_REF_B = "workspace://SpacesStore/uuid-B";
    private static final String NODE_REF_C = "workspace://SpacesStore/uuid-C";

    private CascadeSourceStash stash;
    private SourceDocLookup sourceDocLookup;
    private CascadeCollectorWriter writer;

    @BeforeEach
    void setUp()
    {
        stash = new CascadeSourceStash();
        sourceDocLookup = Mockito.mock(SourceDocLookup.class);
        writer = new CascadeCollectorWriter(stash, sourceDocLookup);
        given(sourceDocLookup.lookup(anyCollection())).willReturn(Map.of());
    }

    @Test
    @DisplayName("records rename candidate when sys:cascadeTx is set and old segments resolved via mget")
    void recordsRenameCandidateWithCascadeTx()
    {
        given(sourceDocLookup.lookup(anyCollection())).willReturn(Map.of(
                NODE_REF_A, new SourceDocLookup.SourceDocSnapshot("cm:oldName", "oldName", "cm:folder", List.of(), List.of())));
        Map<String, Serializable> props = new HashMap<>();
        props.put(CascadeCollectorWriter.SYS_CASCADE_TX, 54L);
        ReindexingEvent event = updateEvent(NODE_REF_A, "cm:folder", "newName", props);

        writer.write(Chunk.of(event));

        assertThat(stash.renameCount()).isEqualTo(1);
        assertThat(stash.categoryDeleteCount()).isZero();
        CascadeSourceBatch batch = stash.drain();
        assertThat(batch.renames()).singleElement().satisfies(r -> {
            assertThat(r.nodeRef()).isEqualTo(NODE_REF_A);
            assertThat(r.descendantUuid()).isEqualTo("uuid-A");
            assertThat(r.oldPrefixedSegment()).isEqualTo("cm:oldName");
            assertThat(r.newPrefixedSegment()).isEqualTo("cm:newName");
            assertThat(r.oldUnprefixedSegment()).isEqualTo("oldName");
            assertThat(r.newUnprefixedSegment()).isEqualTo("newName");
        });
    }

    @Test
    @DisplayName("skips rename candidate when mget returns no source doc (first-time index)")
    void skipsRenameWhenSourceDocMissing()
    {
        given(sourceDocLookup.lookup(anyCollection())).willReturn(Map.of());
        Map<String, Serializable> props = new HashMap<>();
        props.put(CascadeCollectorWriter.SYS_CASCADE_TX, 54L);

        writer.write(Chunk.of(updateEvent(NODE_REF_A, "cm:folder", "newName", props)));

        assertThat(stash.renameCount()).isZero();
    }

    @Test
    @DisplayName("skips rename candidate when new segment equals old segment (no-op)")
    void skipsRenameWhenSegmentsEqual()
    {
        given(sourceDocLookup.lookup(anyCollection())).willReturn(Map.of(
                NODE_REF_A, new SourceDocLookup.SourceDocSnapshot("cm:name", "name", "cm:folder", List.of(), List.of())));
        Map<String, Serializable> props = new HashMap<>();
        props.put(CascadeCollectorWriter.SYS_CASCADE_TX, 99L);

        writer.write(Chunk.of(updateEvent(NODE_REF_A, "cm:folder", "name", props)));

        assertThat(stash.renameCount()).isZero();
    }

    @Test
    @DisplayName("does NOT record rename when sys:cascadeTx is absent")
    void doesNotRecordRenameWithoutCascadeTx()
    {
        ReindexingEvent event = updateEvent(NODE_REF_A, "cm:folder", "f2", new HashMap<>());

        writer.write(Chunk.of(event));

        assertThat(stash.renameCount()).isZero();
        assertThat(stash.categoryDeleteCount()).isZero();
    }

    @Test
    @DisplayName("does NOT record rename when properties are null")
    void doesNotRecordRenameWithNullProperties()
    {
        ReindexingEvent event = updateEvent(NODE_REF_A, "cm:folder", "f2", null);

        writer.write(Chunk.of(event));

        assertThat(stash.renameCount()).isZero();
    }

    @Test
    @DisplayName("records category-delete only when pre-delete ES doc TYPE == cm:category")
    void recordsCategoryDeleteOnlyForCmCategory()
    {
        given(sourceDocLookup.lookup(anyCollection())).willReturn(Map.of(
                NODE_REF_B, new SourceDocLookup.SourceDocSnapshot(null, null, CM_CATEGORY,
                        List.of("/cm:categoryRoot/cm:catB/"),
                        List.of("/categoryRoot/catB/"))));
        ReindexingEvent event = deleteEvent(NODE_REF_B);

        writer.write(Chunk.of(event));

        assertThat(stash.renameCount()).isZero();
        assertThat(stash.categoryDeleteCount()).isEqualTo(1);
        assertThat(stash.drain().categoryDeletes()).singleElement().satisfies(d -> {
            assertThat(d.nodeRef()).isEqualTo(NODE_REF_B);
            assertThat(d.descendantUuid()).isEqualTo("uuid-B");
            assertThat(d.deletedCategoryPaths()).containsExactly("/cm:categoryRoot/cm:catB/");
            assertThat(d.deletedCategoryUnprefixedPaths()).containsExactly("/categoryRoot/catB/");
        });
    }

    @Test
    @DisplayName("ignores delete events whose pre-delete ES TYPE is NOT cm:category")
    void ignoresNonCategoryDelete()
    {
        given(sourceDocLookup.lookup(anyCollection())).willReturn(Map.of(
                NODE_REF_B, new SourceDocLookup.SourceDocSnapshot(null, null, "cm:folder", List.of(), List.of())));
        writer.write(Chunk.of(deleteEvent(NODE_REF_B)));

        assertThat(stash.categoryDeleteCount()).isZero();
    }

    @Test
    @DisplayName("ignores delete events for docs no longer present in Elasticsearch")
    void ignoresDeleteWhenSourceDocMissing()
    {
        given(sourceDocLookup.lookup(anyCollection())).willReturn(Map.of());
        writer.write(Chunk.of(deleteEvent(NODE_REF_C)));

        assertThat(stash.categoryDeleteCount()).isZero();
    }

    @Test
    @DisplayName("mixed chunk: partitions rename and category-delete candidates correctly")
    void mixedChunkPartitionsCorrectly()
    {
        given(sourceDocLookup.lookup(anyCollection())).willReturn(Map.of(
                NODE_REF_A, new SourceDocLookup.SourceDocSnapshot("cm:old", "old", "cm:folder", List.of(), List.of()),
                NODE_REF_B, new SourceDocLookup.SourceDocSnapshot(null, null, CM_CATEGORY, List.of(), List.of())));
        Map<String, Serializable> renameProps = new HashMap<>();
        renameProps.put(CascadeCollectorWriter.SYS_CASCADE_TX, 200L);

        ReindexingEvent renameEvent = updateEvent(NODE_REF_A, "cm:folder", "new-name", renameProps);
        ReindexingEvent plainEvent = updateEvent(NODE_REF_C, "cm:content", "unchanged", new HashMap<>());
        ReindexingEvent categoryDelete = deleteEvent(NODE_REF_B);

        writer.write(Chunk.of(renameEvent, plainEvent, categoryDelete));

        assertThat(stash.renameCount()).isEqualTo(1);
        assertThat(stash.categoryDeleteCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("gracefully skips events without a resource or without an id")
    void skipsMalformedEvents()
    {
        ReindexingEvent nullResource = ReindexingEvent.builder()
                .withRepoEvent(RepoEvent.<EventData<NodeResource>> builder()
                        .setData(EventData.<NodeResource> builder().build())
                        .build())
                .build();

        ReindexingEvent noId = updateEvent(null, "cm:folder", "n", new HashMap<>());

        writer.write(Chunk.of(nullResource, noId));

        assertThat(stash.renameCount()).isZero();
        assertThat(stash.categoryDeleteCount()).isZero();
    }

    @Test
    @DisplayName("accumulates across multiple chunk invocations within the same cycle")
    void accumulatesAcrossChunks()
    {
        given(sourceDocLookup.lookup(anyCollection())).willReturn(Map.of(
                NODE_REF_A, new SourceDocLookup.SourceDocSnapshot("cm:oldA", "oldA", "cm:folder", List.of(), List.of()),
                NODE_REF_C, new SourceDocLookup.SourceDocSnapshot("cm:oldC", "oldC", "cm:folder", List.of(), List.of()),
                NODE_REF_B, new SourceDocLookup.SourceDocSnapshot(null, null, CM_CATEGORY, List.of(), List.of())));
        Map<String, Serializable> props = new HashMap<>();
        props.put(CascadeCollectorWriter.SYS_CASCADE_TX, 77L);

        writer.write(Chunk.of(updateEvent(NODE_REF_A, "cm:folder", "a", props)));
        writer.write(Chunk.of(updateEvent(NODE_REF_C, "cm:folder", "c", props)));
        writer.write(Chunk.of(deleteEvent(NODE_REF_B)));

        assertThat(stash.renameCount()).isEqualTo(2);
        assertThat(stash.categoryDeleteCount()).isEqualTo(1);
    }

    private ReindexingEvent updateEvent(String nodeRef, String nodeType, String name,
            Map<String, Serializable> properties)
    {
        NodeResource.Builder nodeResourceBuilder = NodeResource.builder()
                .setId(nodeRef)
                .setNodeType(nodeType)
                .setName(name);
        if (properties != null)
        {
            nodeResourceBuilder.setProperties(properties);
        }
        RepoEvent<EventData<NodeResource>> repoEvent = RepoEvent.<EventData<NodeResource>> builder()
                .setData(EventData.<NodeResource> builder()
                        .setResource(nodeResourceBuilder.build())
                        .build())
                .setType(EventType.NODE_CREATED.getType())
                .build();
        return ReindexingEvent.builder().withRepoEvent(repoEvent).build();
    }

    private ReindexingEvent deleteEvent(String nodeRef)
    {
        NodeResource.Builder builder = NodeResource.builder()
                .setId(nodeRef)
                .setNodeType(TYPE_DELETED);
        RepoEvent<EventData<NodeResource>> repoEvent = RepoEvent.<EventData<NodeResource>> builder()
                .setData(EventData.<NodeResource> builder().setResource(builder.build()).build())
                .setType(EventType.NODE_DELETED.getType())
                .build();
        return ReindexingEvent.builder().withRepoEvent(repoEvent).build();
    }
}
