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

import org.springframework.batch.infrastructure.item.ItemProcessor;

import org.alfresco.indexing.shared.config.FilteringConfig;
import org.alfresco.reindexing.model.ReindexingEvent;
import org.alfresco.repo.event.v1.model.DataAttributes;
import org.alfresco.repo.event.v1.model.NodeResource;
import org.alfresco.repo.event.v1.model.RepoEvent;

/**
 * {@link ItemProcessor} that filters out {@link RepoEvent}s if the type is present inside the given {@link FilteringConfig}
 */
public class ReindexEventNodeTypeFilterProcessor implements ItemProcessor<ReindexingEvent, ReindexingEvent>
{

    private final FilteringConfig filteringConfig;

    public ReindexEventNodeTypeFilterProcessor(FilteringConfig filteringConfig)
    {
        this.filteringConfig = filteringConfig;
    }

    @Override
    public ReindexingEvent process(ReindexingEvent repoEvent) throws Exception
    {
        return of(repoEvent)
                .filter(this::hasAllowedType)
                .orElse(null);
    }

    private boolean hasAllowedType(ReindexingEvent repoEvent)
    {
        return of(repoEvent)
                .map(ReindexingEvent::getData)
                .map(DataAttributes::getResource)
                .map(NodeResource::getNodeType)
                .filter(filteringConfig::doesntContainNodeType)
                .isPresent();
    }

}
