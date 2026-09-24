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

import static org.alfresco.indexing.shared.IndexingConstants.TARGET_MEDIA_TYPE;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClient.Builder;
import reactor.util.retry.Retry;

import org.alfresco.indexing.shared.mtls.WebClientBuilderAdjuster;

public class AcceptedContentMediaTypesCache
{

    private final Logger LOGGER = LoggerFactory.getLogger(AcceptedContentMediaTypesCache.class);

    private final AcceptedContentMediaTypesCacheProperties properties;

    private volatile Set<Object> sourceMediaTypes;

    WebClientBuilderAdjuster adjuster;

    public AcceptedContentMediaTypesCache(AcceptedContentMediaTypesCacheProperties properties, WebClientBuilderAdjuster adjuster)
    {
        this.properties = properties;
        this.adjuster = adjuster;
    }

    public void refresh()
    {
        List<String> urls = properties.getResolvedBaseUrls();
        if (urls.isEmpty())
        {
            return;
        }

        Set<Object> aggregated = new HashSet<>();
        int successCount = 0;

        for (String url : urls)
        {
            try
            {
                Set<Object> result = fetchFromEndpoint(url);
                aggregated.addAll(result);
                successCount++;
            }
            catch (Exception ex)
            {
                LOGGER.warn("Failed to fetch media types from {}: {}", url, ex.getMessage());
            }
        }

        if (successCount == urls.size())
        {
            sourceMediaTypes = Set.copyOf(aggregated);
            LOGGER.info("Refreshed media-type cache from {} endpoint(s), accepted type count: {}", urls.size(), aggregated.size());
        }
        else if (successCount > 0)
        {
            if (sourceMediaTypes != null)
            {
                aggregated.addAll(sourceMediaTypes);
            }
            sourceMediaTypes = Set.copyOf(aggregated);
            LOGGER.warn("Partial refresh ({}/{} endpoints succeeded). Merged with previous cache, accepted type count: {}",
                    successCount, urls.size(), aggregated.size());
        }
        else
        {
            LOGGER.error("All {} endpoint(s) failed during media-type cache refresh. Keeping previous cache.", urls.size());
        }
    }

    private Set<Object> fetchFromEndpoint(String url)
    {
        Builder webClientBuilder = WebClient.builder();
        adjuster.adjust(webClientBuilder);
        return webClientBuilder
                .baseUrl(url)
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(-1))
                .build()
                .get()
                .retrieve()
                .bodyToMono(Map.class)
                .doOnError(ex -> LOGGER.error("Can't retrieve content media types from {}: {}", url, ex.getMessage()))
                .retryWhen(Retry.backoff(properties.getRetryMaxAttempts(), Duration.ofMillis(100)))
                .flatMapIterable(t -> (List<Map<String, Object>>) t.get("transformers"))
                .flatMapIterable(t -> ((List<Map<String, Object>>) t.get("supportedSourceAndTargetList")))
                .filter(t -> TARGET_MEDIA_TYPE.equals(t.get("targetMediaType")))
                .map(t -> t.get("sourceMediaType"))
                .collect(Collectors.toSet())
                .block();
    }

    public boolean isSupported(String mimetype)
    {
        if (sourceMediaTypes == null || sourceMediaTypes.isEmpty())
        {
            return true;
        }
        else
        {
            return sourceMediaTypes.contains(mimetype);
        }
    }
}
