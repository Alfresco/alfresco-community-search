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

import java.util.concurrent.TimeoutException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.util.retry.Retry;

@Slf4j
@RequiredArgsConstructor
public class ContentTransformClient
{
    private final ContentTransformProperties properties;
    private final WebClient acsWebClient;

    /**
     * Fetch transformed content for the given node. See {@link ContentTransformResult} for the three possible shapes. 4xx responses surface as a failure so the caller can route the node to the DLQ; 5xx responses stay swallowed (they've already been retried inside the WebClient and are expected to recover on a later reindex cycle via the overlap re-scan).
     */
    public ContentTransformResult transformContent(Long dbId)
    {
        try
        {
            log.debug("Starting direct transformation for node id: {}", dbId);

            String transformedText = fetchContentFromAcs(dbId);

            if (transformedText == null)
            {
                log.warn("Empty response from ACS for node id: {}", dbId);
                return ContentTransformResult.empty();
            }

            if (log.isDebugEnabled())
            {
                log.debug("Successfully transformed content for node id: {}, text length: {}",
                        dbId, transformedText.length());
            }
            return ContentTransformResult.ofText(transformedText);
        }
        catch (WebClientResponseException e)
        {
            HttpStatusCode status = e.getStatusCode();
            String body = e.getResponseBodyAsString();
            if (status.is4xxClientError())
            {
                log.warn("Content transform unavailable for node id: {} (HTTP {})", dbId, status);
                return ContentTransformResult.ofFailure(new ContentTransformFailure(
                        "TRANSFORM_HTTP_" + status.value(),
                        body == null || body.isBlank() ? e.getMessage() : body));
            }
            log.error("Content transform failed for node id: {} (HTTP {}): {}", dbId, status, body);
            return ContentTransformResult.empty();
        }
        catch (Exception e)
        {
            log.error("Content transform failed for node id: {}", dbId, e);
            return ContentTransformResult.empty();
        }
    }

    private String fetchContentFromAcs(Long dbId)
    {
        return acsWebClient
                .get()
                .uri(uriBuilder -> uriBuilder
                        .path(properties.getUrlPath())
                        .queryParam("nodeId", dbId)
                        .build())
                .header("X-Alfresco-Search-Secret", properties.getSharedSecret())
                .retrieve()
                .bodyToMono(String.class)
                .timeout(properties.getTimeout())
                .retryWhen(Retry.fixedDelay(properties.getRetryMaxAttempts(), properties.getRetryDelay())
                        .filter(throwable -> throwable instanceof WebClientResponseException.ServiceUnavailable
                                || throwable instanceof TimeoutException))
                .block();
    }
}
