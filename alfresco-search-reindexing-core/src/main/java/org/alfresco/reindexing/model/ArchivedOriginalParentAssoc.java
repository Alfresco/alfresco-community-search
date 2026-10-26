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

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.alfresco.indexing.shared.utils.NodeRefUtils;

/**
 * The original parent association of a node that was deleted to the trashcan, as archived in {@code sys:archivedOriginalParentAssoc}.
 * <p>
 * The repository stores the property ({@code d:childassocref}) as the string form of {@code ChildAssociationRef}:
 *
 * <pre>
 * parentNodeRef | childNodeRef | assocTypeQName | assocQName | isPrimary | nthSibling
 * </pre>
 *
 * e.g. {@code workspace://SpacesStore/<parent>|workspace://SpacesStore/<child>|{http://www.alfresco.org/model/content/1.0}contains|{http://www.alfresco.org/model/content/1.0}doc.txt|true|-1}
 *
 * @param parentUuid
 *            UUID of the original parent node
 * @param assocNamespaceUri
 *            namespace URI of the original association QName
 * @param assocLocalName
 *            local name of the original association QName
 */
public record ArchivedOriginalParentAssoc(
        String parentUuid,
        String assocNamespaceUri,
        String assocLocalName)
{

    private static final Logger LOGGER = LoggerFactory.getLogger(ArchivedOriginalParentAssoc.class);

    private static final String FILLER = "\\|";
    private static final int TOKEN_COUNT = 6;

    /**
     * Parses the serialized value of {@code sys:archivedOriginalParentAssoc}.
     *
     * @param value
     *            the raw property value.
     * @return the parsed value, or empty when it cannot be parsed
     */
    public static Optional<ArchivedOriginalParentAssoc> parse(Object value)
    {
        if (!(value instanceof String stringValue) || stringValue.isBlank())
        {
            LOGGER.trace("Cannot parse sys:archivedOriginalParentAssoc: expected a non-blank string");
            return Optional.empty();
        }

        String[] tokens = stringValue.split(FILLER, -1);
        if (tokens.length != TOKEN_COUNT)
        {
            LOGGER.trace("Cannot parse sys:archivedOriginalParentAssoc '{}': expected {} '|'-separated tokens but got {}", stringValue, TOKEN_COUNT, tokens.length);
            return Optional.empty();
        }

        String parentNodeRef = tokens[0].trim();
        String uuid = NodeRefUtils.extractUuid(parentNodeRef);

        String assocQName = tokens[3].trim();
        if (!assocQName.startsWith("{"))
        {
            LOGGER.trace("Cannot parse sys:archivedOriginalParentAssoc '{}': association QName '{}' does not start with '{'", stringValue, assocQName);
            return Optional.empty();
        }
        int uriEnd = assocQName.indexOf('}');
        if (uriEnd < 0 || uriEnd == assocQName.length() - 1)
        {
            LOGGER.trace("Cannot parse sys:archivedOriginalParentAssoc '{}': association QName '{}' has no closing '}' or no local name", stringValue, assocQName);
            return Optional.empty();
        }
        String uri = assocQName.substring(1, uriEnd);
        String localName = assocQName.substring(uriEnd + 1);

        return Optional.of(new ArchivedOriginalParentAssoc(uuid, uri, localName));
    }
}
