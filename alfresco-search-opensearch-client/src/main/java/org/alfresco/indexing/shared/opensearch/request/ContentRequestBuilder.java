/*
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

package org.alfresco.indexing.shared.opensearch.request;

import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.CM_CONTENT_TR_EX;
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.CM_CONTENT_TR_STATUS;
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.CONTENT_ATTRIBUTE_NAME;
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.CONTENT_INDEXING_LAST_UPDATE;
import static org.alfresco.elasticsearch.shared.translator.AlfrescoQualifiedNameTranslator.encode;
import static org.alfresco.indexing.shared.utils.EsScriptUtils.contentUpsertScriptSource;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import org.opensearch.client.opensearch.core.UpdateRequest;
import org.springframework.stereotype.Component;

import org.alfresco.indexing.shared.ElasticsearchProperties;
import org.alfresco.indexing.shared.opensearch.command.ContentIndexCmd;
import org.alfresco.indexing.shared.utils.EsScriptUtils;

@Component
@RequiredArgsConstructor
public class ContentRequestBuilder
{
    private static final String ENCODED_TR_STATUS = encode(CM_CONTENT_TR_STATUS);
    private static final String ENCODED_TR_EX = encode(CM_CONTENT_TR_EX);
    private static final String ENCODED_CONTENT_TEXT = encode(CONTENT_ATTRIBUTE_NAME);
    private final ElasticsearchProperties elasticsearchProperties;

    public UpdateRequest<?, ?> createUpsertRequest(ContentIndexCmd contentIndexCmd)
    {
        Map<String, Object> fieldsToUpsert = buildFieldsMapping(contentIndexCmd);
        String scriptSource = buildUpsertScript(contentIndexCmd);
        var script = EsScriptUtils.buildInlineScript(fieldsToUpsert, scriptSource);
        return new UpdateRequest.Builder<>()
                .index(elasticsearchProperties.getIndexName())
                .retryOnConflict(elasticsearchProperties.getRetryOnConflict())
                .id(contentIndexCmd.id())
                .upsert(fieldsToUpsert)
                .script(script)
                .build();
    }

    private Map<String, Object> buildFieldsMapping(ContentIndexCmd contentIndexCmd)
    {
        Map<String, Object> fields = new HashMap<>();

        fields.put(CONTENT_INDEXING_LAST_UPDATE, contentIndexCmd.updateTime());

        switch (contentIndexCmd.scriptType())
        {
        case EXCEPTION_SCRIPT -> {
            fields.put(ENCODED_TR_STATUS, contentIndexCmd.transformationStatus());
            fields.put(ENCODED_TR_EX, contentIndexCmd.transformationError());
        }
        case TEXT_SCRIPT -> fields.put(ENCODED_CONTENT_TEXT, contentIndexCmd.contentText());
        }
        return fields;
    }

    private String buildUpsertScript(ContentIndexCmd contentIndexCmd)
    {
        var identifier = contentIndexCmd.scriptType();
        return switch (identifier)
        {
        case EXCEPTION_SCRIPT -> {
            var encodedFieldNamesToAssign = Set.of(CONTENT_INDEXING_LAST_UPDATE, ENCODED_TR_STATUS, ENCODED_TR_EX);
            var encodedFieldNamesToRemove = Set.of(ENCODED_CONTENT_TEXT);
            yield contentUpsertScriptSource(encodedFieldNamesToAssign, encodedFieldNamesToRemove);
        }
        case TEXT_SCRIPT -> {
            var encodedFieldNamesToAssign = Set.of(CONTENT_INDEXING_LAST_UPDATE, ENCODED_CONTENT_TEXT);
            var encodedFieldNamesToRemove = Set.of(ENCODED_TR_STATUS, ENCODED_TR_EX);
            yield contentUpsertScriptSource(encodedFieldNamesToAssign, encodedFieldNamesToRemove);
        }
        };
    }
}
