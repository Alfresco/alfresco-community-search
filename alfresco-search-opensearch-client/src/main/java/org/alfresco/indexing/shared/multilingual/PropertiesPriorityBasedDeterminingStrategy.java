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
package org.alfresco.indexing.shared.multilingual;

import static java.util.Comparator.comparingInt;

import java.io.Serializable;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.springframework.util.StringUtils;

import org.alfresco.indexing.shared.PropertiesDeterminingStrategy;

public class PropertiesPriorityBasedDeterminingStrategy implements PropertiesDeterminingStrategy
{

    private final String[] localePriorityList;

    public PropertiesPriorityBasedDeterminingStrategy(String[] localePriorityList)
    {
        this.localePriorityList = localePriorityList == null ? new String[0]
                : Arrays.stream(localePriorityList)
                        .map(StringUtils::parseLocale)
                        .filter(Objects::nonNull)
                        .map(Locale::toString).toArray(String[]::new);
    }

    @Override
    public Map<String, Serializable> determineProperties(Map<String, Serializable> properties, Map<String, Map<String, String>> localizedProperties)
    {
        if (localePriorityListNotDefined() || localizedPropertiesNotExist(localizedProperties))
        {
            return properties != null ? new HashMap<>(properties) : new HashMap<>(0);
        }
        Map<String, String> propertiesForLanguageFromPriorityList = extractPropertiesForLanguageFromPriorityList(localizedProperties);
        return mergePropertiesAndLocalizedProperties(properties, propertiesForLanguageFromPriorityList);
    }

    private boolean localePriorityListNotDefined()
    {
        return localePriorityList == null || localePriorityList.length == 0;
    }

    private boolean localizedPropertiesNotExist(Map<String, Map<String, String>> localizedProperties)
    {
        return localizedProperties == null || localizedProperties.isEmpty();
    }

    private Map<String, String> extractPropertiesForLanguageFromPriorityList(Map<String, Map<String, String>> localizedProperties)
    {
        Map<String, String> extractedProperties = new HashMap<>();

        for (var localizedProperty : localizedProperties.entrySet())
        {
            String property = localizedProperty.getKey();
            Optional<String> value = extractLocalizedValue(localizedProperty.getValue());
            value.ifPresent(v -> extractedProperties.put(property, v));
        }
        return extractedProperties;
    }

    private Optional<String> extractLocalizedValue(Map<String, String> localizedPropertyValues)
    {
        return valueByPriorityList(localizedPropertyValues)
                .or(() -> valueByLanguageAlphabeticalOrder(localizedPropertyValues));
    }

    private Optional<String> valueByPriorityList(Map<String, String> localizedPropertyValues)
    {
        for (String locale : localePriorityList)
        {
            Optional<String> matchingLocale = findMatchingLocale(locale, localizedPropertyValues);
            if (matchingLocale.isPresent())
            {
                return Optional.of(localizedPropertyValues.get(matchingLocale.get()));
            }
        }
        return Optional.empty();
    }

    private Optional<String> findMatchingLocale(String localeFromPriorityList, Map<String, String> localizedPropertyValues)
    {
        var locales = localizedPropertyValues.keySet();
        return locales.stream()
                .filter(locale -> locale.startsWith(localeFromPriorityList) && StringUtils.hasText(localizedPropertyValues.get(locale)))
                .min(comparingInt(String::length).thenComparing(String::compareTo));
    }

    private Optional<String> valueByLanguageAlphabeticalOrder(Map<String, String> extractedProperties)
    {
        return extractedProperties.entrySet().stream()
                .min(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue);
    }

    private Map<String, Serializable> mergePropertiesAndLocalizedProperties(Map<String, Serializable> properties, Map<String, String> localizedProperties)
    {
        Map<String, Serializable> result = properties != null ? new HashMap<>(properties) : new HashMap<>();
        result.putAll(localizedProperties);
        return result;
    }
}
