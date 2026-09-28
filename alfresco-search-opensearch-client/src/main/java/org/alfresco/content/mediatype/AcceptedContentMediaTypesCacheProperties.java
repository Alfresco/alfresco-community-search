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
package org.alfresco.content.mediatype;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(value = "acceptedContentMediaTypesCacheProperties")
@ConfigurationProperties(prefix = "alfresco.accepted-content-media-types-cache")
public class AcceptedContentMediaTypesCacheProperties
{

    private String baseUrl;

    private String refreshTime;

    private boolean enabled;

    private long retryMaxAttempts = 5;

    public String getBaseUrl()
    {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl)
    {
        this.baseUrl = baseUrl;
    }

    public List<String> getResolvedBaseUrls()
    {
        if (baseUrl == null || baseUrl.isBlank())
        {
            return List.of();
        }
        return Arrays.stream(baseUrl.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    public String getRefreshTime()
    {
        return refreshTime;
    }

    public void setRefreshTime(String refreshTime)
    {
        this.refreshTime = refreshTime;
    }

    public boolean isEnabled()
    {
        return enabled;
    }

    public void setEnabled(boolean enabled)
    {
        this.enabled = enabled;
    }

    public long getRetryMaxAttempts()
    {
        return retryMaxAttempts;
    }

    public void setRetryMaxAttempts(long retryMaxAttempts)
    {
        this.retryMaxAttempts = retryMaxAttempts;
    }

    @Override
    public String toString()
    {
        return "AcceptedContentMediaTypesCacheProperties{" +
                "baseUrl='" + baseUrl + '\'' +
                ", refreshTime='" + refreshTime + '\'' +
                ", enabled=" + enabled +
                ", retryMaxAttempts=" + retryMaxAttempts +
                '}';
    }
}
