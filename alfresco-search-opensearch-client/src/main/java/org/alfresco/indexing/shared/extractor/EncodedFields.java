/*
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
package org.alfresco.indexing.shared.extractor;

import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.alfresco.elasticsearch.shared.translator.AlfrescoQualifiedNameTranslator;

/**
 * Mutable object holding and managing encoded Elasticsearch fields map.
 */
public class EncodedFields
{
    private final Map<String, Object> fieldsMap;

    public EncodedFields()
    {
        this.fieldsMap = new HashMap<>();
    }

    /**
     * Puts mapping into fields map, encoding the key and preparing the value.
     *
     * @param key
     *            the raw (not encoded) key
     * @param value
     *            the value
     */
    public void put(String key, Object value)
    {
        if (value instanceof Collection<?> collection)
        {
            if (value instanceof Set<?>)
            {
                value = collection.stream()
                        .map(this::prepareField)
                        .collect(toSet());
            }
            else
            {
                value = collection.stream()
                        .map(this::prepareField)
                        .collect(toList());
            }
        }
        else
        {
            value = prepareField(value);
        }
        fieldsMap.put(AlfrescoQualifiedNameTranslator.encode(key), value);
    }

    /**
     * Gets value from fields map by given key.
     *
     * @param key
     *            the raw (not encoded) key
     * @return the optional value
     */
    public Optional<Object> get(String key)
    {
        return Optional.ofNullable(fieldsMap.get(AlfrescoQualifiedNameTranslator.encode(key)));
    }

    /**
     * Removes value from fields map by given key (if key exists).
     *
     * @param key
     *            the raw (not encoded) key
     * @return the optional value that was removed
     */
    public Optional<Object> remove(String key)
    {
        return Optional.ofNullable(fieldsMap.remove(AlfrescoQualifiedNameTranslator.encode(key)));
    }

    /**
     * Gets the entire encoded fields mapping.
     *
     * @return the encoded fields mapping
     */
    public Map<String, Object> getEncodedMapping()
    {
        return new HashMap<>(fieldsMap);
    }

    private Object prepareField(Object field)
    {
        // Handler to remove storeRef from NodeRefs.
        if (field instanceof Map<?, ?> map && map.containsKey("id"))
        {
            return map.get("id");
        }
        return field;
    }
}
