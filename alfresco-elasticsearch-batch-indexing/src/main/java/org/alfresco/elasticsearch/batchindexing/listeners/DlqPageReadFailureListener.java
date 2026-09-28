/*-
 * #%L
 * Alfresco Elasticsearch Connector :: Batch Indexing Module
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
package org.alfresco.elasticsearch.batchindexing.listeners;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.alfresco.database.connector.NodeParams;
import org.alfresco.elasticsearch.batchindexing.DlqWriter;
import org.alfresco.elasticsearch.batchindexing.model.DeadLetterDocument.FailureStage;
import org.alfresco.reindexing.readers.PageReadFailureListener;

/**
 * Bridges page-level read failures (a bad row in a JDBC page fails the whole page — see reindexing {@code AbstractAlfrescoNodeReader}) into {@link DlqWriter}. The reader has already swallowed the failure and advanced past the page, so recording here is the only trail the incident leaves.
 *
 * <p>
 * Both page shapes must be recorded. A window page becomes one record keyed by the time window. An id page becomes one record per id: the DLQ drain reads those same ids back, and {@code DlqDrainService.cleanup()} deletes any entry whose {@code failureCount} did not rise during the run. Staying silent on an id page would therefore let cleanup read "no new failure" as "recovered" and delete records for nodes that were never reindexed.
 *
 * <p>
 * Not registered when the DLQ config is disabled — with no listener bean the reindexing reader rethrows the page exception.
 */
public class DlqPageReadFailureListener implements PageReadFailureListener
{
    private static final Logger LOGGER = LoggerFactory.getLogger(DlqPageReadFailureListener.class);

    private final DlqWriter dlqWriter;

    public DlqPageReadFailureListener(DlqWriter dlqWriter)
    {
        this.dlqWriter = dlqWriter;
    }

    @Override
    public void onPageReadFailure(String readerName, NodeParams params, Throwable cause)
    {
        params.getTimestampRange().ifPresent(
                range -> dlqWriter.recordWindowFailure(range.getBegin(), range.getEnd(), cause));

        // One record per id so the drain's cleanup sees the failureCount rise on the very
        // entries it is about to evaluate.
        params.getIds().ifPresent(
                ids -> ids.forEach(id -> dlqWriter.recordSkip(id, null, null, FailureStage.READER, cause)));

        if (params.getTimestampRange().isEmpty() && params.getIds().isEmpty())
        {
            LOGGER.warn("[{}] Page read failure with neither a timestamp range nor ids; no DLQ record written. cause={}",
                    readerName, cause == null ? "<null>" : cause.getMessage());
        }
    }
}
