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
package org.alfresco.indexing.shared;

import lombok.experimental.UtilityClass;

@UtilityClass
@SuppressWarnings("PMD.LongVariable")
public class IndexingConstants
{
    public static final String SFS_UUID_ATTRIBUTE_NAME = "sfsuuid";
    public static final String NODE_UUID_ATTRIBUTE_NAME = "uuid";
    public static final String MODIFIED_AT_ATTRIBUTE_NAME = "modifiedAt";
    public static final String EXTRACTED_TEXT_ATTRIBUTE_NAME = "extractedText";
    public static final String EXTRACTED_TEXT_NOT_FOUND_ATTRIBUTE_NAME = "extractedTextNotFound";
    public static final String EXTRACTED_TEXT_ERR_DETAILS_ATTRIBUTE_NAME = "extractedTextErrDetails";
    public static final String STORE_REF_WORKSPACE_SPACESSTORE = "workspace://SpacesStore/";
    public static final String STORE_REF_ARCHIVE_SPACESSTORE = "archive://SpacesStore/";
    public static final String TARGET_MEDIA_TYPE = "text/plain";
}
