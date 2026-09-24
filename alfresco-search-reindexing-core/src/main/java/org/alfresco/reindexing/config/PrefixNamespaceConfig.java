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

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import jakarta.annotation.PostConstruct;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;
import org.springframework.util.StringUtils;

import org.alfresco.database.connector.PrefixUriMapper;
import org.alfresco.reindexing.domain.namespace.response.PrefixUriMapping;

@Configuration
public class PrefixNamespaceConfig
{
    private Map<String, String> prefixUriMap;

    private static final String BUNDLED_PREFIXES_FILE = "classpath:reindex.prefixes-file.json";

    /** Operator override. Blank means the bundled map; the reindexing app leaves it blank and replaces the map later. */
    @Value("${alfresco.reindex.prefixes-file:}")
    private String prefixesFile;

    @PostConstruct
    public void init()
    {
        // Bootstrap default, so prefixUriMap is never null or empty.
        // PrefixSourceConfig replaces it with fresh API data on ApplicationStartedEvent.
        this.prefixUriMap = load(StringUtils.hasText(prefixesFile) ? prefixesFile : BUNDLED_PREFIXES_FILE);
    }

    private Map<String, String> load(String location)
    {
        ResourceLoader resourceLoader = new DefaultResourceLoader();
        try (InputStream is = resourceLoader.getResource(location).getInputStream())
        {
            ObjectMapper mapper = new ObjectMapper();
            PrefixUriMapping mapping = mapper.readValue(is, PrefixUriMapping.class);
            return mapping.prefixUriMap();
        }
        catch (IOException e)
        {
            throw new IllegalStateException("Failed to load namespace prefixes from [" + location + "]", e);
        }
    }

    public void setPrefixUriMap(Map<String, String> prefixUriMap)
    {
        if (prefixUriMap != null && !prefixUriMap.isEmpty())
        {
            this.prefixUriMap = prefixUriMap;
        }
    }

    public PrefixUriMapper prefixUriMapper()
    {
        return (a) -> prefixUriMap.get(a);
    }
}
