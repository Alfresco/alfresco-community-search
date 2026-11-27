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
package org.alfresco.indexing.shared.opensearch.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.ScriptType.EXCEPTION_SCRIPT;
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.ScriptType.TEXT_SCRIPT;

import org.junit.jupiter.api.Test;

import org.alfresco.indexing.shared.TransformStatus;

class ContentIndexCmdTest
{
    private static final String ID = "testNodeId";
    private static final Long UPDATE_TIME = 1000L;
    private static final String TEXT = "test content";
    private static final String HASH = "46b5f1fc07ba950cdbf478fc0403668d2ad91b3b8084739e5681895a194f4e54";

    @Test
    void shouldCreateTextScriptWithoutFingerprint()
    {
        var cmd = ContentIndexCmd.withTextScript(ID, UPDATE_TIME, TEXT);

        assertThat(cmd.scriptType()).isEqualTo(TEXT_SCRIPT);
        assertThat(cmd.contentText()).isEqualTo(TEXT);
        assertThat(cmd.contentFingerprint()).isNull();
    }

    @Test
    void shouldCreateTextScriptWithFingerprint()
    {
        var cmd = ContentIndexCmd.withTextScript(ID, UPDATE_TIME, TEXT, HASH);

        assertThat(cmd.scriptType()).isEqualTo(TEXT_SCRIPT);
        assertThat(cmd.contentText()).isEqualTo(TEXT);
        assertThat(cmd.contentFingerprint()).isEqualTo(HASH);
    }

    @Test
    void shouldCreateExceptionScriptWithoutFingerprint()
    {
        var cmd = ContentIndexCmd.withExceptionScript(ID, UPDATE_TIME, TransformStatus.TRANSFORM_FAILED, "err");

        assertThat(cmd.scriptType()).isEqualTo(EXCEPTION_SCRIPT);
        assertThat(cmd.contentFingerprint()).isNull();
    }

    @Test
    void shouldTreatLegacyConstructorAsHavingNoFingerprint()
    {
        var legacy = new ContentIndexCmd(ID, UPDATE_TIME, TEXT_SCRIPT, TEXT, null, null);
        var canonical = new ContentIndexCmd(ID, UPDATE_TIME, TEXT_SCRIPT, TEXT, null, null, null);

        assertThat(legacy).isEqualTo(canonical);
        assertThat(legacy.hashCode()).isEqualTo(canonical.hashCode());
        assertThat(legacy.contentFingerprint()).isNull();
    }

    @Test
    void shouldRequireContentTextWhenFingerprintGiven()
    {
        assertThatThrownBy(() -> ContentIndexCmd.withTextScript(ID, UPDATE_TIME, null, HASH))
                .isInstanceOf(NullPointerException.class);
    }
}
