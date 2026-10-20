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
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.CONTENT_INDEXING_LAST_UPDATE;
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.METADATA_INDEXING_LAST_UPDATE;
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.PATH_INDEXING_LAST_UPDATE;

import java.util.Set;

import org.junit.jupiter.api.Test;

class EsScriptUtilsTest
{
    private static final String SYS_ARCHIVED_BY = "sys%3AarchivedBy";
    private static final String SYS_ARCHIVED_DATE = "sys%3AarchivedDate";

    private static final String TIMESTAMP_GUARD = "if ((ctx._source." + METADATA_INDEXING_LAST_UPDATE + " != null && ctx._source." + METADATA_INDEXING_LAST_UPDATE + " > params.eventTimestamp) "
            + "|| (ctx._source." + PATH_INDEXING_LAST_UPDATE + " != null && ctx._source." + PATH_INDEXING_LAST_UPDATE + " > params.eventTimestamp) "
            + "|| (ctx._source." + CONTENT_INDEXING_LAST_UPDATE + " != null && ctx._source." + CONTENT_INDEXING_LAST_UPDATE + " > params.eventTimestamp)) "
            + "{ ctx.op = 'noop'; } else ";

    @Test
    void purgeScriptShouldWipeTheWholeSource()
    {
        var script = EsScriptUtils.deleteDocumentScriptSource();

        assertThat(script)
                .startsWith(TIMESTAMP_GUARD)
                .endsWith("{ ctx._source = params.doc; }");
    }

    @Test
    void archiveScriptShouldMergeFieldsInsteadOfWipingTheSource()
    {
        var script = EsScriptUtils.archiveDocumentScriptSource(Set.of(ALIVE, METADATA_INDEXING_LAST_UPDATE, "cm%3Atitle"));

        assertThat(script)
                .startsWith(TIMESTAMP_GUARD)
                .doesNotContain("ctx._source = params.doc")
                .contains("ctx._source['" + ALIVE + "'] = params['" + ALIVE + "']; ")
                .contains("ctx._source['" + METADATA_INDEXING_LAST_UPDATE + "'] = params['" + METADATA_INDEXING_LAST_UPDATE + "']; ")
                .contains("ctx._source['cm%3Atitle'] = params['cm%3Atitle']; ");
    }

    @Test
    void metadataUpsertScriptShouldDropArchiveFieldsWhenNodeIsAlive()
    {
        var script = EsScriptUtils.metadataUpsertScriptSource(Set.of(ALIVE, METADATA_INDEXING_LAST_UPDATE, "cm%3Atitle"));

        assertThat(script)
                .contains("if (params['" + ALIVE + "'] == true) { ")
                .contains("ctx._source.remove('" + SYS_ARCHIVED_BY + "');")
                .contains("ctx._source.remove('" + SYS_ARCHIVED_DATE + "');");
    }

    @Test
    void reindexingUpdateScriptShouldDropArchiveOnlyFieldsWhenNodeIsAlive()
    {
        var script = EsScriptUtils.reindexingUpdateScriptSource(Set.of(ALIVE, METADATA_INDEXING_LAST_UPDATE, "cm%3Atitle"));

        assertThat(script)
                .contains("if (params['" + ALIVE + "'] == true) { ")
                .contains("ctx._source.remove('" + SYS_ARCHIVED_BY + "');")
                .contains("ctx._source.remove('" + SYS_ARCHIVED_DATE + "');");
    }

    @Test
    void reindexingPathOnlyUpdateScriptShouldNotTouchArchiveOnlyFields()
    {
        var script = EsScriptUtils.reindexingUpdateScriptSource(Set.of(PATH_INDEXING_LAST_UPDATE, "PATH"));

        assertThat(script)
                .doesNotContain("ctx._source.remove('" + SYS_ARCHIVED_BY + "');")
                .doesNotContain("ctx._source.remove('" + SYS_ARCHIVED_DATE + "');");
    }

    @Test
    void archiveScriptShouldKeepArchiveOnlyFields()
    {
        var script = EsScriptUtils.archiveDocumentScriptSource(Set.of(ALIVE, SYS_ARCHIVED_BY, SYS_ARCHIVED_DATE));

        assertThat(script)
                .doesNotContain("ctx._source.remove('" + SYS_ARCHIVED_BY + "');")
                .doesNotContain("ctx._source.remove('" + SYS_ARCHIVED_DATE + "');");
    }

    @Test
    void archiveAndPurgeScriptsShouldShareTheSameOutOfOrderGuard()
    {
        var archiveGuard = EsScriptUtils.archiveDocumentScriptSource(Set.of(ALIVE)).split("else ")[0];
        var purgeGuard = EsScriptUtils.deleteDocumentScriptSource().split("else ")[0];

        assertThat(archiveGuard).isEqualTo(purgeGuard);
    }
}
