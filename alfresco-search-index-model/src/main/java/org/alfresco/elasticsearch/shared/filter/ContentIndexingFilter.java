/*-
 * #%L
 * Alfresco Search Community :: Search Index Model
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
package org.alfresco.elasticsearch.shared.filter;

import static java.util.Locale.ENGLISH;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.alfresco.repo.event.v1.model.ContentInfo;

/**
 * Decides whether a node's content may be content indexed, from a configured MIME type deny list and maximum size. Metadata and path indexing are unaffected.
 */
public class ContentIndexingFilter
{
    private static final Logger LOGGER = LoggerFactory.getLogger(ContentIndexingFilter.class);
    private static final long NO_SIZE_LIMIT = 0L;
    private static final int MAX_LOGGED_SKIPS = 10_000;
    private static final String WILDCARD_SUFFIX = "/*";
    private static final ContentIndexingFilter ACCEPT_ALL = new ContentIndexingFilter(Set.of(), NO_SIZE_LIMIT);

    private final Set<String> excludedMimeTypes;
    private final long maxContentSizeBytes;

    /** Skips already logged, keyed by node, mime type and size. Bounded so it cannot grow without limit. */
    private final Map<String, Boolean> loggedSkips = Collections.synchronizedMap(new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest)
        {
            return size() > MAX_LOGGED_SKIPS;
        }
    });

    /** Matching is case-insensitive and blank entries are ignored. A {@code maxContentSizeBytes} of {@code 0} means no limit. */
    public ContentIndexingFilter(Collection<String> excludedMimeTypes, long maxContentSizeBytes)
    {
        this.excludedMimeTypes = excludedMimeTypes.stream()
                .filter(Objects::nonNull)
                .map(mimeType -> mimeType.trim().toLowerCase(ENGLISH))
                .filter(mimeType -> !mimeType.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        this.maxContentSizeBytes = maxContentSizeBytes;
    }

    /** A filter that accepts all content, used when no policy is configured. */
    public static ContentIndexingFilter acceptAll()
    {
        return ACCEPT_ALL;
    }

    /** A node without content is always indexable, because there is nothing to skip. */
    public boolean isIndexable(String nodeId, ContentInfo content)
    {
        if (content == null)
        {
            return true;
        }
        if (isExcludedMimeType(content.getMimeType()))
        {
            logSkipOnce(nodeId, content, "mimetype=" + content.getMimeType() + " is excluded by policy");
            return false;
        }
        if (isOverMaxSize(content.getSizeInBytes()))
        {
            logSkipOnce(nodeId, content, "sizeInBytes=" + content.getSizeInBytes() + " is above the limit of " + maxContentSizeBytes + " bytes");
            return false;
        }
        return true;
    }

    @Override
    public String toString()
    {
        return "ContentIndexingFilter[excludedMimeTypes=%s, maxContentSizeBytes=%s]"
                .formatted(excludedMimeTypes, maxContentSizeBytes > NO_SIZE_LIMIT ? maxContentSizeBytes : "unlimited");
    }

    private void logSkipOnce(String nodeId, ContentInfo content, String reason)
    {
        String key = nodeId + "|" + content.getMimeType() + "|" + content.getSizeInBytes();
        if (loggedSkips.put(key, Boolean.TRUE) == null)
        {
            LOGGER.debug("Content of node {} not indexed: {}", nodeId, reason);
        }
    }

    private boolean isExcludedMimeType(String mimeType)
    {
        if (mimeType == null)
        {
            return false;
        }
        String normalised = mimeType.toLowerCase(ENGLISH);
        if (excludedMimeTypes.contains(normalised))
        {
            return true;
        }
        int separator = normalised.indexOf('/');
        return separator > 0 && excludedMimeTypes.contains(normalised.substring(0, separator) + WILDCARD_SUFFIX);
    }

    private boolean isOverMaxSize(Long sizeInBytes)
    {
        return maxContentSizeBytes > NO_SIZE_LIMIT && sizeInBytes != null && sizeInBytes > maxContentSizeBytes;
    }
}
