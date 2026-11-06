/*-
 * #%L
 * Alfresco Search Community :: Search Index Model
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
package org.alfresco.elasticsearch.shared.config;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

import org.alfresco.elasticsearch.shared.filter.ContentIndexingFilter;

/**
 * Binds the {@code alfresco.content-indexing.filter.*} properties to the {@link ContentIndexingFilter} used by live indexing and reindexing.
 */
@Configuration
public class ContentIndexingFilterConfig
{
    private static final Logger LOGGER = LoggerFactory.getLogger(ContentIndexingFilterConfig.class);

    @Bean
    public ContentIndexingFilter contentIndexingFilter(
            @Value("${alfresco.content-indexing.filter.excluded-mime-types:}") List<String> excludedMimeTypes,
            @Value("${alfresco.content-indexing.filter.max-content-size:0}") DataSize maxContentSize)
    {
        ContentIndexingFilter filter = new ContentIndexingFilter(excludedMimeTypes, maxContentSize.toBytes());
        LOGGER.info("Content indexing policy: {}", filter);
        return filter;
    }
}
