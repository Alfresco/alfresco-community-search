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
package org.alfresco.elasticsearch.batchindexing.listeners;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;

import org.alfresco.database.connector.AlfrescoMetadataRepository;
import org.alfresco.database.connector.MetadataRepositoryCachingDecorator;

@ExtendWith(MockitoExtension.class)
@DisplayName("CacheInvalidationListener")
class CacheInvalidationListenerTest
{
    @Mock
    private MetadataRepositoryCachingDecorator cachingDecorator;

    @Mock
    private AlfrescoMetadataRepository plainRepository;

    private JobExecution dummyJobExecution()
    {
        JobInstance instance = new JobInstance(1L, "reindexByDate");
        return new JobExecution(1L, instance, new JobParameters());
    }

    @Test
    @DisplayName("Invalidates both caches before job")
    void invalidatesBothCaches()
    {
        CacheInvalidationListener listener = new CacheInvalidationListener(cachingDecorator);

        listener.beforeJob(dummyJobExecution());

        verify(cachingDecorator).invalidateTagsCache();
        verify(cachingDecorator).invalidateChildAssocCache();
    }

    @Test
    @DisplayName("beforeJob invalidates tag cache before child-assoc cache")
    void invalidatesTagCacheFirst()
    {
        CacheInvalidationListener listener = new CacheInvalidationListener(cachingDecorator);

        var inOrder = inOrder(cachingDecorator);
        listener.beforeJob(dummyJobExecution());

        inOrder.verify(cachingDecorator).invalidateTagsCache();
        inOrder.verify(cachingDecorator).invalidateChildAssocCache();
    }

    @Test
    @DisplayName("skips invalidation when repository is not MetadataRepositoryCachingDecorator")
    void skipsInvalidationWhenNotCachingDecorator()
    {
        CacheInvalidationListener listener = new CacheInvalidationListener(plainRepository);

        listener.beforeJob(dummyJobExecution());

        verifyNoInteractions(plainRepository);
    }

    @Test
    @DisplayName("afterJob does not interact with the repository")
    void afterJobNoOp()
    {
        CacheInvalidationListener listener = new CacheInvalidationListener(cachingDecorator);

        listener.afterJob(dummyJobExecution());

        verifyNoInteractions(cachingDecorator);
    }

}
