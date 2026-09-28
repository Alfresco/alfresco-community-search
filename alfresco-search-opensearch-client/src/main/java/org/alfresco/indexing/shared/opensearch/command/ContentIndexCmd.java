/*
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

package org.alfresco.indexing.shared.opensearch.command;

import java.util.Objects;

import lombok.NonNull;

import org.alfresco.elasticsearch.shared.ElasticsearchConstants;
import org.alfresco.indexing.shared.TransformStatus;

public record ContentIndexCmd(@NonNull String id, @NonNull Long updateTime,
        @NonNull ElasticsearchConstants.ScriptType scriptType, String contentText,
        String transformationStatus, String transformationError)
{

    public static ContentIndexCmd withTextScript(String id, Long updateTime, String contentText)
    {
        Objects.requireNonNull(contentText);
        return new ContentIndexCmd(id, updateTime, ElasticsearchConstants.ScriptType.TEXT_SCRIPT, contentText, null, null);
    }

    public static ContentIndexCmd withExceptionScript(String id, Long updateTime, TransformStatus transformStatus, String transformError)
    {
        Objects.requireNonNull(transformStatus);
        Objects.requireNonNull(transformError);
        return new ContentIndexCmd(id, updateTime, ElasticsearchConstants.ScriptType.EXCEPTION_SCRIPT, null, transformStatus.name(), transformError);
    }
}
