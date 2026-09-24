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

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.DeprecatedConfigurationProperty;

@ConfigurationProperties(prefix = "spring.elasticsearch.rest")
@Deprecated
class DeprecatedSearchEngineRestClientProperties
{
    /**
     * Comma-separated list of the Elasticsearch instances to use.
     */
    private List<String> uris = new ArrayList<>(Collections.singletonList("http://localhost:9200"));

    /**
     * Credentials username.
     */
    private String username;

    /**
     * Credentials password.
     */
    private String password;

    /**
     * Connection timeout.
     */
    private Duration connectionTimeout = Duration.ofSeconds(1);

    /**
     * Read timeout.
     */
    private Duration readTimeout = Duration.ofSeconds(30);

    private final Sniffer sniffer = new Sniffer();

    private boolean customized = false;

    @DeprecatedConfigurationProperty(replacement = "spring.elasticsearch.uris")
    public List<String> getUris()
    {
        return this.uris;
    }

    public void setUris(List<String> uris)
    {
        this.customized = true;
        this.uris = uris;
    }

    @DeprecatedConfigurationProperty(replacement = "spring.elasticsearch.username")
    public String getUsername()
    {
        return this.username;
    }

    public void setUsername(String username)
    {
        this.customized = true;
        this.username = username;
    }

    @DeprecatedConfigurationProperty(replacement = "spring.elasticsearch.password")
    public String getPassword()
    {
        return this.password;
    }

    public void setPassword(String password)
    {
        this.customized = true;
        this.password = password;
    }

    @DeprecatedConfigurationProperty(replacement = "spring.elasticsearch.connection-timeout")
    public Duration getConnectionTimeout()
    {
        return this.connectionTimeout;
    }

    public void setConnectionTimeout(Duration connectionTimeout)
    {
        this.customized = true;
        this.connectionTimeout = connectionTimeout;
    }

    @DeprecatedConfigurationProperty(replacement = "spring.elasticsearch.socket-timeout")
    public Duration getReadTimeout()
    {
        return this.readTimeout;
    }

    public void setReadTimeout(Duration readTimeout)
    {
        this.customized = true;
        this.readTimeout = readTimeout;
    }

    boolean isCustomized()
    {
        return this.customized;
    }

    public Sniffer getSniffer()
    {
        return this.sniffer;
    }

    @Deprecated
    class Sniffer
    {

        /**
         * Interval between consecutive ordinary sniff executions.
         */
        private Duration interval = Duration.ofMinutes(5);

        /**
         * Delay of a sniff execution scheduled after a failure.
         */
        private Duration delayAfterFailure = Duration.ofMinutes(1);

        @DeprecatedConfigurationProperty(replacement = "spring.elasticsearch.restclient.sniffer.interval")
        public Duration getInterval()
        {
            return this.interval;
        }

        public void setInterval(Duration interval)
        {
            DeprecatedSearchEngineRestClientProperties.this.customized = true;
            this.interval = interval;
        }

        @DeprecatedConfigurationProperty(replacement = "spring.elasticsearch.restclient.sniffer.delay-after-failure")
        public Duration getDelayAfterFailure()
        {
            return this.delayAfterFailure;
        }

        public void setDelayAfterFailure(Duration delayAfterFailure)
        {
            DeprecatedSearchEngineRestClientProperties.this.customized = true;
            this.delayAfterFailure = delayAfterFailure;
        }

    }
}
