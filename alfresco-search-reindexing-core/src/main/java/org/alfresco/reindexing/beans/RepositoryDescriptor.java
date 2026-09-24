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
package org.alfresco.reindexing.beans;

import java.io.Serializable;
import java.util.Collection;
import java.util.Map;

/**
 * Alfresco Repository includes special nodes named Descriptor in System Store. These nodes include information relative to the deployment: software version, license type, db schema.
 *
 * <p>
 * The information in this descriptor is read from a node in the system store. After the patch process runs successfully, the version Major/Minor/Revision should be equal to the server descriptor.
 * <p>
 * The "repository id" that uniquely identifies each alfresco repository is available in the "id" property.
 * <p>
 * The following properties are available in the descriptor
 * <ul>
 * <li>Major</li>
 * <li>Minor</li>
 * <li>Revision</li>
 * <li>Label</li>
 * <li>Build</li>
 * <li>Schema</li>
 * <li>name</li>
 * <li>id</li>
 * </ul>
 *
 * The following properties may be present
 * <ul>
 * <li>LicenceKey</li>
 * <li>Edition</li>
 * </ul>
 */
public class RepositoryDescriptor
{

    public static final String ID_PROPERTY_NAME = "sys:node-uuid";
    public static final String TYPE = "sys:descriptor";

    private final Map<String, Serializable> properties;

    public RepositoryDescriptor(final Map<String, Serializable> properties)
    {
        this.properties = properties;
    }

    public Map<String, Serializable> getProperties()
    {
        return properties;
    }

    public String getId()
    {
        return getDescriptor(ID_PROPERTY_NAME);
    }

    public String getName()
    {
        return getDescriptor("sys:name");
    }

    public String getVersionMajor()
    {
        return getDescriptor("sys:versionMajor");
    }

    public String getVersionMinor()
    {
        return getDescriptor("sys:versionMinor");
    }

    public String getVersionRevision()
    {
        return getDescriptor("sys:versionRevision");
    }

    public String getVersionLabel()
    {
        return getDescriptor("sys:versionLabel");
    }

    public String getVersionBuild()
    {
        return getDescriptor("sys:versionBuild");
    }

    public String getEdition()
    {
        return getDescriptor("sys:versionEdition");
    }

    public int getSchema()
    {
        return Integer.parseInt(getDescriptor("sys:versionSchema"));
    }

    public String getLicenseMode()
    {
        return getDescriptor("sys:licenseMode");
    }

    private String getDescriptor(final String key)
    {
        String strValue = null;
        final Serializable value = properties.get(key);
        if (null != value)
        {
            if (value instanceof Collection)
            {
                final Collection<?> coll = (Collection<?>) value;
                if (coll.size() > 0)
                {
                    strValue = coll.iterator().next().toString();
                }
            }
            else
            {
                strValue = value.toString();
            }
        }
        return strValue;
    }

    @Override
    public String toString()
    {
        return "RepositoryDescriptor{" +
                "properties=" + properties +
                '}';
    }

}
