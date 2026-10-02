/*-
 * #%L
 * Alfresco Search Community :: Reindexing Core
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

package org.alfresco.reindexing.opensearch;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.alfresco.indexing.shared.model.BulkRequestDto;
import org.alfresco.indexing.shared.model.NodeResourceDto;
import org.alfresco.reindexing.model.ReindexingEvent;
import org.alfresco.repo.event.v1.model.RepoEvent;

public interface ElasticSearchUpdateOperations
{
    /**
     * Runs a path-update bulk with the writer-level retry loop applied. Returned DTO reports the input size, the count of items that failed on the final attempt, and the per-item failure details for that final attempt (empty when everything succeeded). Items that failed a mid-round but succeeded on a retry do not appear in {@code failures}.
     */
    BulkRequestDto updateElasticSearchWithBulkRequests(List<NodeResourceDto> nodeResourceDtos, int retryCount,
            long retryDelay) throws IOException, InterruptedException;

    void indexNode(String id, String index, Map<String, Object> source) throws IOException;

    Map<String, Object> getDescriptorFromElasticsearch(String type) throws Exception;

    /**
     * Runs the metadata bulk with a retry loop for transient per-item failures. On each round, item errors classified as retryable by {@link org.alfresco.reindexing.opensearch.impl.BulkItemErrorClassifier} are re-sent, up to {@code retryCount} extra rounds with {@code retryDelay} ms between rounds. Terminal errors (e.g. mapping errors) are recorded on the returned DTO's {@code failures} list without further retries. Items still retryable after the final round land in the same {@code failures} list.
     */
    BulkRequestDto getBulkRequest(List<RepoEvent> repoEvents, long reindexingStartTime, int retryCount, long retryDelay)
            throws IOException, InterruptedException;

    /**
     * Runs the merged metadata+path bulk (see {@code alfresco.reindex.writersMergedEnabled}). For each create/update event it emits a single scripted upsert carrying both the metadata and the path field sets, so the two dominant writes collapse into one ES round-trip and can never race into a {@code document_missing_exception} or version conflict. The content transform-status upsert is still emitted as its own operation, and delete events produce the usual soft-delete op. The same {@link org.alfresco.reindexing.opensearch.impl.BulkItemErrorClassifier} retry loop as the other bulks is applied. The returned DTO's {@code numberOfActions} counts source nodes (not raw bulk operations).
     */
    BulkRequestDto getMergedBulkRequest(List<ReindexingEvent> events, long reindexingStartTime, int retryCount,
            long retryDelay) throws IOException, InterruptedException;
}
