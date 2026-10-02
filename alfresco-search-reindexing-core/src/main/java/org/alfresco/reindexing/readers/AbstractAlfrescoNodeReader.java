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
package org.alfresco.reindexing.readers;

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.infrastructure.item.database.AbstractPagingItemReader;

import org.alfresco.database.connector.AlfrescoMetadataRepository;
import org.alfresco.database.connector.NodeParams;
import org.alfresco.database.connector.model.AlfrescoNode;

@Slf4j
public abstract class AbstractAlfrescoNodeReader extends AbstractPagingItemReader<AlfrescoNode>
{

    private final AlfrescoMetadataRepository metadataRepository;
    private final PageReadFailureListener pageReadFailureListener;

    private boolean readerExhausted;

    /** Index into the current page, advanced by rows served rather than by page size. */
    private int currentResult;

    protected AbstractAlfrescoNodeReader(AlfrescoMetadataRepository metadataRepository)
    {
        this(metadataRepository, null);
    }

    protected AbstractAlfrescoNodeReader(AlfrescoMetadataRepository metadataRepository, PageReadFailureListener pageReadFailureListener)
    {
        this.metadataRepository = requireNonNull(metadataRepository);
        this.pageReadFailureListener = pageReadFailureListener;
    }

    @Override
    protected void doReadPage()
    {
        List<AlfrescoNode> resultList = List.of();

        if (needMoreNodes() && !readerExhausted)
        {
            NodeParams params = getParams();
            try
            {
                resultList = metadataRepository.getAlfrescoNodes(params);
            }
            catch (RuntimeException e)
            {
                if (pageReadFailureListener == null)
                {
                    // Historical behaviour: no listener registered (enterprise reindexing
                    // one-shot CLI). Fail loudly so the operator sees it.
                    throw e;
                }
                // Batch-indexing: hand the failure to the listener and stop. The by-date
                // reader's params carry the whole window, so the listener records that
                // window in the DLQ and the next cycle moves on. Readers with a finite
                // cursor skip the page and carry on — see pagesUntilCursorExhausted().
                log.error("[{}] Page read failed for params={}; {}.", getName(), params,
                        pagesUntilCursorExhausted() ? "skipping this page" : "stopping this reader", e);
                pageReadFailureListener.onPageReadFailure(getName(), params, e);
                if (!pagesUntilCursorExhausted())
                {
                    readerExhausted = true;
                }
            }
        }

        if (results == null)
        {
            results = new CopyOnWriteArrayList<>();
        }
        else
        {
            results.clear();
        }

        results.addAll(resultList);
    }

    @Override
    protected void doOpen() throws Exception
    {
        super.doOpen();
        currentResult = 0;
    }

    /**
     * Advances by rows returned rather than by page size, because a page of explicit ids comes back short when some of those ids have been deleted. Range readers keep Spring's behaviour, where a short page really is the end of the data.
     */
    @Override
    protected synchronized AlfrescoNode doRead() throws Exception
    {
        if (!pagesUntilCursorExhausted())
        {
            return super.doRead();
        }

        List<AlfrescoNode> currentResults = results;
        while (currentResults == null || currentResult >= currentResults.size())
        {
            if (!needMoreNodes() || readerExhausted)
            {
                return null;
            }
            doReadPage();
            currentResult = 0;
            currentResults = results;
        }
        return currentResults.get(currentResult++);
    }

    protected abstract NodeParams getParams();

    protected abstract boolean needMoreNodes();

    protected final boolean isReaderExhausted()
    {
        return readerExhausted;
    }

    /**
     * Whether {@link #needMoreNodes()} alone decides when this reader is done, so a page that fails or comes back empty is skipped rather than taken as end of data. Only safe when {@link #getParams()} advances its own cursor; a reader paging off {@code getPage()} would re-read the same page forever.
     */
    protected boolean pagesUntilCursorExhausted()
    {
        return false;
    }
}
