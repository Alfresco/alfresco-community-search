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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.env.MapPropertySource;

import org.alfresco.elasticsearch.shared.filter.ContentIndexingFilter;
import org.alfresco.repo.event.v1.model.ContentInfo;

/**
 * Builds the configuration in a plain {@link AnnotationConfigApplicationContext}, the kind of context that carries none of the converters Spring Boot registers. Live indexing and reindexing start through Spring Boot, but the configuration is also imported by contexts that do not, so every property it reads has to bind without them.
 * <p>
 * The context is deliberately not a Spring Boot one: under Spring Boot these tests pass whether or not the properties are read as text, so they would report nothing about the binding they exist to cover.
 */
@DisplayName("content indexing filter configuration")
class ContentIndexingFilterConfigTest
{
    private static final String EXCLUDED_MIME_TYPES = "alfresco.content-indexing.filter.excluded-mime-types";
    private static final String MAX_CONTENT_SIZE = "alfresco.content-indexing.filter.max-content-size";

    private static final ContentInfo SMALL_PDF = new ContentInfo("application/pdf", 1_024L, "UTF-8");
    private static final ContentInfo LARGE_PDF = new ContentInfo("application/pdf", 60L * 1024 * 1024, "UTF-8");
    private static final ContentInfo ZIP = new ContentInfo("application/zip", 1_024L, "UTF-8");

    @Test
    @DisplayName("a size with a unit binds without Spring Boot's conversion service")
    void sizeWithAUnitBindsInAPlainContext()
    {
        ContentIndexingFilter filter = filterFrom(Map.of(MAX_CONTENT_SIZE, "50MB"));

        assertThat(filter.isIndexable("node-1", SMALL_PDF)).isTrue();
        assertThat(filter.isIndexable("node-2", LARGE_PDF)).isFalse();
    }

    @Test
    @DisplayName("a size without a unit is read as bytes")
    void bareNumberIsReadAsBytes()
    {
        ContentIndexingFilter filter = filterFrom(Map.of(MAX_CONTENT_SIZE, "1024"));

        assertThat(filter.isIndexable("node-1", SMALL_PDF)).isTrue();
        assertThat(filter.isIndexable("node-2", new ContentInfo("application/pdf", 1_025L, "UTF-8"))).isFalse();
    }

    @Test
    @DisplayName("an unset size means no limit")
    void absentSizeMeansNoLimit()
    {
        assertThat(filterFrom(Map.of()).isIndexable("node-1", LARGE_PDF)).isTrue();
    }

    @Test
    @DisplayName("an empty size value means no limit, as an empty property arrives")
    void emptySizeMeansNoLimit()
    {
        assertThat(filterFrom(Map.of(MAX_CONTENT_SIZE, "")).isIndexable("node-1", LARGE_PDF)).isTrue();
    }

    @Test
    @DisplayName("excluded MIME types bind from a comma-separated value")
    void excludedMimeTypesBindFromACommaSeparatedValue()
    {
        ContentIndexingFilter filter = filterFrom(Map.of(EXCLUDED_MIME_TYPES, "application/zip,video/*"));

        assertThat(filter.isIndexable("node-1", ZIP)).isFalse();
        assertThat(filter.isIndexable("node-2", new ContentInfo("video/mp4", 1_024L, "UTF-8"))).isFalse();
        assertThat(filter.isIndexable("node-3", SMALL_PDF)).isTrue();
    }

    @Test
    @DisplayName("a negative size fails the context rather than being treated as a limit")
    void negativeSizeIsRejected()
    {
        assertThatThrownBy(() -> filterFrom(Map.of(MAX_CONTENT_SIZE, "-1")))
                .rootCause()
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(MAX_CONTENT_SIZE)
                .hasMessageContaining("must not be negative");
    }

    /**
     * Builds the configuration against the given properties in a context Spring Boot had no part in starting.
     * <p>
     * {@link PropertySourcesPlaceholderConfigurer} is registered so placeholders resolve strictly, as they do in a running application. Without it the context still resolves them, through the environment-backed resolver {@code AbstractApplicationContext} installs by default, but a placeholder with no default would be passed on as text rather than failing.
     */
    private static ContentIndexingFilter filterFrom(Map<String, Object> properties)
    {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext())
        {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", properties));
            context.register(ContentIndexingFilterConfig.class);
            context.registerBean(PropertySourcesPlaceholderConfigurer.class);
            context.refresh();
            return context.getBean(ContentIndexingFilter.class);
        }
    }
}
