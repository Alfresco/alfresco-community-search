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

import static org.assertj.core.api.Assertions.assertThat;

import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.CM_CONTENT_TR_STATUS;
import static org.alfresco.indexing.shared.TransformStatus.NO_TRANSFORM;
import static org.alfresco.indexing.shared.TransformStatus.TRANSFORM_OUTDATED;

import org.junit.jupiter.api.Test;

import org.alfresco.indexing.shared.EventGenerator;
import org.alfresco.repo.event.v1.model.EventData;
import org.alfresco.repo.event.v1.model.EventType;
import org.alfresco.repo.event.v1.model.NodeResource;
import org.alfresco.repo.event.v1.model.RepoEvent;

class TransformStatusExtenderTest
{
    private final TransformStatusExtender extender = new TransformStatusExtender();

    @Test
    void shouldSetNoTransformForNodeCreated()
    {
        var event = event(EventType.NODE_CREATED, null);

        assertThat(trStatusOf(extender.extendWithTrStatus(event))).isEqualTo(NO_TRANSFORM.name());
    }

    @Test
    void shouldSetTransformOutdatedForNodeUpdated()
    {
        var event = event(EventType.NODE_UPDATED, null);

        assertThat(trStatusOf(extender.extendWithTrStatus(event))).isEqualTo(TRANSFORM_OUTDATED.name());
    }

    @Test
    void shouldSetTransformOutdatedForArchiveDelete()
    {
        var event = event(EventType.NODE_DELETED, Boolean.FALSE);

        assertThat(trStatusOf(extender.extendWithTrStatus(event))).isEqualTo(TRANSFORM_OUTDATED.name());
    }

    @Test
    void shouldLeavePurgeDeleteUntouched()
    {
        var event = event(EventType.NODE_DELETED, Boolean.TRUE);

        assertThat(trStatusOf(extender.extendWithTrStatus(event))).isNull();
    }

    @Test
    void shouldLeaveLegacyDeleteUntouched()
    {
        var event = event(EventType.NODE_DELETED, null);

        assertThat(trStatusOf(extender.extendWithTrStatus(event))).isNull();
    }

    private RepoEvent<EventData<NodeResource>> event(EventType type, Boolean isPermanentlyDeleted)
    {
        return EventGenerator.builder()
                .withType(type)
                .withContentSet(true)
                .withMetadataSet(true)
                .withPermanentlyDeleted(isPermanentlyDeleted)
                .generateEvent();
    }

    private Object trStatusOf(RepoEvent<EventData<NodeResource>> event)
    {
        return event.getData().getResource().getProperties().get(CM_CONTENT_TR_STATUS);
    }
}
