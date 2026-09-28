/*-
 * #%L
 * Alfresco Search Community :: Reindexing Core
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
package org.alfresco.reindexing.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

import org.alfresco.database.connector.PrefixUriMapper;

/**
 * Unit tests for {@link PrefixNamespaceConfig} — holds the resolved prefix map and exposes the {@link PrefixUriMapper} consumed (unchanged) by {@code AlfrescoNodeProcessor} / {@code ElasticsearchPathWriter}.
 */
class PrefixNamespaceConfigTest
{
    @Test
    void ignoresNullMapAndRetainsPrevious()
    {
        PrefixNamespaceConfig config = new PrefixNamespaceConfig();
        config.setPrefixUriMap(Map.of("u1", "p1"));

        config.setPrefixUriMap(null);

        assertThat(config.prefixUriMapper().getUriMapping("u1")).isEqualTo("p1");
    }

    @Test
    void ignoresEmptyMapAndRetainsPrevious()
    {
        PrefixNamespaceConfig config = new PrefixNamespaceConfig();
        config.setPrefixUriMap(Map.of("u1", "p1"));

        config.setPrefixUriMap(Map.of());

        assertThat(config.prefixUriMapper().getUriMapping("u1")).isEqualTo("p1");
    }

    @Test
    void mapperResolvesKnownUriAndReturnsNullForUnknown()
    {
        PrefixNamespaceConfig config = new PrefixNamespaceConfig();
        config.setPrefixUriMap(Map.of("http://www.alfresco.org/model/content/1.0", "cm"));

        PrefixUriMapper mapper = config.prefixUriMapper();

        assertThat(mapper.getUriMapping("http://www.alfresco.org/model/content/1.0")).isEqualTo("cm");
        assertThat(mapper.getUriMapping("http://unknown")).isNull();
    }

    @Test
    void mapperReflectsLatestSetMap()
    {
        PrefixNamespaceConfig config = new PrefixNamespaceConfig();
        config.setPrefixUriMap(Map.of("u1", "p1"));

        PrefixUriMapper mapper = config.prefixUriMapper(); // obtained before the second set

        config.setPrefixUriMap(Map.of("u2", "p2"));

        assertThat(mapper.getUriMapping("u2")).isEqualTo("p2");
        assertThat(mapper.getUriMapping("u1")).isNull();
    }
}
