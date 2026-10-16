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
package org.alfresco.indexing.shared.opensearch.request;

import static java.util.Collections.emptyMap;

import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.ALIVE;
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.CM_CONTENT_TR_STATUS;
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.CONTENT_INDEXING_LAST_UPDATE;
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.METADATA_INDEXING_LAST_UPDATE;
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.PATH_INDEXING_LAST_UPDATE;
import static org.alfresco.indexing.shared.utils.EsScriptUtils.archiveDocumentScriptSource;
import static org.alfresco.indexing.shared.utils.EsScriptUtils.contentInProgressScriptSource;
import static org.alfresco.indexing.shared.utils.EsScriptUtils.deleteDocumentScriptSource;
import static org.alfresco.indexing.shared.utils.EsScriptUtils.removeFieldScriptSource;
import static org.alfresco.indexing.shared.utils.EventUtils.getEventTimestamp;
import static org.alfresco.indexing.shared.utils.ResourceTools.extractTransformStatus;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.opensearch.client.opensearch._types.Script;
import org.opensearch.client.opensearch.core.bulk.BulkOperation;
import org.opensearch.client.opensearch.core.bulk.UpdateOperation;

import org.alfresco.indexing.shared.ElasticsearchProperties;
import org.alfresco.indexing.shared.extractor.EncodedFields;
import org.alfresco.indexing.shared.extractor.EncodedFieldsExtractor;
import org.alfresco.indexing.shared.utils.EsScriptUtils;
import org.alfresco.repo.event.v1.model.NodeResource;
import org.alfresco.repo.event.v1.model.RepoEvent;

/**
 * Abstract base class holding common helpers for building Elasticsearch bulk operations. Concrete subclasses provide flow-specific request building for live indexing or reindexing.
 */
@SuppressWarnings("PMD.AbstractClassWithoutAbstractMethod")
public abstract class AbstractElasticsearchRequestBuilder
{
    protected final ElasticsearchProperties elasticsearchProperties;
    protected final EncodedFieldsExtractor encodedFieldsExtractor;

    protected AbstractElasticsearchRequestBuilder(ElasticsearchProperties elasticsearchProperties, EncodedFieldsExtractor encodedFieldsExtractor)
    {
        this.elasticsearchProperties = elasticsearchProperties;
        this.encodedFieldsExtractor = encodedFieldsExtractor;
    }

    protected Optional<BulkOperation> createContentUpsertRequest(RepoEvent<?> event)
    {
        Optional<String> transformStatus = extractTransformStatus(event);
        if (transformStatus.isEmpty())
        {
            return Optional.empty();
        }
        var encodedFields = new EncodedFields();
        encodedFields.put(CM_CONTENT_TR_STATUS, transformStatus.get());
        encodedFields.put(CONTENT_INDEXING_LAST_UPDATE, getEventTimestamp(event));
        var fieldsMapping = encodedFields.getEncodedMapping();
        Set<String> fieldNames = fieldsMapping.keySet();
        var script = EsScriptUtils.buildInlineScript(fieldsMapping, contentInProgressScriptSource(fieldNames));
        return Optional.of(createUpsertOperation(getIdFromEvent(event), fieldsMapping, script));
    }

    /**
     * Builds the permanent (purge) deletion operation: the whole {@code _source} is replaced with a minimal tombstone holding only {@code ALIVE=false} and the indexing timestamps.
     */
    protected BulkOperation createDeleteRequest(RepoEvent event)
    {
        var eventTimestamp = getEventTimestamp(event);
        var encodedFields = new EncodedFields();
        encodedFields.put(METADATA_INDEXING_LAST_UPDATE, eventTimestamp);
        encodedFields.put(CONTENT_INDEXING_LAST_UPDATE, eventTimestamp);
        encodedFields.put(PATH_INDEXING_LAST_UPDATE, eventTimestamp);
        encodedFields.put(ALIVE, false);

        var fieldsMapping = encodedFields.getEncodedMapping();
        Map<String, Object> params = Map.of("doc", fieldsMapping, "eventTimestamp", eventTimestamp);
        var script = EsScriptUtils.buildInlineScript(params, deleteDocumentScriptSource());
        return createUpsertOperation(getIdFromEvent(event), fieldsMapping, script);
    }

    /**
     * Builds the archive (trashcan) deletion operation: the full metadata carried by the event is merged into the existing document and only {@code ALIVE} is flipped to {@code false}, so the node stays searchable through the {@code deleted-nodes} scope.
     */
    protected BulkOperation createArchiveRequest(RepoEvent event)
    {
        var eventTimestamp = getEventTimestamp(event);
        var encodedFields = encodedFieldsExtractor.extract(event);
        encodedFields.put(METADATA_INDEXING_LAST_UPDATE, eventTimestamp);
        encodedFields.put(ALIVE, false);

        var fieldsMapping = encodedFields.getEncodedMapping();
        Map<String, Object> params = new HashMap<>(fieldsMapping);
        params.put("eventTimestamp", eventTimestamp);
        var script = EsScriptUtils.buildInlineScript(params, archiveDocumentScriptSource(fieldsMapping.keySet()));
        return createUpsertOperation(getIdFromEvent(event), fieldsMapping, script);
    }

    protected BulkOperation createDeletePropertyRequest(RepoEvent event, String propertyName)
    {
        var script = EsScriptUtils.buildInlineScript(emptyMap(), removeFieldScriptSource(propertyName));
        return new BulkOperation.Builder().update(new UpdateOperation.Builder<>()
                .index(elasticsearchProperties.getIndexName())
                .retryOnConflict(elasticsearchProperties.getRetryOnConflict())
                .id(getIdFromEvent(event))
                .script(script).build())
                .build();
    }

    protected BulkOperation createUpsertOperation(String id, Map<String, Object> fieldsMapping, Script script)
    {
        return new BulkOperation.Builder().update(new UpdateOperation.Builder<>()
                .index(elasticsearchProperties.getIndexName())
                .retryOnConflict(elasticsearchProperties.getRetryOnConflict())
                .id(id)
                .upsert(fieldsMapping)
                .script(script).build())
                .build();
    }

    protected String getIdFromEvent(RepoEvent event)
    {
        NodeResource resource = (NodeResource) event.getData().getResource();
        return resource.getId();
    }
}
