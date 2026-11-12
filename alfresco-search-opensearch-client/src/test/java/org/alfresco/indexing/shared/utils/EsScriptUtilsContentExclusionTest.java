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

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Structural checks on the exclusion Painless source. These do not run the script; they pin the two rules it must keep: noop only when content was indexed after the reindex cutoff, and a stored timestamp that only moves forward.
 */
@DisplayName("content exclusion script")
class EsScriptUtilsContentExclusionTest
{
    private static final String TIMESTAMP = "CONTENT_INDEXING_LAST_UPDATE";

    private final String source = EsScriptUtils.contentExclusionScriptSource(Set.of("cm%3Acontent"));

    @Test
    @DisplayName("noop only when the stored timestamp is after the reindex cutoff")
    void noopGuardUsesReindexCutoff()
    {
        assertThat(source).startsWith("if (ctx._source." + TIMESTAMP + " != null && ctx._source." + TIMESTAMP
                + " > params.CONTENT_EXCLUSION_CUTOFF) { ctx.op = 'noop'; } else if (");
    }

    @Test
    @DisplayName("a write that would change nothing is a noop, so a repeated clear does not write and an unindexed node is not created")
    void nothingToChangeIsANoop()
    {
        assertThat(source).contains("else if (!ctx._source.containsKey('cm%3Acontent') && ctx._source." + TIMESTAMP
                + " != null && ctx._source." + TIMESTAMP + " >= params." + TIMESTAMP + ") { ctx.op = 'noop'; } else {");
    }

    @Test
    @DisplayName("a node with nothing indexed yet still records the timestamp, so a reply from a transform in flight cannot write its content back")
    void absentTimestampIsNotANoop()
    {
        assertThat(source).doesNotContain("containsKey('cm%3Acontent')) { ctx.op = 'noop'; }");
    }

    @Test
    @DisplayName("stored timestamp is only ever moved forward, so a clear cannot reopen the guard for an older transform")
    void timestampOnlyMovesForward()
    {
        assertThat(source).contains("if (ctx._source." + TIMESTAMP + " == null || ctx._source." + TIMESTAMP
                + " < params." + TIMESTAMP + ") { ctx._source." + TIMESTAMP + " = params." + TIMESTAMP + "; }");
        assertThat(source).doesNotContain("else { ctx._source['" + TIMESTAMP + "'] = params");
    }

    @Test
    @DisplayName("the content fields are removed on the non-noop path")
    void contentFieldsAreRemoved()
    {
        assertThat(source).contains("ctx._source.remove('cm%3Acontent')");
    }
}
