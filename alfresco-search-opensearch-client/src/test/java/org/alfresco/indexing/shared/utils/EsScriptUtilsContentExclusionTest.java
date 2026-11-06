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
 * Structural checks on the content Painless sources. These do not run the scripts; they pin the rules they must keep: the exclusion noops only when content was indexed after the reindex cutoff, the stored timestamp only moves forward, the excluded version is recorded so a late reply for it is rejected, and only a policy-checked write can lift the exclusion.
 */
@DisplayName("content exclusion script")
class EsScriptUtilsContentExclusionTest
{
    private static final String TIMESTAMP = "CONTENT_INDEXING_LAST_UPDATE";
    private static final String EXCLUDED_AT = "CONTENT_INDEXING_EXCLUDED_AT";

    private final String source = EsScriptUtils.contentExclusionScriptSource(Set.of("cm%3Acontent"));

    @Test
    @DisplayName("noop only when the stored timestamp is after the reindex cutoff")
    void noopGuardUsesReindexCutoff()
    {
        assertThat(source).startsWith("if (ctx._source." + TIMESTAMP + " != null && ctx._source." + TIMESTAMP
                + " > params.CONTENT_EXCLUSION_CUTOFF) { ctx.op = 'noop'; } else {");
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

    @Test
    @DisplayName("the excluded content version is recorded and only ever moves forward")
    void exclusionMarkerIsRecorded()
    {
        assertThat(source).contains("if (ctx._source." + EXCLUDED_AT + " == null || ctx._source." + EXCLUDED_AT
                + " < params." + TIMESTAMP + ") { ctx._source." + EXCLUDED_AT + " = params." + TIMESTAMP + "; }");
    }

    @Test
    @DisplayName("a transform reply not newer than the exclusion is a noop, a newer one drops the marker")
    void transformReplyHonoursExclusion()
    {
        String reply = EsScriptUtils.contentUpsertScriptSource(Set.of(TIMESTAMP, "cm%3Acontent"), Set.of());

        assertThat(reply)
                .startsWith("if ((ctx._source." + TIMESTAMP + " != null && ctx._source." + TIMESTAMP + " > params." + TIMESTAMP + ") "
                        + "|| (ctx._source." + EXCLUDED_AT + " != null && ctx._source." + EXCLUDED_AT + " >= params." + TIMESTAMP + ")) { ctx.op = 'noop'; }")
                .endsWith("ctx._source.remove('" + EXCLUDED_AT + "'); }");
    }

    @Test
    @DisplayName("marking content in progress lifts an exclusion at or before the event, bypassing the stale timestamp guard")
    void inProgressLiftsExclusionAtOrBeforeEvent()
    {
        String inProgress = EsScriptUtils.contentInProgressScriptSource(Set.of(TIMESTAMP, "cm%3Acontent%2Etr_status"));

        assertThat(inProgress).isEqualTo(
                "if (ctx._source." + EXCLUDED_AT + " != null && ctx._source." + EXCLUDED_AT + " <= params." + TIMESTAMP + ") "
                        + "{ ctx._source['" + TIMESTAMP + "'] = params['" + TIMESTAMP + "']; ctx._source['cm%3Acontent%2Etr_status'] = params['cm%3Acontent%2Etr_status']; "
                        + "ctx._source.remove('" + EXCLUDED_AT + "'); } "
                        + "else if (ctx._source." + TIMESTAMP + " != null && ctx._source." + TIMESTAMP + " >= params." + TIMESTAMP + ") { ctx.op = 'noop'; } "
                        + "else { ctx._source['" + TIMESTAMP + "'] = params['" + TIMESTAMP + "']; ctx._source['cm%3Acontent%2Etr_status'] = params['cm%3Acontent%2Etr_status']; }");
    }
}
