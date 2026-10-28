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
package org.alfresco.reindexing.model;

import java.net.URI;
import java.time.ZonedDateTime;
import java.util.Set;

import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.With;

import org.alfresco.database.connector.model.ChildAssocMetaData;
import org.alfresco.database.connector.model.Path;
import org.alfresco.repo.event.EventAttributes;
import org.alfresco.repo.event.v1.model.EventData;
import org.alfresco.repo.event.v1.model.NodeResource;
import org.alfresco.repo.event.v1.model.RepoEvent;

/**
 * The Value Object containing all information required to perform a node reindex. It delegates the RepoEvent class adding additional information required during the reindex. This is an EventAttributes implementation to use existing live indexing code.
 */
@With
@Getter
@Builder(setterPrefix = "with")
@RequiredArgsConstructor
public class ReindexingEvent implements EventAttributes
{

    private final RepoEvent<EventData<NodeResource>> repoEvent;
    private final ChildAssocMetaData primaryParent;
    private final Set<ChildAssocMetaData> secondaryParents;
    private final Path primaryPath;
    private final Set<Path> allPaths;
    private final Long nodeDbId;
    private final ArchivedOriginalParentAssoc archivedOriginalParentAssoc;

    @Override
    public String getSpecversion()
    {
        return repoEvent.getSpecversion();
    }

    @Override
    public String getType()
    {
        return repoEvent.getType();
    }

    @Override
    public String getId()
    {
        return repoEvent.getId();
    }

    @Override
    public URI getSource()
    {
        return repoEvent.getSource();
    }

    @Override
    public ZonedDateTime getTime()
    {
        return repoEvent.getTime();
    }

    @Override
    public URI getDataschema()
    {
        return repoEvent.getDataschema();
    }

    @Override
    public String getDatacontenttype()
    {
        return repoEvent.getDatacontenttype();
    }

    public EventData<NodeResource> getData()
    {
        return repoEvent.getData();
    }
}
