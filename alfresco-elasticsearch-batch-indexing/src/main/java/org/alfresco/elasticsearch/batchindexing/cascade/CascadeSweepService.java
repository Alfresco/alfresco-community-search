/*-
 * #%L
 * Alfresco Search Community :: Batch Indexing
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
package org.alfresco.elasticsearch.batchindexing.cascade;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.alfresco.elasticsearch.batchindexing.observability.ReindexCycleListener;

/**
 * Post-job orchestrator that drains the {@link CascadeSourceStash} once a {@code reindexByDate} job finishes successfully and dispatches each observed cascade source to the appropriate cascade service.
 * <p>
 * Subscribes to {@link ReindexCycleListener}: {@link #onJobStart()} discards observations from a prior aborted or successful job, and {@link #onJobSuccess()} drains and applies the stash. Sources observed during aborted / failed jobs are intentionally left in the stash so an operator can inspect them.
 * <p>
 * Runs on the continuous-reindex thread. Each source is applied independently and failures are logged, not propagated: one bad cascade must not block the rest of the batch.
 */
public class CascadeSweepService implements ReindexCycleListener
{
    private static final Logger LOGGER = LoggerFactory.getLogger(CascadeSweepService.class);

    private final CascadeSourceStash stash;

    private final RenameCascadeService renameCascadeService;

    private final CategoryDeleteCascadeService categoryDeleteCascadeService;

    public CascadeSweepService(CascadeSourceStash stash,
            RenameCascadeService renameCascadeService,
            CategoryDeleteCascadeService categoryDeleteCascadeService)
    {
        this.stash = stash;
        this.renameCascadeService = renameCascadeService;
        this.categoryDeleteCascadeService = categoryDeleteCascadeService;
    }

    /**
     * Called immediately before a reindex job begins so that stale observations from a prior aborted or successful job do not leak into the new one.
     */
    @Override
    public void onJobStart()
    {
        if (stash != null)
        {
            stash.clear();
        }
    }

    /**
     * Called only after a reindex job completes with {@code BatchStatus.COMPLETED}. Drains the stash and dispatches each cascade source to its service.
     */
    @Override
    public void onJobSuccess()
    {
        if (stash == null)
        {
            return;
        }
        CascadeSourceBatch batch = stash.drain();
        if (batch.isEmpty())
        {
            LOGGER.debug("Cascade sweep: no cascade sources observed this cycle");
            return;
        }

        LOGGER.info("Cascade sweep: applying {} rename cascade(s), {} category-delete cascade(s)",
                batch.renames().size(), batch.categoryDeletes().size());

        long renameUpdated = 0L;
        for (RenameCascadeSource source : batch.renames())
        {
            try
            {
                renameUpdated += renameCascadeService.apply(source);
            }
            catch (RuntimeException ex)
            {
                LOGGER.warn("Cascade sweep: unexpected error applying rename for nodeRef={}: {}",
                        source.nodeRef(), ex.getMessage(), ex);
            }
        }

        long deleteUpdated = 0L;
        for (CategoryDeleteCascadeSource source : batch.categoryDeletes())
        {
            try
            {
                deleteUpdated += categoryDeleteCascadeService.apply(source);
            }
            catch (RuntimeException ex)
            {
                LOGGER.warn("Cascade sweep: unexpected error applying category-delete for nodeRef={}: {}",
                        source.nodeRef(), ex.getMessage(), ex);
            }
        }

        LOGGER.info("Cascade sweep completed: rename cascades updated {} descendant doc(s); "
                + "category-delete cascades updated {} referring doc(s)",
                renameUpdated, deleteUpdated);
    }
}
