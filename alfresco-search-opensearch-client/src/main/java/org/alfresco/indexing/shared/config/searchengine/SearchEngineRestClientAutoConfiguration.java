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
package org.alfresco.indexing.shared.config.searchengine;

import org.opensearch.client.RestClientBuilder;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;

@AutoConfiguration
@ConditionalOnClass({RestClientBuilder.class, OpenSearchClient.class})
@EnableConfigurationProperties({SearchEngineProperties.class, SearchEngineRestClientProperties.class,
        DeprecatedSearchEngineRestClientProperties.class})
@Import({SearchEngineRestClientConfigurations.RestClientBuilderConfiguration.class,
        SearchEngineRestClientConfigurations.BasicOpenSearchClientConfiguration.class,
        SearchEngineRestClientConfigurations.RestClientConfiguration.class,
        SearchEngineRestClientConfigurations.RestClientSnifferConfiguration.class})
public class SearchEngineRestClientAutoConfiguration
{
    @Bean
    SearchEngineAuthentication searchEngineAuthentication(SearchEngineProperties properties, Environment environment,
            ObjectProvider<SearchEngineAuthentication.AwsIamCapability> capabilities)
    {
        return new SearchEngineAuthentication(properties, environment, capabilities.stream().findAny().isPresent());
    }
}
