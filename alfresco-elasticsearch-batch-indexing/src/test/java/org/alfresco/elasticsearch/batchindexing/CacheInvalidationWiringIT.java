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
package org.alfresco.elasticsearch.batchindexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.AbstractJob;
import org.springframework.batch.core.job.Job;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import org.alfresco.elasticsearch.batchindexing.listeners.CacheInvalidationListener;

/**
 * Verifies that {@link org.alfresco.elasticsearch.batchindexing.config.JobListenerInjector} correctly attaches {@link CacheInvalidationListener} to the {@code reindexByDateJob} bean in the full batch-indexing Spring context, and that it fires before every job execution.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("CacheInvalidationListener wiring IT")
public class CacheInvalidationWiringIT extends BatchIndexingAbstractIT
{
    @Autowired
    @Qualifier("reindexByDateJob") private Job reindexByDateJob;

    @MockitoSpyBean
    private CacheInvalidationListener cacheInvalidationListener;

    @Test
    @DisplayName("reindexByDateJob is an AbstractJob")
    void jobIsAbstractJob()
    {
        assertThat(reindexByDateJob).isInstanceOf(AbstractJob.class);
    }

    @Test
    @DisplayName("CacheInvalidationListener bean is present in the context")
    void listenerBeanExists()
    {
        assertThat(cacheInvalidationListener).isNotNull();
    }

    @Test
    @DisplayName("CacheInvalidationListener fires before job execution")
    void listenerFiresBeforeJob()
    {
        continuousReindexingService.runCycleSafe();

        verify(cacheInvalidationListener, atLeastOnce()).beforeJob(any());
    }
}
