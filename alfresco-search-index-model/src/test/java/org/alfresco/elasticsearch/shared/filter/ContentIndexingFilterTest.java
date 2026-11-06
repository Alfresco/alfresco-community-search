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
package org.alfresco.elasticsearch.shared.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import org.alfresco.repo.event.v1.model.ContentInfo;

class ContentIndexingFilterTest
{
    private static final long MAX_SIZE = 1_000L;

    @Test
    void acceptAllIndexesAnyContent()
    {
        assertThat(ContentIndexingFilter.acceptAll().isIndexable("node-1", new ContentInfo("application/zip", Long.MAX_VALUE, "UTF-8"))).isTrue();
    }

    @Test
    void nodeWithoutContentIsAlwaysIndexable()
    {
        assertThat(new ContentIndexingFilter(List.of("application/zip"), MAX_SIZE).isIndexable("node-1", null)).isTrue();
    }

    @Test
    void excludedMimeTypeIsNotIndexed()
    {
        var filter = new ContentIndexingFilter(List.of("application/zip"), 0L);

        assertThat(filter.isIndexable("node-1", new ContentInfo("application/zip", 10L, "UTF-8"))).isFalse();
        assertThat(filter.isIndexable("node-1", new ContentInfo("application/pdf", 10L, "UTF-8"))).isTrue();
    }

    @Test
    void mimeTypeMatchIgnoresCaseAndSurroundingWhitespace()
    {
        var filter = new ContentIndexingFilter(List.of("  Application/ZIP "), 0L);

        assertThat(filter.isIndexable("node-1", new ContentInfo("application/zip", 10L, "UTF-8"))).isFalse();
    }

    @Test
    void blankExcludedEntriesAreIgnored()
    {
        var filter = new ContentIndexingFilter(Arrays.asList("", "   ", null), 0L);

        assertThat(filter.isIndexable("node-1", new ContentInfo("application/zip", 10L, "UTF-8"))).isTrue();
    }

    @Test
    void contentAtTheSizeLimitIsIndexed()
    {
        var filter = new ContentIndexingFilter(List.of(), MAX_SIZE);

        assertThat(filter.isIndexable("node-1", new ContentInfo("application/pdf", MAX_SIZE, "UTF-8"))).isTrue();
    }

    @Test
    void contentAboveTheSizeLimitIsNotIndexed()
    {
        var filter = new ContentIndexingFilter(List.of(), MAX_SIZE);

        assertThat(filter.isIndexable("node-1", new ContentInfo("application/pdf", MAX_SIZE + 1, "UTF-8"))).isFalse();
    }

    @Test
    void wildcardMimeTypeExcludesEverySubtype()
    {
        var filter = new ContentIndexingFilter(List.of("video/*"), 0L);

        assertThat(filter.isIndexable("node-1", new ContentInfo("video/mp4", 10L, "UTF-8"))).isFalse();
        assertThat(filter.isIndexable("node-1", new ContentInfo("video/quicktime", 10L, "UTF-8"))).isFalse();
        assertThat(filter.isIndexable("node-1", new ContentInfo("application/video", 10L, "UTF-8"))).isTrue();
    }

    @Test
    void wildcardMatchIgnoresCase()
    {
        var filter = new ContentIndexingFilter(List.of("VIDEO/*"), 0L);

        assertThat(filter.isIndexable("node-1", new ContentInfo("video/MP4", 10L, "UTF-8"))).isFalse();
    }

    @Test
    void policyIsDescribedByToString()
    {
        assertThat(new ContentIndexingFilter(List.of("application/zip"), 2_097_152L).toString())
                .contains("application/zip")
                .contains("2097152");
        assertThat(new ContentIndexingFilter(List.of(), 0L).toString()).contains("unlimited");
    }

    @Test
    void zeroSizeLimitMeansNoLimit()
    {
        var filter = new ContentIndexingFilter(List.of(), 0L);

        assertThat(filter.isIndexable("node-1", new ContentInfo("application/pdf", Long.MAX_VALUE, "UTF-8"))).isTrue();
    }
}
