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
package org.alfresco.indexing.shared.utils;

import java.util.regex.Pattern;

import lombok.experimental.UtilityClass;

/**
 * Helpers for dealing with Alfresco node references.
 *
 * <p>
 * Since the trashcan feature a node reference can be qualified with more than one store: content of an archived node is read from {@code archive://SpacesStore/} while live content is read from {@code workspace://SpacesStore/}. Anything that needs the bare node id has to cope with both.
 * </p>
 */
@UtilityClass
public class NodeRefUtils
{
    /** Matches any {@code <protocol>://<identifier>/} store prefix at the beginning of a node reference. */
    private static final Pattern STORE_REF_PREFIX = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*://[^/]+/");

    /**
     * Extracts the bare node id from a node reference, regardless of the store it belongs to.
     *
     * @param nodeRef
     *            a store-qualified node reference such as {@code workspace://SpacesStore/<id>} or {@code archive://SpacesStore/<id>}, or an already bare node id
     * @return the node id, or {@code null} when {@code nodeRef} is {@code null}
     */
    public static String extractUuid(String nodeRef)
    {
        if (nodeRef == null)
        {
            return null;
        }
        return STORE_REF_PREFIX.matcher(nodeRef).replaceFirst("");
    }
}
