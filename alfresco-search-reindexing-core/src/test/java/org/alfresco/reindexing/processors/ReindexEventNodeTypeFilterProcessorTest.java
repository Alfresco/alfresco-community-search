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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import org.alfresco.indexing.shared.config.FilteringConfig;
import org.alfresco.reindexing.model.ReindexingEvent;
import org.alfresco.repo.event.v1.model.EventData;
import org.alfresco.repo.event.v1.model.EventType;
import org.alfresco.repo.event.v1.model.NodeResource;
import org.alfresco.repo.event.v1.model.RepoEvent;

class ReindexEventNodeTypeFilterProcessorTest
{

    @Test
    void should_filterNotAllowedTypes() throws Exception
    {
        FilteringConfig config = new FilteringConfig();
        config.setNodeTypes(List.of("notAllowed"));
        ReindexEventNodeTypeFilterProcessor processor = new ReindexEventNodeTypeFilterProcessor(config);

        // given
        ReindexingEvent reindexingEvent = eventWithNodeType("notAllowed");

        // when
        ReindexingEvent processed = processor.process(reindexingEvent);

        // then
        assertThat(processed).isNull();
    }

    @Test
    void should_notFilterAllowedTypes() throws Exception
    {
        FilteringConfig config = new FilteringConfig();
        config.setNodeTypes(List.of("notAllowed"));
        ReindexEventNodeTypeFilterProcessor processor = new ReindexEventNodeTypeFilterProcessor(config);

        // given
        ReindexingEvent reindexingEvent = eventWithNodeType("allowed");

        // when
        ReindexingEvent processed = processor.process(reindexingEvent);

        // then
        assertThat(processed).isNotNull();
    }

    private static ReindexingEvent eventWithNodeType(String nodeType)
    {
        NodeResource resource = NodeResource.builder().setNodeType(nodeType).build();
        return ReindexingEvent.builder()
                .withRepoEvent(RepoEvent.<EventData<NodeResource>> builder()
                        .setType(EventType.NODE_CREATED.getType())
                        .setData(EventData.<NodeResource> builder().setResource(resource).build())
                        .build())
                .build();
    }
}
