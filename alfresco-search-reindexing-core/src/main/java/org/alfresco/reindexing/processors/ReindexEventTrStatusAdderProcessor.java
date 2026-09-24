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

import lombok.RequiredArgsConstructor;
import org.springframework.batch.infrastructure.item.ItemProcessor;

import org.alfresco.indexing.shared.transform.TransformStatusExtender;
import org.alfresco.reindexing.domain.transform.ContentTransformSupportService;
import org.alfresco.reindexing.model.ReindexingEvent;

/**
 * The processor that adds the transformation status to the reindexing event.
 */
@RequiredArgsConstructor
public class ReindexEventTrStatusAdderProcessor implements ItemProcessor<ReindexingEvent, ReindexingEvent>
{
    private final ContentTransformSupportService contentTransformSupportService;
    private final TransformStatusExtender trStatusExtender;

    @Override
    public ReindexingEvent process(ReindexingEvent reindexingEvent)
    {
        return of(reindexingEvent)
                .filter(contentTransformSupportService::isContentTransformSupported)
                .map(ReindexingEvent::getRepoEvent)
                .map(trStatusExtender::extendWithTrStatus)
                .map(reindexingEvent::withRepoEvent)
                .orElse(reindexingEvent);
    }
}
