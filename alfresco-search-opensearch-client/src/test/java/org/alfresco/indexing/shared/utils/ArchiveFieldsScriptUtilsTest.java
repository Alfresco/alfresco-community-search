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
package org.alfresco.indexing.shared.utils;

import static org.assertj.core.api.Assertions.assertThat;

import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.ALIVE;
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.METADATA_INDEXING_LAST_UPDATE;
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.PATH_INDEXING_LAST_UPDATE;
import static org.alfresco.indexing.shared.utils.ArchiveFieldsScriptUtils.ARCHIVE_FIELDS;
import static org.alfresco.indexing.shared.utils.ArchiveFieldsScriptUtils.removeArchiveFieldsWhenAlive;

import java.util.Set;

import org.junit.jupiter.api.Test;

class ArchiveFieldsScriptUtilsTest
{
    private static final String SYS_ARCHIVED_BY = "sys%3AarchivedBy";
    private static final String SYS_ARCHIVED_DATE = "sys%3AarchivedDate";

    @Test
    void archiveOnlyFieldsShouldBeEncoded()
    {
        assertThat(ARCHIVE_FIELDS).containsExactlyInAnyOrder(SYS_ARCHIVED_BY, SYS_ARCHIVED_DATE);
    }

    @Test
    void shouldRemoveArchiveFieldsOnlyWhenAliveIsTrue()
    {
        var fragment = removeArchiveFieldsWhenAlive(Set.of(ALIVE, METADATA_INDEXING_LAST_UPDATE, "cm%3Atitle"));

        assertThat(fragment)
                .contains("if (params['" + ALIVE + "'] == true) { ")
                .contains("ctx._source.remove('" + SYS_ARCHIVED_BY + "');")
                .contains("ctx._source.remove('" + SYS_ARCHIVED_DATE + "');");
    }

    @Test
    void shouldEmitNothingWhenAliveIsNotAssigned()
    {
        var fragment = removeArchiveFieldsWhenAlive(Set.of(PATH_INDEXING_LAST_UPDATE, "PATH"));

        assertThat(fragment).isEmpty();
    }

    @Test
    void shouldNotRemoveArchiveFieldsThatAreBeingAssigned()
    {
        var fragment = removeArchiveFieldsWhenAlive(Set.of(ALIVE, SYS_ARCHIVED_BY));

        assertThat(fragment)
                .doesNotContain("ctx._source.remove('" + SYS_ARCHIVED_BY + "');")
                .contains("ctx._source.remove('" + SYS_ARCHIVED_DATE + "');");
    }

    @Test
    void shouldEmitNothingWhenAllArchiveFieldsAreBeingAssigned()
    {
        var fragment = removeArchiveFieldsWhenAlive(Set.of(ALIVE, SYS_ARCHIVED_BY, SYS_ARCHIVED_DATE));

        assertThat(fragment).isEmpty();
    }
}
