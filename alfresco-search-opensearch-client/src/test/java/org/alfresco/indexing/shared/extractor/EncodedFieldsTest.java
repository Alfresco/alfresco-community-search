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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class EncodedFieldsTest
{
    private static final Map<String, String> STORE_REF = Map.of("identifier", "SpacesStore", "protocol", "workspace");

    @Test
    void addEncodedField_Date()
    {
        var encodedFields = new EncodedFields();
        var date = new Date(1234567890);
        var key = "cm:published";

        encodedFields.put(key, date);

        var expected = Map.of("cm%3Apublished", date);
        assertEquals(expected, encodedFields.getEncodedMapping(), "Unexpected fields map after method call.");
        assertThat(encodedFields.get(key))
                .isNotEmpty()
                .get()
                .isEqualTo(date);
    }

    @Test
    void addEncodedField_SetOfStrings()
    {
        var encodedFields = new EncodedFields();
        var colours = Set.of("red", "green", "blue");
        var key = "cm:colours";

        encodedFields.put(key, colours);

        var expected = Map.of("cm%3Acolours", colours);
        assertEquals(expected, encodedFields.getEncodedMapping(), "Unexpected fields map after method call.");
        assertThat(encodedFields.get(key))
                .isNotEmpty()
                .get()
                .isEqualTo(colours);
    }

    @Test
    void addEncodedField_ListOfCategories()
    {
        var encodedFields = new EncodedFields();
        var categoryIds = List.of("01230123-0123-0123-0123-012301230123", "45674567-4567-4567-4567-456745674567");
        List<Map<String, Object>> categories = categoryIds.stream()
                .map(id -> Map.of("id", id, "storeRef", STORE_REF))
                .collect(toList());
        var key = "cm:categories";

        encodedFields.put(key, categories);

        var expected = Map.of("cm%3Acategories", categoryIds);
        assertEquals(expected, encodedFields.getEncodedMapping(), "Unexpected fields map after method call.");
        assertThat(encodedFields.get(key))
                .isNotEmpty()
                .get()
                .isEqualTo(categoryIds);
    }

    @Test
    void removeField()
    {
        var encodedFields = new EncodedFields();
        var key1 = "key1";
        var value1 = "value1";
        var key2 = "cm:content";
        var value2 = "value2";
        encodedFields.put(key1, value1);
        encodedFields.put(key2, value2);

        var removedValue1 = encodedFields.remove(key1);
        assertThat(removedValue1)
                .isNotEmpty()
                .get()
                .isEqualTo(value1);
        assertThat(encodedFields.getEncodedMapping())
                .containsExactlyInAnyOrderEntriesOf(Map.of("cm%3Acontent", value2));

        var removedValue2 = encodedFields.remove(key2);
        assertThat(removedValue2)
                .isNotEmpty()
                .get()
                .isEqualTo(value2);
        assertThat(encodedFields.getEncodedMapping())
                .isEmpty();
    }

    @Test
    void encodedMapping_shouldNotExposeInternalRepresentation()
    {
        var encodedFields = new EncodedFields();
        var key1 = "key1";
        var value1 = "value1";
        var key2 = "cm:taggable";
        var value2 = "value2";

        encodedFields.put(key1, value1);
        var mapping1 = encodedFields.getEncodedMapping();
        var mapping2 = encodedFields.getEncodedMapping();

        assertThat(mapping1)
                .isNotSameAs(mapping2)
                .isEqualTo(mapping2);

        encodedFields.put(key2, value2);
        var mapping3 = encodedFields.getEncodedMapping();

        assertThat(mapping1)
                .isNotSameAs(mapping3)
                .isNotEqualTo(mapping3)
                .containsExactlyInAnyOrderEntriesOf(Map.of(key1, value1));
        assertThat(mapping3)
                .containsExactlyInAnyOrderEntriesOf(Map.of(key1, value1, "cm%3Ataggable", value2));
    }
}
