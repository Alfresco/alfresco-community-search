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
package org.alfresco.indexing.shared.model;

import static org.assertj.core.api.Assertions.assertThat;

import static org.alfresco.repo.event.v1.model.EventType.NODE_CREATED;

import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import org.alfresco.indexing.shared.utils.RepoEventCloneUtils;
import org.alfresco.repo.event.v1.model.EventData;
import org.alfresco.repo.event.v1.model.NodeResource;
import org.alfresco.repo.event.v1.model.RepoEvent;

class RepoEventCloneUtilsTest
{

    @Test
    void shouldAddProperties()
    {
        // given
        RepoEvent<EventData<NodeResource>> srcEvent = repoEventWithProperties(Map.of("existingProp", "existingValue"));
        Map<String, Serializable> additionalProps = Map.of("newProp1", "newProp1Value", "newProp2", "newProp2Value");

        // when
        var updatedEvent = RepoEventCloneUtils.withAdditionalProps(srcEvent, additionalProps);

        // then
        assertThat(updatedEvent)
                .usingRecursiveComparison()
                .ignoringFields("data.resource.properties")
                .isEqualTo(srcEvent);
        assertThat(updatedEvent.getData().getResource().getProperties())
                .isNotSameAs(srcEvent.getData().getResource().getProperties())
                .containsAllEntriesOf(additionalProps)
                .containsAllEntriesOf(srcEvent.getData().getResource().getProperties());
    }

    @Test
    void shouldIgnoreUnexistedProperties()
    {
        // given
        RepoEvent<EventData<NodeResource>> srcEvent = repoEventWithProperties(Map.of("existingProp", "existingValue"));
        var propsToRemove = Set.of("alreadyUnexistedProp");

        // when
        var updatedEvent = RepoEventCloneUtils.withFilteredProps(srcEvent, propsToRemove);

        // then
        assertThat(updatedEvent)
                .usingRecursiveComparison()
                .isEqualTo(srcEvent);
    }

    @Test
    void shouldRemoveProperties()
    {
        // given
        RepoEvent<EventData<NodeResource>> srcEvent = repoEventWithProperties(Map.of("existingProp", "existingValue"));
        var propsToRemove = srcEvent.getData().getResource().getProperties().keySet();

        // when
        var updatedEvent = RepoEventCloneUtils.withFilteredProps(srcEvent, propsToRemove);

        // then
        assertThat(updatedEvent)
                .usingRecursiveComparison()
                .ignoringFields("data.resource.properties")
                .isEqualTo(srcEvent);
        assertThat(updatedEvent.getData().getResource().getProperties())
                .isNotSameAs(srcEvent.getData().getResource().getProperties())
                .isEmpty();
    }

    @Test
    void shouldIgnoreWhenPropertiesDoesNotExist()
    {
        // given
        RepoEvent<EventData<NodeResource>> srcEvent = repoEventWithProperties(null);
        Map<String, Serializable> additionalProps = Map.of("newProp1", "newProp1Value", "newProp2", "newProp2Value");

        // when
        var updatedEvent = RepoEventCloneUtils.withAdditionalProps(srcEvent, additionalProps);

        // then
        assertThat(updatedEvent)
                .isSameAs(srcEvent)
                .extracting(event -> event.getData().getResource().getProperties())
                .isNull();
    }

    private RepoEvent<EventData<NodeResource>> repoEventWithProperties(Map<String, Serializable> properties)
    {
        NodeResource resource = NodeResource.builder()
                .setProperties(properties)
                .setSecondaryParents(List.of())
                .build();
        EventData<NodeResource> data = EventData.<NodeResource> builder()
                .setResource(resource)
                .build();
        return RepoEvent.<EventData<NodeResource>> builder()
                .setType(NODE_CREATED.getType())
                .setData(data)
                .build();
    }
}
