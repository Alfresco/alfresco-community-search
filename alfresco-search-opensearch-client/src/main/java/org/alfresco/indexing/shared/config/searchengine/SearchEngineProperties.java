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
import java.util.Locale;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

@ConfigurationProperties(prefix = "spring.elasticsearch")
public class SearchEngineProperties
{
    public static final String AUTH_MODE_BASIC = "basic";
    public static final String AUTH_MODE_AWS_IAM = "aws-iam";
    public static final String DEFAULT_AUTH_MODE = AUTH_MODE_BASIC;

    private static final Set<String> SUPPORTED_AUTH_MODES = Set.of(AUTH_MODE_BASIC, AUTH_MODE_AWS_IAM);

    /**
     * Comma-separated list of the Elasticsearch instances to use.
     */
    private List<String> uris = new ArrayList<>(Collections.singletonList("http://localhost:9200"));

    /**
     * Authentication mode used when communicating with Elasticsearch. Validated by {@link #setAuthMode(String)} so an unsupported value fails fast at property binding.
     */
    private String authMode = DEFAULT_AUTH_MODE;

    /**
     * Username for authentication with Elasticsearch.
     */
    private String username;

    /**
     * Password for authentication with Elasticsearch.
     */
    private String password;

    /**
     * Connection timeout used when communicating with Elasticsearch.
     */
    private Duration connectionTimeout = Duration.ofSeconds(1);

    /**
     * Socket timeout used when communicating with Elasticsearch.
     */
    private Duration socketTimeout = Duration.ofSeconds(30);

    /**
     * Prefix added to the path of every request sent to Elasticsearch.
     */
    private String pathPrefix;

    public List<String> getUris()
    {
        return this.uris;
    }

    public void setUris(List<String> uris)
    {
        this.uris = uris;
    }

    public String getAuthMode()
    {
        return this.authMode;
    }

    public void setAuthMode(String authMode)
    {
        this.authMode = normalizeAuthMode(authMode);
    }

    public static String normalizeAuthMode(String authMode)
    {
        if (!StringUtils.hasText(authMode)
                || !SUPPORTED_AUTH_MODES.contains(authMode.trim().toLowerCase(Locale.ROOT)))
        {
            throw new IllegalArgumentException("Unsupported spring.elasticsearch.auth-mode; expected basic or aws-iam");
        }
        return authMode.trim().toLowerCase(Locale.ROOT);
    }

    public String getUsername()
    {
        return this.username;
    }

    public void setUsername(String username)
    {
        this.username = username;
    }

    public String getPassword()
    {
        return this.password;
    }

    public void setPassword(String password)
    {
        this.password = password;
    }

    public Duration getConnectionTimeout()
    {
        return this.connectionTimeout;
    }

    public void setConnectionTimeout(Duration connectionTimeout)
    {
        this.connectionTimeout = connectionTimeout;
    }

    public Duration getSocketTimeout()
    {
        return this.socketTimeout;
    }

    public void setSocketTimeout(Duration socketTimeout)
    {
        this.socketTimeout = socketTimeout;
    }

    public String getPathPrefix()
    {
        return this.pathPrefix;
    }

    public void setPathPrefix(String pathPrefix)
    {
        this.pathPrefix = pathPrefix;
    }
}
