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

import static java.util.Collections.emptyMap;

import static org.opensearch.client.opensearch._types.BuiltinScriptLanguage.Painless;

import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.*;
import static org.alfresco.elasticsearch.shared.translator.AlfrescoQualifiedNameTranslator.encode;
import static org.alfresco.indexing.shared.utils.ArchiveFieldsScriptUtils.removeArchiveFieldsWhenAlive;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import jakarta.json.JsonValue;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.experimental.UtilityClass;
import org.opensearch.client.json.JsonData;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.client.opensearch._types.Script;

@UtilityClass
public class EsScriptUtils
{
    private static final ObjectMapper OM = OpenSearchJsonMapperFactory.createObjectMapper();

    /**
     * Build the Elasticsearch inline script with all fields passing to parameters.
     *
     * @param fieldsMapping
     *            the mapping of fields to be updated
     * @param source
     *            the script source
     * @return script instance
     */
    public static Script buildInlineScript(Map<String, Object> fieldsMapping, String source)
    {
        return new Script.Builder().inline(builder -> builder.lang(b -> b.builtin(Painless))
                .options(emptyMap())
                .params(parseToJsonData(fieldsMapping))
                .source(source))
                .build();
    }

    public static String contentInProgressScriptSource(Set<String> encodedFieldNamesToAssign)
    {
        return "if (ctx._source." + CONTENT_INDEXING_LAST_UPDATE + " != null && ctx._source." + CONTENT_INDEXING_LAST_UPDATE + " >= params." + CONTENT_INDEXING_LAST_UPDATE + ") "
                + "{ ctx.op = 'noop'; } "
                + "else "
                + "{ " + assignValuesFor(encodedFieldNamesToAssign) + "}";
    }

    public static String contentUpsertScriptSource(Set<String> encodedFieldNamesToAssign, Set<String> encodedFieldNamesToRemove)
    {
        return "if (ctx._source." + CONTENT_INDEXING_LAST_UPDATE + " != null && ctx._source." + CONTENT_INDEXING_LAST_UPDATE + " > params." + CONTENT_INDEXING_LAST_UPDATE + ") "
                + "{ ctx.op = 'noop'; } "
                + "else "
                + "{ " + assignValuesFor(encodedFieldNamesToAssign) + removeValuesFor(encodedFieldNamesToRemove) + "}";
    }

    /**
     * Clears content fields unless the document was content indexed after the cutoff. The cutoff is a separate parameter from the timestamp being written, so a reindex can use its start time to decide whether to clear while still storing the node's own event timestamp. The stored timestamp only moves forward, so a clear can never reopen the guard for an older transform reply.
     */
    public static String contentExclusionScriptSource(Set<String> encodedFieldNamesToRemove)
    {
        String timestamp = CONTENT_INDEXING_LAST_UPDATE;
        return "if (ctx._source." + timestamp + " != null && ctx._source." + timestamp + " > params." + CONTENT_EXCLUSION_CUTOFF + ") "
                + "{ ctx.op = 'noop'; } "
                + "else "
                + "{ if (ctx._source." + timestamp + " == null || ctx._source." + timestamp + " < params." + timestamp + ") "
                + "{ ctx._source." + timestamp + " = params." + timestamp + "; } "
                + removeValuesFor(encodedFieldNamesToRemove) + "}";
    }

    private static final Set<String> ENCODED_CONTENT_FIELDS_TO_CLEAR = Set.of(encode(CONTENT_ATTRIBUTE_NAME),
            encode(CM_CONTENT_TR_STATUS), encode(CM_CONTENT_TR_EX));

    /** The exclusion script for a node: assigns {@code fieldsToUpsert} and drops the content, transform status and transform error. */
    public static Script contentExclusionScript(Map<String, Object> fieldsToUpsert, long conflictCutoff)
    {
        Map<String, Object> scriptParams = new HashMap<>(fieldsToUpsert);
        scriptParams.put(CONTENT_EXCLUSION_CUTOFF, conflictCutoff);
        return buildInlineScript(scriptParams,
                contentExclusionScriptSource(ENCODED_CONTENT_FIELDS_TO_CLEAR));
    }

    public static String pathUpsertScriptSource(Set<String> encodedFieldNames)
    {
        return "if (ctx._source." + PATH_INDEXING_LAST_UPDATE + " != null && ctx._source." + PATH_INDEXING_LAST_UPDATE + " > params." + PATH_INDEXING_LAST_UPDATE + ") "
                + "{ ctx.op = 'noop'; } "
                + "else "
                + "{ " + assignValuesFor(encodedFieldNames) + "}";
    }

    public static String metadataUpsertScriptSource(Set<String> encodedFieldNames)
    {
        return "if (ctx._source." + METADATA_INDEXING_LAST_UPDATE + " != null && ctx._source." + METADATA_INDEXING_LAST_UPDATE + " > params." + METADATA_INDEXING_LAST_UPDATE + ") "
                + "{ ctx.op = 'noop'; } "
                + "else "
                + "{ " + assignValuesFor(encodedFieldNames) + removeArchiveFieldsWhenAlive(encodedFieldNames) + "}";
    }

    /**
     * Reindex conflict resolution script.
     * <p>
     * Noops if the document was updated by live indexing after reindex started, otherwise updates all fields.
     * <p>
     * Null check handles upsert cases where METADATA_INDEXING_LAST_UPDATE may not exist. Works for DB restore since older timestamps allow overwrite (restores ALIVE=true).
     */
    public static String reindexingUpdateScriptSource(Set<String> encodedFieldNames)
    {
        return "if (ctx._source." + METADATA_INDEXING_LAST_UPDATE + " != null && " +
                "ctx._source." + METADATA_INDEXING_LAST_UPDATE + " > params." + REINDEXING_START_TIME + ") "
                + "{ ctx.op = 'noop'; } "
                + "else "
                + "{ " + assignValuesFor(encodedFieldNames) + removeArchiveFieldsWhenAlive(encodedFieldNames) + "}";
    }

    public static String deleteDocumentScriptSource()
    {
        return "if ((ctx._source." + METADATA_INDEXING_LAST_UPDATE + " != null && ctx._source." + METADATA_INDEXING_LAST_UPDATE + " > params.eventTimestamp) "
                + "|| (ctx._source." + PATH_INDEXING_LAST_UPDATE + " != null && ctx._source." + PATH_INDEXING_LAST_UPDATE + " > params.eventTimestamp) "
                + "|| (ctx._source." + CONTENT_INDEXING_LAST_UPDATE + " != null && ctx._source." + CONTENT_INDEXING_LAST_UPDATE + " > params.eventTimestamp)) "
                + "{ ctx.op = 'noop'; } "
                + "else "
                + "{ ctx._source = params.doc; }";
    }

    /**
     * Archive (trashcan) deletion script.
     * <p>
     * Shares the very same out-of-order guard as {@link #deleteDocumentScriptSource()}, but instead of replacing the whole {@code _source} with a minimal tombstone it <b>merges</b> the supplied fields into the existing document. This keeps every indexed metadata field (and any previously indexed content) searchable while {@code ALIVE} is flipped to {@code false}.
     *
     * @param encodedFieldNames
     *            encoded names of the fields to assign from the script params
     */
    public static String archiveDocumentScriptSource(Set<String> encodedFieldNames)
    {
        return "if ((ctx._source." + METADATA_INDEXING_LAST_UPDATE + " != null && ctx._source." + METADATA_INDEXING_LAST_UPDATE + " > params.eventTimestamp) "
                + "|| (ctx._source." + PATH_INDEXING_LAST_UPDATE + " != null && ctx._source." + PATH_INDEXING_LAST_UPDATE + " > params.eventTimestamp) "
                + "|| (ctx._source." + CONTENT_INDEXING_LAST_UPDATE + " != null && ctx._source." + CONTENT_INDEXING_LAST_UPDATE + " > params.eventTimestamp)) "
                + "{ ctx.op = 'noop'; } "
                + "else "
                + "{ " + assignValuesFor(encodedFieldNames) + "}";
    }

    public static String removeFieldScriptSource(String encodedFieldName)
    {
        return "ctx._source.remove('" + encodedFieldName + "'); ";
    }

    private static String removeValuesFor(Set<String> encodedFieldNames)
    {
        var sb = new StringBuilder();
        encodedFieldNames.stream()
                .sorted()
                .forEach(fieldName -> sb.append(removeFieldScriptSource(fieldName)));
        return sb.toString();
    }

    private static String assignValuesFor(Set<String> encodedFieldNames)
    {
        var sb = new StringBuilder();
        encodedFieldNames.stream()
                .sorted()
                .forEach(fieldName -> sb.append("ctx._source['")
                        .append(fieldName)
                        .append("'] = params['")
                        .append(fieldName)
                        .append("']; "));
        return sb.toString();
    }

    private static Map<String, JsonData> parseToJsonData(Map<String, Object> fields)
    {
        JacksonJsonpMapper jacksonJsonpMapper = new JacksonJsonpMapper(OM);
        final Map<String, JsonData> jsonMap = new HashMap<>(fields.size());
        fields.forEach((key, value) -> {
            if (key != null)
            {
                jsonMap.put(key, value == null ? JsonData.of(JsonValue.NULL, jacksonJsonpMapper) : JsonData.of(value, jacksonJsonpMapper));
            }
        });
        return jsonMap;
    }
}
