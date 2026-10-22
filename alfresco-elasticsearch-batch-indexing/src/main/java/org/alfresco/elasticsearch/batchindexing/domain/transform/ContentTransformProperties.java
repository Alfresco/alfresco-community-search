/*-
 * #%L
 * Alfresco Search Community :: Batch Indexing
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
package org.alfresco.elasticsearch.batchindexing.domain.transform;

import java.time.Duration;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ToString(exclude = "sharedSecret")
@ConfigurationProperties(prefix = "alfresco.content.transform")
public class ContentTransformProperties
{
    private String urlPath;

    private Duration timeout = Duration.ofSeconds(20);

    private String sharedSecret;

    private int maxResponseSizeMb = 10;

    /** Maximum number of retry attempts when ACS returns a 503 Service Unavailable response. */
    private int retryMaxAttempts = 2;

    /** Delay between retry attempts for failed content transform requests. */
    private Duration retryDelay = Duration.ofSeconds(1);
}
