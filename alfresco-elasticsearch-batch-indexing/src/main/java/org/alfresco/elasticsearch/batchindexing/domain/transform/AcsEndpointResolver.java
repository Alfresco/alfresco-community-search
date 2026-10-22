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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves the ACS base URL and content-transform service path from the new {@code alfresco.repository.url} and the deprecated {@code alfresco.acs.url} / {@code alfresco.content.transform.urlPath}, keeping old deployments working. The new property carries the full base URL including the context path; the deprecated pair split it as host plus context-in-path. TODO: delete together with `alfresco.acs.url` and AcsProperties.class on next major release
 */
public final class AcsEndpointResolver
{
    static final String DEFAULT_REPOSITORY_URL = "http://localhost:8080/alfresco";
    static final String DEFAULT_URL_PATH = "/service/api/solr/textContent";
    static final String LEGACY_ACS_URL = "http://localhost:8080";
    static final String LEGACY_URL_PATH = "/alfresco/service/api/solr/textContent";

    private static final Logger LOGGER = LoggerFactory.getLogger(AcsEndpointResolver.class);

    private AcsEndpointResolver()
    {}

    /**
     * @param acsUrl
     *            the deprecated {@code alfresco.acs.url}
     * @param repositoryUrl
     *            the new {@code alfresco.repository.url}
     * @param urlPath
     *            the deprecated {@code alfresco.content.transform.urlPath}
     * @return the base URL and service path to call
     * @throws IllegalStateException
     *             if {@code alfresco.acs.url} is set together with {@code alfresco.repository.url}
     */
    public static AcsEndpoint resolve(String acsUrl, String repositoryUrl, String urlPath)
    {
        boolean acsSet = isSet(acsUrl);
        boolean repositorySet = isSet(repositoryUrl);
        boolean urlPathSet = isSet(urlPath);

        if (acsSet && repositorySet)
        {
            throw new IllegalStateException(
                    "Both the deprecated alfresco.acs.url and the new alfresco.repository.url are configured, so the "
                            + "ACS base URL is ambiguous. Remove alfresco.acs.url and keep alfresco.repository.url (the "
                            + "ACS base URL including the context path, for example " + DEFAULT_REPOSITORY_URL + ").");
        }

        // Deprecated host-only base: the context lives in the service path.
        if (acsSet)
        {
            warnDeprecated();
            return new AcsEndpoint(acsUrl, urlPathSet ? urlPath : LEGACY_URL_PATH);
        }

        // New base URL (with context). A custom service path is allowed alongside it.
        if (repositorySet)
        {
            return new AcsEndpoint(repositoryUrl, urlPathSet ? urlPath : DEFAULT_URL_PATH);
        }

        // Only the deprecated service path is set: it must already carry the context
        // (the legacy host base has none), so keep the old host-only base.
        if (urlPathSet)
        {
            warnDeprecated();
            return new AcsEndpoint(LEGACY_ACS_URL, urlPath);
        }

        return new AcsEndpoint(DEFAULT_REPOSITORY_URL, DEFAULT_URL_PATH);
    }

    private static void warnDeprecated()
    {
        LOGGER.warn("alfresco.acs.url and alfresco.content.transform.urlPath are deprecated. Set "
                + "alfresco.repository.url to the ACS base URL including the context path (for example {}); "
                + "the fixed service path is appended automatically.", DEFAULT_REPOSITORY_URL);
    }

    private static boolean isSet(String value)
    {
        return value != null && !value.isBlank();
    }

    /** The resolved ACS base URL and the service path appended to it. */
    public record AcsEndpoint(String baseUrl, String urlPath)
    {}
}
