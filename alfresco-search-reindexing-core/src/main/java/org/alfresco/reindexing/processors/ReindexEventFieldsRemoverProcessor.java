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
package org.alfresco.reindexing.processors;

import static java.util.Optional.of;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.infrastructure.item.ItemProcessor;

import org.alfresco.indexing.shared.config.FilteringConfig;
import org.alfresco.indexing.shared.utils.RepoEventCloneUtils;
import org.alfresco.reindexing.model.ReindexingEvent;
import org.alfresco.repo.event.v1.model.RepoEvent;

/**
 * This {@link ItemProcessor} removes unwanted fields from the give {@link RepoEvent} according to filtering configuration
 */
public class ReindexEventFieldsRemoverProcessor implements ItemProcessor<ReindexingEvent, ReindexingEvent>
{

    private static final Logger LOGGER = LoggerFactory.getLogger(ReindexEventFieldsRemoverProcessor.class);

    private final List<String> fieldsToBeRemoved;

    public ReindexEventFieldsRemoverProcessor(FilteringConfig filteringConfig)
    {

        fieldsToBeRemoved = filteringConfig.getFields();
        LOGGER.info("These fields will be removed:: {} ", fieldsToBeRemoved);
    }

    @Override
    public ReindexingEvent process(ReindexingEvent reindexingEvent) throws Exception
    {
        return of(reindexingEvent)
                .map(ReindexingEvent::getRepoEvent)
                .map(repoEvent -> RepoEventCloneUtils.withFilteredProps(repoEvent, fieldsToBeRemoved))
                .map(reindexingEvent::withRepoEvent)
                .orElse(reindexingEvent);
    }

}
