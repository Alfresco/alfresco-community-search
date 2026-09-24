/*-
 * #%L
 * Alfresco Elasticsearch Connector :: Batch Indexing Module
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
package org.alfresco.elasticsearch.batchindexing.config;

import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.DeprecatedConfigurationProperty;

/**
 * Old deployments set it to the host without the context path (for example, {@code http://localhost:8080}) and put the context in {@code alfresco.content.transform.urlPath}. Use {@code alfresco.repository.url} from {@link RepositoryProperties} instead, which carries the full base URL including the context path. When this is set the application logs a deprecation warning; setting it together with {@code alfresco.repository.url} stops the application.
 */
@Setter
@ConfigurationProperties(prefix = "alfresco.acs")
@Deprecated(forRemoval = true, since = "5.8")
public class AcsProperties
{
    private String url;

    @DeprecatedConfigurationProperty
    public String getUrl()
    {
        return url;
    }
}
