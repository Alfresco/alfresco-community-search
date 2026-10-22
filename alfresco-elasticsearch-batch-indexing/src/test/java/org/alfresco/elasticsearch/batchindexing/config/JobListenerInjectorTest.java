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
package org.alfresco.elasticsearch.batchindexing.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.job.AbstractJob;

import org.alfresco.elasticsearch.batchindexing.listeners.CacheInvalidationListener;

@ExtendWith(MockitoExtension.class)
@DisplayName("JobListenerInjector")
class JobListenerInjectorTest
{
    @Mock
    private CacheInvalidationListener cacheInvalidationListener;

    @Mock
    private AbstractJob abstractJob;

    private JobListenerInjector injector;

    @BeforeEach
    void setUp()
    {
        injector = new JobListenerInjector(cacheInvalidationListener);
    }

    @Test
    @DisplayName("registers listener on reindexByDateJob")
    void registersListenerOnTargetJob()
    {
        injector.postProcessAfterInitialization(abstractJob, "reindexByDateJob");

        verify(abstractJob).registerJobExecutionListener(cacheInvalidationListener);
    }

    @Test
    @DisplayName("returns same bean instance")
    void returnsSameBeanInstance()
    {
        Object result = injector.postProcessAfterInitialization(abstractJob, "reindexByDateJob");

        assertThat(result).isSameAs(abstractJob);
    }

    @Test
    @DisplayName("ignores beans with different name")
    void ignoresDifferentBeanName()
    {
        injector.postProcessAfterInitialization(abstractJob, "someOtherJob");

        verify(abstractJob, never()).registerJobExecutionListener(any());
    }

    @Test
    @DisplayName("no-op when bean is not AbstractJob")
    void noOpForNonAbstractJob()
    {
        Object nonJobBean = new Object();

        Object result = injector.postProcessAfterInitialization(nonJobBean, "reindexByDateJob");

        assertThat(result).isSameAs(nonJobBean);
        verifyNoInteractions(abstractJob);
    }

    @Test
    @DisplayName("no-op for unrelated beans")
    void noOpForUnrelatedBean()
    {
        Object someService = new Object();

        Object result = injector.postProcessAfterInitialization(someService, "someService");

        assertThat(result).isSameAs(someService);
    }
}
