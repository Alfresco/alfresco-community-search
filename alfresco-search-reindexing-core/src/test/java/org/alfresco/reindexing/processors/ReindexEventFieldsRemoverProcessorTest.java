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

import java.io.Serializable;
import java.util.List;
import java.util.Map;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import org.alfresco.indexing.shared.config.FilteringConfig;
import org.alfresco.reindexing.model.ReindexingEvent;
import org.alfresco.repo.event.v1.model.DataAttributes;
import org.alfresco.repo.event.v1.model.EventData;
import org.alfresco.repo.event.v1.model.EventType;
import org.alfresco.repo.event.v1.model.NodeResource;
import org.alfresco.repo.event.v1.model.RepoEvent;

class ReindexEventFieldsRemoverProcessorTest
{

    @Test
    void should_removeGivenPropertiesListFromEvent() throws Exception
    {

        FilteringConfig config = new FilteringConfig();
        config.setFields(List.of("toBeRemoved1", "toBeRemoved2"));
        ReindexEventFieldsRemoverProcessor processor = new ReindexEventFieldsRemoverProcessor(config);

        Map<String, Serializable> inputProperties = new java.util.HashMap<>();
        inputProperties.put("cm:description", "this is a description");
        inputProperties.put("toBeRemoved1", "value");
        inputProperties.put("toBeRemoved2", "value");
        inputProperties.put("keepThis", "value");
        ReindexingEvent reindexingEvent = event(NodeResource.builder().setProperties(inputProperties).build());

        ReindexingEvent processed = processor.process(reindexingEvent);

        Map<String, Serializable> properties = of(processed)
                .map(ReindexingEvent::getData)
                .map(DataAttributes::getResource)
                .map(NodeResource::getProperties)
                .get();

        Assertions.assertThat(properties).hasSize(2)
                .containsOnlyKeys("keepThis", "cm:description");
    }

    private static ReindexingEvent event(NodeResource resource)
    {
        return ReindexingEvent.builder()
                .withRepoEvent(RepoEvent.<EventData<NodeResource>> builder()
                        .setType(EventType.NODE_CREATED.getType())
                        .setData(EventData.<NodeResource> builder().setResource(resource).build())
                        .build())
                .build();
    }
}
