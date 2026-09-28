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

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.List;

import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.Credentials;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.async.HttpAsyncClientBuilder;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManagerBuilder;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.util.Timeout;
import org.opensearch.client.Node;
import org.opensearch.client.RestClient;
import org.opensearch.client.RestClientBuilder;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.sniff.OpenSearchNodesSniffer;
import org.opensearch.client.sniff.OpenSearchNodesSniffer.Scheme;
import org.opensearch.client.sniff.Sniffer;
import org.opensearch.client.sniff.SnifferBuilder;
import org.opensearch.client.transport.OpenSearchTransport;
import org.opensearch.client.transport.rest_client.RestClientTransport;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.context.properties.PropertyMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.util.StringUtils;

import org.alfresco.indexing.shared.utils.OpenSearchJsonMapperFactory;

@Slf4j
public class SearchEngineRestClientConfigurations
{
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnMissingBean(RestClientBuilder.class)
    @Conditional(SearchEngineAuthentication.BasicCondition.class)
    static class RestClientBuilderConfiguration
    {

        private final ConsolidatedProperties properties;

        @SuppressWarnings("deprecation")
        RestClientBuilderConfiguration(SearchEngineProperties properties,
                DeprecatedSearchEngineRestClientProperties deprecatedProperties)
        {
            this.properties = new ConsolidatedProperties(properties, deprecatedProperties);
        }

        @Bean
        SearchEngineRestClientBuilderCustomizer defaultRestClientBuilderCustomizer()
        {
            return new DefaultRestClientBuilderCustomizer(this.properties);
        }

        @Bean
        @DependsOn("searchEngineAuthentication")
        RestClientBuilder elasticsearchRestClientBuilder(
                ObjectProvider<SearchEngineRestClientBuilderCustomizer> builderCustomizers)
        {
            HttpHost[] hosts = this.properties.getUris().stream().map(uri -> createHttpHost(URI.create(uri))).toArray(HttpHost[]::new);
            RestClientBuilder builder = RestClient.builder(hosts);
            String pathPrefix = getPathPrefix();
            builder.setHttpClientConfigCallback((httpClientBuilder) -> {
                builderCustomizers.orderedStream().forEach((customizer) -> customizer.customize(httpClientBuilder));
                return httpClientBuilder;
            });

            builder.setRequestConfigCallback((requestConfigBuilder) -> {
                builderCustomizers.orderedStream().forEach((customizer) -> customizer.customize(requestConfigBuilder));
                return requestConfigBuilder;
            });

            if (StringUtils.hasText(pathPrefix))
            {
                builder.setPathPrefix(pathPrefix);
            }

            builderCustomizers.orderedStream().forEach((customizer) -> customizer.customize(builder));
            return builder;
        }

        String getPathPrefix()
        {
            return this.properties.getPathPrefix();
        }

        private HttpHost createHttpHost(URI uri)
        {
            if (!StringUtils.hasLength(uri.getUserInfo()))
            {
                return HttpHost.create(uri);
            }
            try
            {
                return HttpHost.create(new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), uri.getPath(),
                        uri.getQuery(), uri.getFragment()).toString());
            }
            catch (URISyntaxException ex)
            {
                throw new IllegalStateException(ex);
            }
        }

    }

    @SuppressWarnings("deprecation")
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(OpenSearchClient.class)
    @Conditional(SearchEngineAuthentication.BasicCondition.class)
    @ConditionalOnMissingBean(OpenSearchClient.class)
    static class BasicOpenSearchClientConfiguration
    {

        @Bean
        OpenSearchClient elasticsearch(RestClient restClientBuilder)
        {
            OpenSearchTransport transport = new RestClientTransport(
                    restClientBuilder, OpenSearchJsonMapperFactory.createJsonpMapper());
            return new OpenSearchClient(transport);
        }

    }

    /* @SuppressWarnings("deprecation")
     * 
     * @Configuration(proxyBeanMethods = false)
     * 
     * @ConditionalOnClass(org.opensearch.client.opensearch.OpenSearchClient.class)
     * 
     * @ConditionalOnSingleCandidate(org.opensearch.client.opensearch.OpenSearchClient.class)
     * 
     * @ConditionalOnMissingBean(RestClient.class) static class RestClientFromRestHighLevelClientConfiguration {
     * 
     * @Bean RestClient elasticsearchRestClient(org.opensearch.client.opensearch.OpenSearchClient openSearchClient) { return openSearchClient.getScriptLanguages(); } } */

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnMissingClass("org.opensearch.client.RestHighLevelClient")
    @ConditionalOnMissingBean(RestClient.class)
    @Conditional(SearchEngineAuthentication.BasicCondition.class)
    static class RestClientConfiguration
    {
        @Bean
        @DependsOn("searchEngineAuthentication")
        RestClient elasticsearchRestClient(RestClientBuilder restClientBuilder)
        {
            return restClientBuilder.build();
        }

    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(Sniffer.class)
    @ConditionalOnSingleCandidate(RestClient.class)
    @ConditionalOnProperty(prefix = "spring.elasticsearch.restclient.sniffer", name = "enabled", havingValue = "true", matchIfMissing = true)
    @Conditional(SearchEngineAuthentication.BasicCondition.class)
    static class RestClientSnifferConfiguration
    {

        @Bean
        @ConditionalOnMissingBean
        @SuppressWarnings("deprecation")
        Sniffer elasticsearchSniffer(RestClient client, SearchEngineRestClientProperties properties,
                DeprecatedSearchEngineRestClientProperties deprecatedProperties)
        {
            SnifferBuilder builder;
            boolean useHttps = false;

            log.debug("Initializing sniffer. Enabled: {}", properties.getSniffer().isEnabled());

            if (client != null && client.getNodes() != null)
            {
                List<Node> nodes = client.getNodes();
                for (Node node : nodes)
                {
                    HttpHost host = node.getHost();
                    log.debug("Sniffer: Found host: {} with scheme: {}", host, host.getSchemeName());
                    if (host != null && "https".equalsIgnoreCase(host.getSchemeName()))
                    {
                        useHttps = true;
                        break;
                    }
                }
            }

            long requestTimeout = properties.getSniffer().getRequestTimeout() != null
                    ? properties.getSniffer().getRequestTimeout().toMillis()
                    : OpenSearchNodesSniffer.DEFAULT_SNIFF_REQUEST_TIMEOUT;

            Scheme scheme = useHttps ? Scheme.HTTPS : Scheme.HTTP;

            log.debug("Creating sniffer with scheme: {} and request timeout: {} ms", scheme, requestTimeout);

            OpenSearchNodesSniffer nodeSniffer = new OpenSearchNodesSniffer(client, requestTimeout, scheme);
            builder = Sniffer.builder(client).setNodesSniffer(nodeSniffer);

            PropertyMapper map = PropertyMapper.get();

            Duration interval = deprecatedProperties.isCustomized() ? deprecatedProperties.getSniffer().getInterval()
                    : properties.getSniffer().getInterval();
            log.debug("Sniffer interval: {}", interval);
            map.from(interval).asInt(Duration::toMillis).to(builder::setSniffIntervalMillis);

            Duration delayAfterFailure = deprecatedProperties.isCustomized()
                    ? deprecatedProperties.getSniffer().getDelayAfterFailure()
                    : properties.getSniffer().getDelayAfterFailure();
            log.debug("Sniffer delay after failure: {}", delayAfterFailure);
            map.from(delayAfterFailure).asInt(Duration::toMillis).to(builder::setSniffAfterFailureDelayMillis);

            return builder.build();
        }

    }

    static class DefaultRestClientBuilderCustomizer implements SearchEngineRestClientBuilderCustomizer
    {

        private static final PropertyMapper MAP = PropertyMapper.get();

        private final ConsolidatedProperties properties;

        DefaultRestClientBuilderCustomizer(ConsolidatedProperties properties)
        {
            this.properties = properties;
        }

        @Override
        public void customize(RestClientBuilder builder)
        {
            log.trace("No customizations applied to RestClientBuilder");
        }

        @Override
        public void customize(HttpAsyncClientBuilder builder)
        {
            builder.setDefaultCredentialsProvider(new PropertiesCredentialsProvider(this.properties));

            ConnectionConfig.Builder connectionConfig = ConnectionConfig.custom();
            MAP.from(this.properties::getConnectionTimeout).as(Timeout::of)
                    .to(connectionConfig::setConnectTimeout);
            MAP.from(this.properties::getSocketTimeout).as(Timeout::of)
                    .to(connectionConfig::setSocketTimeout);

            var connManager = PoolingAsyncClientConnectionManagerBuilder.create()
                    .useSystemProperties()
                    .setDefaultConnectionConfig(connectionConfig.build())
                    .build();

            builder.setConnectionManager(connManager);
        }
    }

    private static class PropertiesCredentialsProvider extends BasicCredentialsProvider
    {

        PropertiesCredentialsProvider(ConsolidatedProperties properties)
        {
            super();
            if (StringUtils.hasText(properties.getUsername()))
            {
                Credentials credentials = new UsernamePasswordCredentials(properties.getUsername(),
                        properties.getPassword().toCharArray());
                setCredentials(new AuthScope(null, -1), credentials);
            }
            properties.getUris().stream().map(this::toUri).filter(this::hasUserInfo)
                    .forEach(this::addUserInfoCredentials);
        }

        private URI toUri(String uri)
        {
            try
            {
                return URI.create(uri);
            }
            catch (IllegalArgumentException ex)
            {
                return null;
            }
        }

        private boolean hasUserInfo(URI uri)
        {
            return uri != null && StringUtils.hasLength(uri.getUserInfo());
        }

        private void addUserInfoCredentials(URI uri)
        {
            AuthScope authScope = new AuthScope(uri.getHost(), uri.getPort());
            Credentials credentials = createUserInfoCredentials(uri.getUserInfo());
            setCredentials(authScope, credentials);
        }

        private Credentials createUserInfoCredentials(String userInfo)
        {
            int delimiter = userInfo.indexOf(':');
            if (delimiter == -1)
            {
                return new UsernamePasswordCredentials(userInfo, null);
            }

            String username = userInfo.substring(0, delimiter);
            String password = userInfo.substring(delimiter + 1);
            return new UsernamePasswordCredentials(username, password.toCharArray());
        }

    }

    @SuppressWarnings("deprecation")
    private static final class ConsolidatedProperties
    {

        private final SearchEngineProperties properties;

        private final DeprecatedSearchEngineRestClientProperties deprecatedProperties;

        private ConsolidatedProperties(SearchEngineProperties properties,
                DeprecatedSearchEngineRestClientProperties deprecatedProperties)
        {
            this.properties = properties;
            this.deprecatedProperties = deprecatedProperties;
        }

        private List<String> getUris()
        {
            return this.deprecatedProperties.isCustomized() ? this.deprecatedProperties.getUris()
                    : this.properties.getUris();
        }

        private String getUsername()
        {
            return this.deprecatedProperties.isCustomized() ? this.deprecatedProperties.getUsername()
                    : this.properties.getUsername();
        }

        private String getPassword()
        {
            return this.deprecatedProperties.isCustomized() ? this.deprecatedProperties.getPassword()
                    : this.properties.getPassword();
        }

        private Duration getConnectionTimeout()
        {
            return this.deprecatedProperties.isCustomized() ? this.deprecatedProperties.getConnectionTimeout()
                    : this.properties.getConnectionTimeout();
        }

        private Duration getSocketTimeout()
        {
            return this.deprecatedProperties.isCustomized() ? this.deprecatedProperties.getReadTimeout()
                    : this.properties.getSocketTimeout();
        }

        private String getPathPrefix()
        {
            return this.deprecatedProperties.isCustomized() ? null : this.properties.getPathPrefix();
        }
    }
}
