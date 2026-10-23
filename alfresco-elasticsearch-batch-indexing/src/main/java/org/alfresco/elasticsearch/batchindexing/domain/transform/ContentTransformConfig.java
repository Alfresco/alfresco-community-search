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

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

import org.alfresco.elasticsearch.batchindexing.config.AcsProperties;
import org.alfresco.elasticsearch.batchindexing.config.RepositoryProperties;
import org.alfresco.elasticsearch.batchindexing.domain.transform.AcsEndpointResolver.AcsEndpoint;

/**
 * Configuration for Transform Core AIO direct integration.
 */
@Configuration
@EnableConfigurationProperties({ContentTransformProperties.class, AcsProperties.class, RepositoryProperties.class})
public class ContentTransformConfig
{
    private final ContentTransformProperties properties;
    private final AcsProperties acsProperties;
    private final RepositoryProperties repositoryProperties;

    public ContentTransformConfig(ContentTransformProperties properties, AcsProperties acsProperties, RepositoryProperties repositoryProperties)
    {
        this.properties = properties;
        this.acsProperties = acsProperties;
        this.repositoryProperties = repositoryProperties;
    }

    @Bean
    public ContentTransformClient contentTransformClient()
    {
        AcsEndpoint endpoint = AcsEndpointResolver.resolve(
                acsProperties.getUrl(), repositoryProperties.getUrl(), properties.getUrlPath());
        // Finalize the effective service path the client appends to the base URL.
        properties.setUrlPath(endpoint.urlPath());

        WebClient acsWebClient = WebClient.builder()
                .baseUrl(normalizeBaseUrl(endpoint.baseUrl()))
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(properties.getMaxResponseSizeMb() * 1024 * 1024))
                .build();

        return new ContentTransformClient(properties, acsWebClient);
    }

    // Strip a trailing slash so the fixed urlPath appends cleanly onto the ACS context path.
    static String normalizeBaseUrl(String url)
    {
        return (url != null && url.endsWith("/")) ? url.substring(0, url.length() - 1) : url;
    }
}
