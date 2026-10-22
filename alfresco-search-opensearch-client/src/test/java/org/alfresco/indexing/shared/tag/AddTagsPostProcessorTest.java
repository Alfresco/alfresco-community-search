/*-
 * #%L
 * Alfresco Search Community :: OpenSearch Client
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
package org.alfresco.indexing.shared.tag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.ASPECT;
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.CM_TAGGABLE;
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.TAG;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.alfresco.elasticsearch.shared.translator.AlfrescoQualifiedNameTranslator;
import org.alfresco.indexing.shared.exception.MissingTagException;
import org.alfresco.indexing.shared.extractor.EncodedFields;

@ExtendWith(MockitoExtension.class)
class AddTagsPostProcessorTest
{
    private static final String CM_TAGGABLE_ENCODED = AlfrescoQualifiedNameTranslator.encode(CM_TAGGABLE);
    private static final String TAG_ID_1 = "tagId1";
    private static final String TAG_NAME_1 = "tagName1";
    private static final String TAG_ID_2 = "tagId2";
    private static final String TAG_NAME_2 = "tagName2";
    private static final String KEY = "testKey";
    private static final String VALUE = "testValue";
    private static final String SYS_ARCHIVED = "sys:archived";

    @Mock
    private TagRepository tagRepository;

    private AddTagsPostProcessor postProcessor;

    @Test
    void shouldAddTagsAndRemoveTaggableAttributeWhenCmTaggableListIsPresent()
    {
        // given
        when(tagRepository.getTagName(TAG_ID_1)).thenReturn(Optional.of(TAG_NAME_1));
        when(tagRepository.getTagName(TAG_ID_2)).thenReturn(Optional.of(TAG_NAME_2));
        postProcessor = new AddTagsPostProcessor(tagRepository);
        var fields = getTestFields();

        // when
        postProcessor.postProcessFields(fields);

        // then
        assertThat(fields.getEncodedMapping())
                .doesNotContainKey(CM_TAGGABLE_ENCODED)
                .hasEntrySatisfying(KEY, v -> assertThat(v).isEqualTo(VALUE))
                .hasEntrySatisfying(TAG, v -> assertThat(v).isInstanceOf(List.class)
                        .asInstanceOf(InstanceOfAssertFactories.LIST)
                        .containsExactly(TAG_NAME_1, TAG_NAME_2));
    }

    @Test
    void shouldAddTagAndRemoveTaggableAttributeWhenCmTaggableStringIsPresent()
    {
        // given
        when(tagRepository.getTagName(TAG_ID_1)).thenReturn(Optional.of(TAG_NAME_1));
        postProcessor = new AddTagsPostProcessor(tagRepository);
        var fields = new EncodedFields();
        fields.put(KEY, VALUE);
        fields.put(CM_TAGGABLE, TAG_ID_1);

        // when
        postProcessor.postProcessFields(fields);

        // then
        assertThat(fields.getEncodedMapping())
                .doesNotContainKey(CM_TAGGABLE_ENCODED)
                .hasEntrySatisfying(KEY, v -> assertThat(v).isEqualTo(VALUE))
                .hasEntrySatisfying(TAG, v -> assertThat(v).isInstanceOf(List.class)
                        .asInstanceOf(InstanceOfAssertFactories.LIST)
                        .containsExactly(TAG_NAME_1));
    }

    @Test
    void shouldAddEmptyTagAttributeWhenCmTaggableNotPresent()
    {
        // given
        postProcessor = new AddTagsPostProcessor(tagRepository);
        var fields = new EncodedFields();
        fields.put(KEY, VALUE);

        // when
        postProcessor.postProcessFields(fields);

        // then
        assertThat(fields.getEncodedMapping())
                .doesNotContainKey(CM_TAGGABLE_ENCODED)
                .hasEntrySatisfying(KEY, v -> assertThat(v).isEqualTo(VALUE))
                .hasEntrySatisfying(TAG, v -> assertThat(v).isInstanceOf(List.class)
                        .asInstanceOf(InstanceOfAssertFactories.LIST)
                        .isEmpty());
    }

    @Test
    void shouldAddEmptyTagAttributeWhenCmTaggableIsNull()
    {
        // given
        postProcessor = new AddTagsPostProcessor(tagRepository);
        var fields = new EncodedFields();
        fields.put(KEY, VALUE);
        fields.put(CM_TAGGABLE, null);

        // when
        postProcessor.postProcessFields(fields);

        // then
        assertThat(fields.getEncodedMapping())
                .doesNotContainKey(CM_TAGGABLE_ENCODED)
                .hasEntrySatisfying(KEY, v -> assertThat(v).isEqualTo(VALUE))
                .hasEntrySatisfying(TAG, v -> assertThat(v).isInstanceOf(List.class)
                        .asInstanceOf(InstanceOfAssertFactories.LIST)
                        .isEmpty());
    }

    @Test
    void shouldAddTagsAndKeepTaggableAttributeWhenRemoveTaggableAttributeIsFalse()
    {
        // given
        when(tagRepository.getTagName(TAG_ID_1)).thenReturn(Optional.of(TAG_NAME_1));
        when(tagRepository.getTagName(TAG_ID_2)).thenReturn(Optional.of(TAG_NAME_2));
        postProcessor = new AddTagsPostProcessor(tagRepository, false, true, true);
        var fields = getTestFields();

        // when
        postProcessor.postProcessFields(fields);

        // then
        assertThat(fields.getEncodedMapping())
                .containsKey(CM_TAGGABLE_ENCODED)
                .hasEntrySatisfying(KEY, v -> assertThat(v).isEqualTo(VALUE))
                .hasEntrySatisfying(TAG, v -> assertThat(v).isInstanceOf(List.class)
                        .asInstanceOf(InstanceOfAssertFactories.LIST)
                        .containsExactly(TAG_NAME_1, TAG_NAME_2));
    }

    @Test
    void shouldNotAddEmptyTagAttributeWhenCmTaggableNotPresentAndAddEmptyTagAttributeIsFalse()
    {
        // given
        postProcessor = new AddTagsPostProcessor(tagRepository, true, false, true);
        var fields = new EncodedFields();
        fields.put(KEY, VALUE);

        // when
        postProcessor.postProcessFields(fields);

        // then
        assertThat(fields.getEncodedMapping())
                .doesNotContainKey(CM_TAGGABLE_ENCODED)
                .doesNotContainKey(TAG)
                .hasEntrySatisfying(KEY, v -> assertThat(v).isEqualTo(VALUE));
    }

    @Test
    void shouldThrowExceptionWhenTagNotFoundAndFailOnMissingTagIsTrue()
    {
        // given
        when(tagRepository.getTagName(TAG_ID_1)).thenReturn(Optional.empty());
        postProcessor = new AddTagsPostProcessor(tagRepository);
        var fields = new EncodedFields();
        fields.put(CM_TAGGABLE, List.of(TAG_ID_1));

        // when, then
        var exception = assertThrows(MissingTagException.class, () -> postProcessor.postProcessFields(fields));
        assertThat(exception)
                .hasMessage("Failed to find tag name for id: " + TAG_ID_1);
        assertThat(exception.getTagId()).isEqualTo(TAG_ID_1);
    }

    @Test
    void shouldThrowExceptionWhenTagNotFoundOnLiveNodeWithOtherAspects()
    {
        // given
        when(tagRepository.getTagName(TAG_ID_1)).thenReturn(Optional.empty());
        postProcessor = new AddTagsPostProcessor(tagRepository);
        var fields = new EncodedFields();
        fields.put(ASPECT, Set.of("cm:auditable", "cm:taggable"));
        fields.put(CM_TAGGABLE, List.of(TAG_ID_1));

        // when, then
        assertThrows(MissingTagException.class, () -> postProcessor.postProcessFields(fields));
    }

    @Test
    void shouldSkipMissingTagWithoutExceptionWhenNodeIsArchivedAndFailOnMissingTagIsTrue()
    {
        // given: the tag was deleted after the node had been moved to the trashcan
        when(tagRepository.getTagName(TAG_ID_1)).thenReturn(Optional.empty());
        when(tagRepository.getTagName(TAG_ID_2)).thenReturn(Optional.of(TAG_NAME_2));
        postProcessor = new AddTagsPostProcessor(tagRepository);
        var fields = getTestFields();
        fields.put(ASPECT, Set.of("cm:auditable", SYS_ARCHIVED));

        // when
        postProcessor.postProcessFields(fields);

        // then
        assertThat(fields.getEncodedMapping())
                .doesNotContainKey(CM_TAGGABLE_ENCODED)
                .hasEntrySatisfying(KEY, v -> assertThat(v).isEqualTo(VALUE))
                .hasEntrySatisfying(TAG, v -> assertThat(v).isInstanceOf(List.class)
                        .asInstanceOf(InstanceOfAssertFactories.LIST)
                        .containsExactly(TAG_NAME_2));
    }

    @Test
    void shouldAddEmptyTagAttributeWhenAllTagsOfArchivedNodeAreDeleted()
    {
        // given
        when(tagRepository.getTagName(TAG_ID_1)).thenReturn(Optional.empty());
        postProcessor = new AddTagsPostProcessor(tagRepository);
        var fields = new EncodedFields();
        fields.put(ASPECT, Set.of(SYS_ARCHIVED));
        fields.put(CM_TAGGABLE, TAG_ID_1);

        // when
        postProcessor.postProcessFields(fields);

        // then
        assertThat(fields.getEncodedMapping())
                .doesNotContainKey(CM_TAGGABLE_ENCODED)
                .hasEntrySatisfying(TAG, v -> assertThat(v).isInstanceOf(List.class)
                        .asInstanceOf(InstanceOfAssertFactories.LIST)
                        .isEmpty());
    }

    @Test
    void shouldNotThrowExceptionAndSkipTagWhenTagNotFoundAndFailOnMissingTagIsFalse()
    {
        // given
        when(tagRepository.getTagName(TAG_ID_1)).thenReturn(Optional.empty());
        when(tagRepository.getTagName(TAG_ID_2)).thenReturn(Optional.of(TAG_NAME_2));
        postProcessor = new AddTagsPostProcessor(tagRepository, true, true, false);
        var fields = getTestFields();

        // when
        postProcessor.postProcessFields(fields);

        // then
        assertThat(fields.getEncodedMapping())
                .doesNotContainKey(CM_TAGGABLE_ENCODED)
                .hasEntrySatisfying(KEY, v -> assertThat(v).isEqualTo(VALUE))
                .hasEntrySatisfying(TAG, v -> assertThat(v).isInstanceOf(List.class)
                        .asInstanceOf(InstanceOfAssertFactories.LIST)
                        .containsExactly(TAG_NAME_2));
    }

    @Test
    void shouldThrowUnsupportedOperationExceptionForUnexpectedCmTaggableType()
    {
        // given
        postProcessor = new AddTagsPostProcessor(tagRepository);
        var fields = new EncodedFields();
        fields.put(CM_TAGGABLE, 123);

        // when, then
        assertThrows(UnsupportedOperationException.class, () -> postProcessor.postProcessFields(fields));
    }

    private EncodedFields getTestFields()
    {
        var fields = new EncodedFields();
        fields.put(KEY, VALUE);
        fields.put(CM_TAGGABLE, List.of(TAG_ID_1, TAG_ID_2));
        return fields;
    }
}
