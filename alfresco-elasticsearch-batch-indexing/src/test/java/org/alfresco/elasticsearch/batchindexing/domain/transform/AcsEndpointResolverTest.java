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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import org.alfresco.elasticsearch.batchindexing.domain.transform.AcsEndpointResolver.AcsEndpoint;

@DisplayName("AcsEndpointResolver")
class AcsEndpointResolverTest
{
    // Example inputs when a property is set.
    private static final String ACS_URL = "http://legacy:80";
    private static final String REPO_URL = "http://custom:80/acs";
    private static final String URL_PATH = "/node/content";

    // Values the resolver fills in when the relevant property is unset.
    private static final String DEFAULT_BASE = "http://localhost:8080/alfresco";
    private static final String DEFAULT_PATH = "/service/api/solr/textContent";
    private static final String LEGACY_BASE = "http://localhost:8080";
    private static final String LEGACY_PATH = "/alfresco/service/api/solr/textContent";

    private static void assertEndpoint(AcsEndpoint endpoint, String expectedBase, String expectedPath)
    {
        assertThat(endpoint.baseUrl()).isEqualTo(expectedBase);
        assertThat(endpoint.urlPath()).isEqualTo(expectedPath);
    }

    /**
     * One test per combination of the three ACS URL properties (acs.url / repository.url / urlPath).
     */
    @Nested
    @DisplayName("input combinations of acs.url / repository.url / urlPath")
    class Combinations
    {
        @Test
        @DisplayName("nothing set → new defaults")
        void nothingSet()
        {
            assertEndpoint(AcsEndpointResolver.resolve(null, null, null), DEFAULT_BASE, DEFAULT_PATH);
        }

        @Test
        @DisplayName("urlPath only (legacy: path carries the context) → deprecated host base, given path")
        void urlPathOnly()
        {
            assertEndpoint(AcsEndpointResolver.resolve(null, null, URL_PATH), LEGACY_BASE, URL_PATH);
        }

        @Test
        @DisplayName("repository.url only → new base, default path")
        void repositoryUrlOnly()
        {
            assertEndpoint(AcsEndpointResolver.resolve(null, REPO_URL, null), REPO_URL, DEFAULT_PATH);
        }

        @Test
        @DisplayName("repository.url + urlPath → new base, given path")
        void repositoryUrlAndUrlPath()
        {
            assertEndpoint(AcsEndpointResolver.resolve(null, REPO_URL, URL_PATH), REPO_URL, URL_PATH);
        }

        @Test
        @DisplayName("acs.url only → deprecated base, legacy path")
        void acsUrlOnly()
        {
            assertEndpoint(AcsEndpointResolver.resolve(ACS_URL, null, null), ACS_URL, LEGACY_PATH);
        }

        @Test
        @DisplayName("acs.url + urlPath → deprecated base, given path")
        void acsUrlAndUrlPath()
        {
            assertEndpoint(AcsEndpointResolver.resolve(ACS_URL, null, URL_PATH), ACS_URL, URL_PATH);
        }

        @Test
        @DisplayName("acs.url + repository.url → error")
        void acsUrlAndRepositoryUrl()
        {
            assertThatThrownBy(() -> AcsEndpointResolver.resolve(ACS_URL, REPO_URL, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("alfresco.repository.url");
        }

        @Test
        @DisplayName("acs.url + repository.url + urlPath → error")
        void acsUrlAndRepositoryUrlAndUrlPath()
        {
            assertThatThrownBy(() -> AcsEndpointResolver.resolve(ACS_URL, REPO_URL, URL_PATH))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("alfresco.repository.url");
        }
    }

    @Test
    @DisplayName("blank strings are treated the same as unset")
    void blankValuesAreTreatedAsUnset()
    {
        assertEndpoint(AcsEndpointResolver.resolve("  ", "", "   "), DEFAULT_BASE, DEFAULT_PATH);
    }
}
