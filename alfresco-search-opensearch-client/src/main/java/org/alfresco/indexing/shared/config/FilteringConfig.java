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
package org.alfresco.indexing.shared.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;

import org.alfresco.indexing.shared.utils.YamlPropertySourceFactory;

@Configuration
@ConfigurationProperties(prefix = "mediation")
@PropertySource(value = "${alfresco.mediation.filter-file}", factory = YamlPropertySourceFactory.class)
public class FilteringConfig
{

    /**
     * This blacklist is applied on the node type regardless the associated event (e.g. content and/or metadata).
     */
    private List<String> nodeTypes;

    /**
     * Node types associated with node whose content has been changed. The blacklist is applied only to nodes (i.e. to their node type) whose event reports a change in the content.
     */
    private List<String> contentNodeTypes;

    /**
     * This blacklist is applied on the node aspect regardless the associated event (e.g. content and/or metadata).
     */
    private List<String> nodeAspects;

    private List<String> fields;

    public boolean containsNodeType(String nodeType)
    {
        return nodeTypes.contains(nodeType);
    }

    public boolean doesntContainNodeType(String nodeType)
    {
        return !containsNodeType(nodeType);
    }

    public boolean containsContentNodeType(String nodeType)
    {
        return contentNodeTypes.contains(nodeType);
    }

    public boolean doesntContainContentNodeType(String nodeType)
    {
        return !containsContentNodeType(nodeType);
    }

    public boolean containsNodeAspect(String nodeAspect)
    {
        return nodeAspects.contains(nodeAspect);
    }

    public void setNodeTypes(List<String> nodeTypes)
    {
        this.nodeTypes = nodeTypes;
    }

    public void setContentNodeTypes(List<String> contentNodeTypes)
    {
        this.contentNodeTypes = contentNodeTypes;
    }

    public List<String> getFields()
    {
        return fields;
    }

    public void setFields(List<String> fields)
    {
        this.fields = fields;
    }

    public List<String> getNodeAspects()
    {
        return nodeAspects;
    }

    public void setNodeAspects(List<String> nodeAspects)
    {
        this.nodeAspects = nodeAspects;
    }
}
