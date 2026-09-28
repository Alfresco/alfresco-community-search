/*-
 * #%L
 * Alfresco Search Community :: Reindexing Core
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
package org.alfresco.reindexing.processors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;

import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.CM_CONTENT_TR_STATUS;
import static org.alfresco.indexing.shared.TransformStatus.NO_TRANSFORM;
import static org.alfresco.indexing.shared.TransformStatus.TRANSFORM_OUTDATED;
import static org.alfresco.repo.event.v1.model.EventType.NODE_CREATED;
import static org.alfresco.repo.event.v1.model.EventType.NODE_UPDATED;

import java.util.Arrays;
import java.util.HashMap;
import java.util.function.Predicate;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.alfresco.indexing.shared.TransformStatus;
import org.alfresco.indexing.shared.transform.TransformStatusExtender;
import org.alfresco.reindexing.domain.transform.ContentTransformSupportService;
import org.alfresco.reindexing.model.ReindexingEvent;
import org.alfresco.repo.event.v1.model.EventData;
import org.alfresco.repo.event.v1.model.EventType;
import org.alfresco.repo.event.v1.model.NodeResource;
import org.alfresco.repo.event.v1.model.RepoEvent;

@ExtendWith(MockitoExtension.class)
class ReindexEventTrStatusAdderProcessorTest
{
    @Mock
    private ContentTransformSupportService contentTransformSupportService;
    private ReindexEventTrStatusAdderProcessor sut;

    @BeforeEach
    void setUp()
    {
        sut = new ReindexEventTrStatusAdderProcessor(contentTransformSupportService, new TransformStatusExtender());
    }

    @ParameterizedTest
    @MethodSource("transformEnabledEventTypes")
    @DisplayName("should add transform status for selected event types: NODE_CREATED, NODE_UPDATED")
    void shouldAddTrStatus(EventType eventType, TransformStatus expectedStatus)
    {
        // given
        doReturn(true).when(contentTransformSupportService).isContentTransformSupported(ArgumentMatchers.any());
        var event = event(eventType, true);

        // when
        var processedEvent = sut.process(event);

        // then
        assertThat(processedEvent.getData().getResource().getProperties())
                .containsAllEntriesOf(event.getData().getResource().getProperties())
                .containsEntry(CM_CONTENT_TR_STATUS, expectedStatus.name());
    }

    public static Stream<Arguments> transformEnabledEventTypes()
    {
        return Stream.of(
                Arguments.of(NODE_CREATED, NO_TRANSFORM),
                Arguments.of(NODE_UPDATED, TRANSFORM_OUTDATED));
    }

    @ParameterizedTest
    @MethodSource("transformIgnoredEventTypes")
    @DisplayName("should ignore events with incorrect types")
    void shouldNotAddTrStatus(EventType eventType)
    {
        // given
        doReturn(true).when(contentTransformSupportService).isContentTransformSupported(ArgumentMatchers.any());
        var event = event(eventType, true);

        // when
        var processedEvent = sut.process(event);

        // then
        assertThat(processedEvent.getData().getResource().getProperties())
                .doesNotContainKey(CM_CONTENT_TR_STATUS)
                .containsAllEntriesOf(event.getData().getResource().getProperties());
    }

    public static Stream<Arguments> transformIgnoredEventTypes()
    {
        return Arrays.stream(EventType.values())
                .filter(Predicate.not(NODE_CREATED::equals))
                .filter(Predicate.not(NODE_UPDATED::equals))
                .map(Arguments::of);
    }

    @ParameterizedTest
    @EnumSource(value = EventType.class)
    @DisplayName("should ignore event without properties")
    void shouldIgnoreWhenNoPropertiesInEvent(EventType eventType)
    {
        // given
        doReturn(true).when(contentTransformSupportService).isContentTransformSupported(ArgumentMatchers.any());
        var event = event(eventType, false);

        // when
        var processedEvent = sut.process(event);

        // then
        assertThat(processedEvent.getData().getResource().getProperties())
                .isNull();
    }

    private static ReindexingEvent event(EventType eventType, boolean withProperties)
    {
        NodeResource.Builder resource = NodeResource.builder();
        if (withProperties)
        {
            resource.setProperties(new HashMap<>());
        }
        return ReindexingEvent.builder()
                .withRepoEvent(RepoEvent.<EventData<NodeResource>> builder()
                        .setType(eventType.getType())
                        .setData(EventData.<NodeResource> builder().setResource(resource.build()).build())
                        .build())
                .build();
    }
}
