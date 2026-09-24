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
package org.alfresco.elasticsearch.batchindexing.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.job.AbstractJob;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;

import org.alfresco.elasticsearch.batchindexing.listeners.CacheInvalidationListener;

/**
 * Attaches the {@link CacheInvalidationListener} to the {@code reindexByDateJob} bean (defined in the reindexing module) without overriding it.
 */
@Component
public class JobListenerInjector implements BeanPostProcessor
{
    private static final String TARGET_JOB_BEAN_NAME = "reindexByDateJob";
    private static final Logger LOGGER = LoggerFactory.getLogger(JobListenerInjector.class);

    private final CacheInvalidationListener cacheInvalidationListener;

    public JobListenerInjector(CacheInvalidationListener cacheInvalidationListener)
    {
        this.cacheInvalidationListener = cacheInvalidationListener;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName)
    {
        if (TARGET_JOB_BEAN_NAME.equals(beanName) && bean instanceof AbstractJob job)
        {
            job.registerJobExecutionListener(cacheInvalidationListener);
            LOGGER.info("Registered CacheInvalidationListener on job bean '{}'", beanName);
        }
        return bean;
    }
}
