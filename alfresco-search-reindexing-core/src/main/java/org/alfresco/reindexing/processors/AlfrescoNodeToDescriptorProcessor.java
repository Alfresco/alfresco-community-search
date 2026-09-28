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

import java.io.Serializable;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.infrastructure.item.ItemProcessor;

import org.alfresco.database.connector.model.AlfrescoNode;
import org.alfresco.indexing.shared.PropertiesDeterminingStrategy;
import org.alfresco.reindexing.beans.RepositoryDescriptor;
import org.alfresco.reindexing.config.PrefixNamespaceConfig;

/**
 * Transform an Alfresco Node into a Repository Descriptor
 */
public class AlfrescoNodeToDescriptorProcessor extends AlfrescoNodeProcessor implements ItemProcessor<AlfrescoNode, RepositoryDescriptor>
{

    private static final Logger LOGGER = LoggerFactory.getLogger(AlfrescoNodeToDescriptorProcessor.class);

    private final PropertiesDeterminingStrategy propertiesDeterminingStrategy;

    public AlfrescoNodeToDescriptorProcessor(PrefixNamespaceConfig prefixNamespaceConfig, PropertiesDeterminingStrategy propertiesDeterminingStrategy)
    {
        super(prefixNamespaceConfig);
        this.propertiesDeterminingStrategy = propertiesDeterminingStrategy;
    }

    @Override
    public RepositoryDescriptor process(final AlfrescoNode node)
    {
        try
        {
            Map<String, Serializable> determinedProperties = propertiesDeterminingStrategy.determineProperties(getProperties(node), getLocalizedProperties(node));
            // Add also "sys:node-uuid" value to identify unambiguously the repository
            determinedProperties.put(RepositoryDescriptor.ID_PROPERTY_NAME, node.getNodeRef());
            return new RepositoryDescriptor(determinedProperties);
        }
        catch (RuntimeException exception)
        {
            LOGGER.error("Impossible to transform node " + node.getId() + " to Repository Descriptor. Error: " + exception.getMessage(),
                    exception);
            throw exception;
        }
    }

}
