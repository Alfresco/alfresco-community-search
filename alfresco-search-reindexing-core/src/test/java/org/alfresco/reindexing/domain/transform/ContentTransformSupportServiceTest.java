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
package org.alfresco.reindexing.domain.transform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;

import static org.alfresco.repo.event.v1.model.EventType.NODE_CREATED;

import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.alfresco.content.mediatype.AcceptedContentMediaTypesCache;
import org.alfresco.indexing.shared.config.FilteringConfig;
import org.alfresco.reindexing.model.ReindexingEvent;
import org.alfresco.repo.event.v1.model.ContentInfo;
import org.alfresco.repo.event.v1.model.EventData;
import org.alfresco.repo.event.v1.model.NodeResource;
import org.alfresco.repo.event.v1.model.RepoEvent;

@ExtendWith(MockitoExtension.class)
class ContentTransformSupportServiceTest
{
    private static final String NODE_TYPE = "type";
    private static final String MEDIA_TYPE = "application/pdf";
    @Mock
    private FilteringConfig filteringConfig;
    @Mock
    private AcceptedContentMediaTypesCache acceptedContentMediaTypesCache;

    private ContentTransformSupportService sut;

    @BeforeEach
    void setup()
    {
        this.sut = new ContentTransformSupportService(filteringConfig, acceptedContentMediaTypesCache);
    }

    @Test
    @DisplayName("should support content transform for event having correct data")
    void shouldSupportCorrectEvent()
    {
        // given
        var event = buildFullEvent();
        doReturn(true).when(filteringConfig).doesntContainContentNodeType(NODE_TYPE);
        doReturn(true).when(acceptedContentMediaTypesCache).isSupported(MEDIA_TYPE);

        // when
        var res = sut.isContentTransformSupported(event);

        // then
        assertThat(res).isTrue();
    }

    @ParameterizedTest(name = "[{index}] {arguments}")
    @CsvSource(useHeadersInDisplayName = true, textBlock = """
            IsNodeTypeSupported,IsMediaTypeSupported
            true,false
            false,true
            false,false
            """)
    @DisplayName("should not support content transform when content node type or media type filters applies")
    void shouldNotSupportWhenContentNodeTypeOrMediaTypeIsFiltered(boolean isNodeTypeSupported, boolean isMediaTypeSupported)
    {
        // given
        lenient().doReturn(isNodeTypeSupported).when(filteringConfig).doesntContainContentNodeType(NODE_TYPE);
        lenient().doReturn(isMediaTypeSupported).when(acceptedContentMediaTypesCache).isSupported(MEDIA_TYPE);
        var event = buildFullEvent();

        // when
        var res = sut.isContentTransformSupported(event);

        // then
        assertThat(res).isFalse();
    }

    @ParameterizedTest
    @MethodSource("incorrectEventProvider")
    @DisplayName("should not support content transform when reindexing event contain incorrect data")
    void shouldNotSupportForIncorrectEvent(ReindexingEvent event)
    {
        // given
        lenient().doReturn(true).when(filteringConfig).doesntContainContentNodeType(NODE_TYPE);
        lenient().doReturn(true).when(acceptedContentMediaTypesCache).isSupported(MEDIA_TYPE);

        // when
        var res = sut.isContentTransformSupported(event);

        // then
        assertThat(res).isFalse();
    }

    public static Stream<Arguments> incorrectEventProvider()
    {
        return Stream.of(
                Arguments.argumentSet("no node type", buildEvent(null, true, true)),
                Arguments.argumentSet("no content", buildEvent(NODE_TYPE, false, true)),
                Arguments.argumentSet("no metadata", buildEvent(NODE_TYPE, true, false)));
    }

    @Test
    @DisplayName("should not support content transform for null event")
    void shouldNotSupportForNullEvent()
    {
        // when
        var res = sut.isContentTransformSupported(null);

        // then
        assertThat(res).isFalse();
    }

    @Test
    @DisplayName("should not support content transform for event with blank content")
    void shouldNotSupportEventWithBlankContent()
    {
        // given
        var event = buildEvent(NODE_TYPE, true, true, new ContentInfo(MEDIA_TYPE, 0L, "UTF-8"));

        // when
        var res = sut.isContentTransformSupported(event);

        // then
        assertThat(res).isFalse();
    }

    private static ReindexingEvent buildFullEvent()
    {
        return buildEvent(NODE_TYPE, true, true);
    }

    private static ReindexingEvent buildEvent(String nodeType, boolean hasContent, boolean hasMetadata)
    {
        return buildEvent(nodeType, hasContent, hasMetadata, new ContentInfo(MEDIA_TYPE, 12L, "UTF-8"));
    }

    private static ReindexingEvent buildEvent(String nodeType, boolean hasContent, boolean hasMetadata, ContentInfo contentInfo)
    {
        NodeResource.Builder resource = NodeResource.builder();
        if (hasContent)
        {
            resource.setContent(contentInfo);
        }
        if (hasMetadata)
        {
            resource.setNodeType(nodeType).setProperties(new java.util.HashMap<>());
        }
        return ReindexingEvent.builder()
                .withRepoEvent(RepoEvent.<EventData<NodeResource>> builder()
                        .setType(NODE_CREATED.getType())
                        .setData(EventData.<NodeResource> builder().setResource(resource.build()).build())
                        .build())
                .build();
    }
}
