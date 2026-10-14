/*-
 * #%L
 * Alfresco Search Community :: OpenSearch Client
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
package org.alfresco.indexing.shared.transform;

import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.CM_CONTENT_TR_STATUS;
import static org.alfresco.indexing.shared.TransformStatus.NO_TRANSFORM;
import static org.alfresco.indexing.shared.TransformStatus.TRANSFORM_OUTDATED;
import static org.alfresco.indexing.shared.utils.DeletionTypeChecker.isArchiveDelete;

import java.io.Serializable;
import java.util.Map;

import org.alfresco.indexing.shared.utils.RepoEventCloneUtils;
import org.alfresco.repo.event.v1.model.EventData;
import org.alfresco.repo.event.v1.model.EventType;
import org.alfresco.repo.event.v1.model.NodeResource;
import org.alfresco.repo.event.v1.model.RepoEvent;

/**
 * The Domain Service containing business logic related to extending {@link RepoEvent} with correct transformation status.
 */
public class TransformStatusExtender
{
    private static final Map<String, Serializable> NO_TRANSFORM_ENTRY = Map.of(CM_CONTENT_TR_STATUS, NO_TRANSFORM.name());
    private static final Map<String, Serializable> TRANSFORM_OUTDATED_ENTRY = Map.of(CM_CONTENT_TR_STATUS, TRANSFORM_OUTDATED.name());

    /**
     * Extends the given {@link RepoEvent} with optional {@link NodeResource} property of transformation status. The decision is based on the event type.
     */
    public RepoEvent<EventData<NodeResource>> extendWithTrStatus(RepoEvent<EventData<NodeResource>> repoEvent)
    {
        return EventType.findByType(repoEvent.getType())
                .map(type -> handleStatusForType(type, repoEvent))
                .orElse(repoEvent);
    }

    private RepoEvent<EventData<NodeResource>> handleStatusForType(EventType eventType, RepoEvent<EventData<NodeResource>> repoEvent)
    {
        return switch (eventType)
        {
        case NODE_CREATED -> RepoEventCloneUtils.withAdditionalProps(repoEvent, NO_TRANSFORM_ENTRY);
        case NODE_UPDATED -> RepoEventCloneUtils.withAdditionalProps(repoEvent, TRANSFORM_OUTDATED_ENTRY);
        case NODE_DELETED -> isArchiveDelete(repoEvent)
                ? RepoEventCloneUtils.withAdditionalProps(repoEvent, TRANSFORM_OUTDATED_ENTRY)
                : repoEvent;
        default -> repoEvent;
        };
    }
}
