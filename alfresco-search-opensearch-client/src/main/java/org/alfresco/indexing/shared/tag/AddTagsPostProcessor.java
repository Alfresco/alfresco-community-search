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

import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.CM_TAGGABLE;
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.TAG;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.alfresco.indexing.shared.ElasticsearchFieldsPostProcessor;
import org.alfresco.indexing.shared.exception.ElasticsearchUnexpectedException;
import org.alfresco.indexing.shared.extractor.EncodedFields;

public class AddTagsPostProcessor implements ElasticsearchFieldsPostProcessor
{

    private static final Logger LOGGER = LoggerFactory.getLogger(AddTagsPostProcessor.class);

    private final TagRepository tagRepository;

    private final boolean removeTaggableAttribute;
    private final boolean addEmptyTagAttribute;
    private final boolean failOnMissingTag;

    public AddTagsPostProcessor(TagRepository tagRepository)
    {
        this(tagRepository, true, true, true);
    }

    public AddTagsPostProcessor(TagRepository tagRepository, boolean removeTaggableAttribute, boolean addEmptyTagAttribute, boolean failOnMissingTag)
    {
        this.tagRepository = Objects.requireNonNull(tagRepository);
        this.removeTaggableAttribute = removeTaggableAttribute;
        this.addEmptyTagAttribute = addEmptyTagAttribute;
        this.failOnMissingTag = failOnMissingTag;
    }

    @Override
    public void postProcessFields(EncodedFields fields)
    {
        var cmTaggable = removeTaggableAttribute ? fields.remove(CM_TAGGABLE) : fields.get(CM_TAGGABLE);
        var tagNames = cmTaggable.map(this::getTagNames).orElse(List.of());

        if (addEmptyTagAttribute || !tagNames.isEmpty())
        {
            fields.put(TAG, tagNames);
        }
    }

    private List<String> getTagNames(Object cmTaggable)
    {
        return tagIds(cmTaggable)
                .map(this::getTagName)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .toList();
    }

    /**
     * Get the tag ids from the cm:taggable property.
     *
     * @param cmTaggable
     *            The value of the cm:taggable property.
     * @return The stream of tag ids.
     */
    private Stream<String> tagIds(Object cmTaggable)
    {
        if (cmTaggable instanceof Collection<?>)
        {
            return ((Collection) cmTaggable).stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast);
        }
        if (cmTaggable instanceof String)
        {
            return Stream
                    .of(cmTaggable)
                    .map(String.class::cast);
        }
        throw new UnsupportedOperationException("Unexpected `" + CM_TAGGABLE + "` type `" + cmTaggable.getClass() + "`");
    }

    private Optional<String> getTagName(String tagId)
    {
        Optional<String> tagName = tagRepository.getTagName(tagId);
        if (tagName.isEmpty())
        {
            if (failOnMissingTag)
            {
                throw new ElasticsearchUnexpectedException("Failed to find tag name for id: %s".formatted(tagId));
            }
            else
            {
                LOGGER.warn("Couldn't find tag name for `{}`. Skipping it.", tagId);
            }
        }
        return tagName;
    }
}
