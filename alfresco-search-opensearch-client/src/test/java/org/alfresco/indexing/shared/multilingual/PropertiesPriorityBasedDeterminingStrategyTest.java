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

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class PropertiesPriorityBasedDeterminingStrategyTest
{
    @Test
    public void shouldReturnEqualButNotTheSameListOfPropertiesWhenLocalePriorityListIsNull()
    {
        var toTest = new PropertiesPriorityBasedDeterminingStrategy(null);

        Map<String, Serializable> properties = Map.of("testKey", "testValue");
        Map<String, Serializable> determinedProperties = toTest.determineProperties(properties, null);

        Assertions.assertNotSame(properties, determinedProperties);
        Assertions.assertEquals(properties, determinedProperties);
    }

    @Test
    public void nullPriorityList_nullLocalizedProperties_nullProperties_emptyResult()
    {
        var toTest = new PropertiesPriorityBasedDeterminingStrategy(null);

        Map<String, Serializable> determinedProperties = toTest.determineProperties(null, null);

        Assertions.assertEquals(0, determinedProperties.size());
    }

    @Test
    public void nullPriorityList_emptyLocalizedProperties_emptyProperties_emptyResult()
    {
        var toTest = new PropertiesPriorityBasedDeterminingStrategy(null);

        Map<String, Serializable> determinedProperties = toTest.determineProperties(Map.of(), Map.of());

        Assertions.assertEquals(0, determinedProperties.size());
    }

    @Test
    public void nullPriorityList_emptyLocalizedProperties_oldPropertiesChosen()
    {
        var toTest = new PropertiesPriorityBasedDeterminingStrategy(null);

        Map<String, Serializable> properties = new NodeResourcePropertyTestBuilder()
                .withProperty("property_1", "propertyValue_1")
                .buildProperties();

        Map<String, Serializable> determinedProperties = toTest.determineProperties(properties, Map.of());

        Assertions.assertEquals(1, determinedProperties.size());
        Assertions.assertTrue(determinedProperties.containsKey("property_1"));
        Assertions.assertEquals("propertyValue_1", determinedProperties.get("property_1"));
    }

    @Test
    public void emptyPriorityList_emptyLocalizedProperties_oldPropertiesChosen()
    {
        var toTest = new PropertiesPriorityBasedDeterminingStrategy(new String[0]);

        Map<String, Serializable> properties = new NodeResourcePropertyTestBuilder()
                .withProperty("property_1", "propertyValue_1")
                .buildProperties();

        Map<String, Serializable> determinedProperties = toTest.determineProperties(properties, Map.of());

        Assertions.assertEquals(1, determinedProperties.size());
        Assertions.assertTrue(determinedProperties.containsKey("property_1"));
        Assertions.assertEquals("propertyValue_1", determinedProperties.get("property_1"));
    }

    @Test
    public void emptyPriorityList_emptyStringLocalizedProperties_oldPropertiesChosen()
    {
        var toTest = new PropertiesPriorityBasedDeterminingStrategy(new String[]{""});

        Map<String, Serializable> properties = new NodeResourcePropertyTestBuilder()
                .withProperty("property_1", "propertyValue_1")
                .buildProperties();

        Map<String, Serializable> determinedProperties = toTest.determineProperties(properties, Map.of());

        Assertions.assertEquals(1, determinedProperties.size());
        Assertions.assertTrue(determinedProperties.containsKey("property_1"));
        Assertions.assertEquals("propertyValue_1", determinedProperties.get("property_1"));
    }

    @Test
    public void emptyPriorityList_propertiesAndLocalizedPropertiesNotEmpty_oldPropertiesChosen()
    {
        var toTest = new PropertiesPriorityBasedDeterminingStrategy(new String[0]);

        Map<String, Serializable> properties = new NodeResourcePropertyTestBuilder()
                .withProperty("property_1", "propertyValue_1")
                .buildProperties();

        Map<String, Map<String, String>> localizedProperties = new NodeResourcePropertyTestBuilder()
                .withLocalizedPropertyForLanguage("property_1", Locale.ENGLISH, "propertyValue_en_1")
                .withLocalizedPropertyForLanguage("property_2", Locale.ENGLISH, "propertyValue_en_2")
                .withLocalizedPropertyForLanguage("property_1", Locale.GERMAN, "propertyValue_de_1")
                .buildLocalizedProperties();

        Map<String, Serializable> determinedProperties = toTest.determineProperties(properties, localizedProperties);

        Assertions.assertEquals(1, determinedProperties.size());
        Assertions.assertTrue(determinedProperties.containsKey("property_1"));
        Assertions.assertEquals("propertyValue_1", determinedProperties.get("property_1"));
    }

    @Test
    public void priorityListNotEmpty_localizedPropertiesNotEmpty_englishChosenAsFirstPriority()
    {
        var toTest = new PropertiesPriorityBasedDeterminingStrategy(new String[]{Locale.ENGLISH.toString(), Locale.GERMAN.toString()});

        Map<String, Map<String, String>> localizedProperties = new NodeResourcePropertyTestBuilder()
                .withLocalizedPropertyForLanguage("property_1", Locale.ENGLISH, "propertyValue_en_1")
                .withLocalizedPropertyForLanguage("property_2", Locale.ENGLISH, "propertyValue_en_2")
                .withLocalizedPropertyForLanguage("property_1", Locale.GERMAN, "propertyValue_de_1")
                .withLocalizedPropertyForLanguage("property_2", Locale.GERMAN, "propertyValue_de_2")
                .buildLocalizedProperties();

        Map<String, Serializable> determinedProperties = toTest.determineProperties(Map.of(), localizedProperties);

        Assertions.assertEquals(2, determinedProperties.size());
        Assertions.assertTrue(determinedProperties.containsKey("property_1"));
        Assertions.assertTrue(determinedProperties.containsKey("property_2"));

        Assertions.assertEquals("propertyValue_en_1", determinedProperties.get("property_1"));
        Assertions.assertEquals("propertyValue_en_2", determinedProperties.get("property_2"));
    }

    /**
     * Similar to {@link #priorityListNotEmpty_localizedPropertiesNotEmpty_englishChosenAsFirstPriority()} but checking if default language (english) is overridden
     */
    @Test
    public void priorityListNotEmpty_localizedPropertiesNotEmpty_germanChosenAsFirstPriority()
    {
        var toTest = new PropertiesPriorityBasedDeterminingStrategy(new String[]{Locale.GERMAN.toString(), Locale.ENGLISH.toString()});

        Map<String, Map<String, String>> localizedProperties = new NodeResourcePropertyTestBuilder()
                .withLocalizedPropertyForLanguage("property_1", Locale.ENGLISH, "propertyValue_en_1")
                .withLocalizedPropertyForLanguage("property_1", Locale.GERMAN, "propertyValue_de_1")
                .buildLocalizedProperties();

        Map<String, Serializable> determinedProperties = toTest.determineProperties(Map.of(), localizedProperties);

        Assertions.assertEquals(1, determinedProperties.size());
        Assertions.assertTrue(determinedProperties.containsKey("property_1"));
        Assertions.assertEquals("propertyValue_de_1", determinedProperties.get("property_1"));
    }

    @Test
    public void priorityListNotEmpty_englishChosenAsFirstPriorityButMissing_germanChosen()
    {
        var toTest = new PropertiesPriorityBasedDeterminingStrategy(new String[]{Locale.ENGLISH.toString(), Locale.GERMAN.toString()});

        Map<String, Map<String, String>> localizedProperties = new NodeResourcePropertyTestBuilder()
                .withLocalizedPropertyForLanguage("property_1", Locale.GERMAN, "propertyValue_de_1")
                .withLocalizedPropertyForLanguage("property_1", Locale.ITALIAN, "propertyValue_it_1")
                .buildLocalizedProperties();

        Map<String, Serializable> determinedProperties = toTest.determineProperties(Map.of(), localizedProperties);

        Assertions.assertEquals(1, determinedProperties.size());
        Assertions.assertTrue(determinedProperties.containsKey("property_1"));
        Assertions.assertEquals("propertyValue_de_1", determinedProperties.get("property_1"));
    }

    @Test
    public void priorityListNotEmpty_localizedPropertiesOnlyItalianAndNotInPriorityList_italianChosen()
    {
        var toTest = new PropertiesPriorityBasedDeterminingStrategy(new String[]{Locale.ENGLISH.toString(), Locale.GERMAN.toString()});

        Map<String, Serializable> properties = new NodeResourcePropertyTestBuilder()
                .withProperty("property_1", "propertyValue_1")
                .buildProperties();

        Map<String, Map<String, String>> localizedProperties = new NodeResourcePropertyTestBuilder()
                .withLocalizedPropertyForLanguage("property_1", Locale.ITALIAN, "propertyValue_it_1")
                .buildLocalizedProperties();

        Map<String, Serializable> determinedProperties = toTest.determineProperties(properties, localizedProperties);

        Assertions.assertEquals(1, determinedProperties.size());
        Assertions.assertTrue(determinedProperties.containsKey("property_1"));
        Assertions.assertEquals("propertyValue_it_1", determinedProperties.get("property_1"));
    }

    @Test
    public void priorityListNotEmpty_localizedPropertiesNotInPriorityList_alphabeticalOrderChosen()
    {
        var toTest = new PropertiesPriorityBasedDeterminingStrategy(new String[]{Locale.ENGLISH.toString(), Locale.GERMAN.toString()});

        Map<String, Serializable> properties = new NodeResourcePropertyTestBuilder()
                .withProperty("property_1", "propertyValue_1")
                .buildProperties();

        Map<String, Map<String, String>> localizedProperties = new NodeResourcePropertyTestBuilder()
                .withLocalizedPropertyForLanguage("property_1", Locale.JAPAN, "propertyValue_ja_1")
                .withLocalizedPropertyForLanguage("property_1", Locale.FRENCH, "propertyValue_fr_1")
                .withLocalizedPropertyForLanguage("property_1", Locale.ITALIAN, "propertyValue_it_1")
                .buildLocalizedProperties();

        Map<String, Serializable> determinedProperties = toTest.determineProperties(properties, localizedProperties);

        Assertions.assertEquals(1, determinedProperties.size());
        Assertions.assertTrue(determinedProperties.containsKey("property_1"));
        Assertions.assertEquals("propertyValue_fr_1", determinedProperties.get("property_1"));

    }

    @Test
    public void priorityListNotEmpty_localizedPropertiesNotInPriorityList_alphabeticalOrderChosen_twoProperties()
    {
        var toTest = new PropertiesPriorityBasedDeterminingStrategy(new String[]{Locale.ENGLISH.toString(), Locale.GERMAN.toString()});

        Map<String, Serializable> properties = new NodeResourcePropertyTestBuilder()
                .withProperty("property_1", "propertyValue_1")
                .buildProperties();

        Map<String, Map<String, String>> localizedProperties = new NodeResourcePropertyTestBuilder()
                .withLocalizedPropertyForLanguage("property_1", Locale.JAPAN, "propertyValue_ja_1")
                .withLocalizedPropertyForLanguage("property_1", Locale.FRENCH, "propertyValue_fr_1")
                .withLocalizedPropertyForLanguage("property_1", Locale.ITALIAN, "propertyValue_it_1")
                .withLocalizedPropertyForLanguage("property_2", Locale.JAPAN, "propertyValue_ja_2")
                .withLocalizedPropertyForLanguage("property_2", Locale.FRENCH, "propertyValue_fr_2")
                .withLocalizedPropertyForLanguage("property_2", Locale.ITALIAN, "propertyValue_it_2")
                .buildLocalizedProperties();

        Map<String, Serializable> determinedProperties = toTest.determineProperties(properties, localizedProperties);

        Assertions.assertEquals(2, determinedProperties.size());
        Assertions.assertTrue(determinedProperties.containsKey("property_1"));
        Assertions.assertEquals("propertyValue_fr_1", determinedProperties.get("property_1"));
        Assertions.assertTrue(determinedProperties.containsKey("property_2"));
        Assertions.assertEquals("propertyValue_fr_2", determinedProperties.get("property_2"));
    }

    @Test
    public void priorityListNotEmpty_localizedPropertiesEmpty_oldPropertiesChosen()
    {
        var toTest = new PropertiesPriorityBasedDeterminingStrategy(new String[]{Locale.ENGLISH.toString(), Locale.GERMAN.toString()});

        Map<String, Serializable> properties = new NodeResourcePropertyTestBuilder()
                .withProperty("property_1", "propertyValue_1")
                .buildProperties();

        Map<String, Serializable> determinedProperties = toTest.determineProperties(properties, Map.of());

        Assertions.assertEquals(1, determinedProperties.size());
        Assertions.assertTrue(determinedProperties.containsKey("property_1"));
        Assertions.assertEquals("propertyValue_1", determinedProperties.get("property_1"));
    }

    @Test
    public void priorityListNotEmpty_localizedPropertiesNull_oldPropertiesChosen()
    {
        var toTest = new PropertiesPriorityBasedDeterminingStrategy(new String[]{Locale.ENGLISH.toString(), Locale.GERMAN.toString()});

        Map<String, Serializable> properties = new NodeResourcePropertyTestBuilder()
                .withProperty("property_1", "propertyValue_1")
                .buildProperties();

        Map<String, Serializable> determinedProperties = toTest.determineProperties(properties, null);

        Assertions.assertEquals(1, determinedProperties.size());
        Assertions.assertTrue(determinedProperties.containsKey("property_1"));
        Assertions.assertEquals("propertyValue_1", determinedProperties.get("property_1"));
    }

    @Test
    public void multiplePriorities_multipleLocalizedProperties_topPriorityFrench_frenchChosen()
    {
        String[] localePriorities = allAvailableLocalesWithPriorityForGivenLocale(0, Locale.FRENCH);
        var toTest = new PropertiesPriorityBasedDeterminingStrategy(localePriorities);

        Map<String, Serializable> determinedProperties = toTest.determineProperties(Map.of(), setupMultipleLocalizedPropertiesForAllAvailableLocales());

        Assertions.assertEquals(3, determinedProperties.size());
        Assertions.assertTrue(determinedProperties.containsKey("property_1"));
        Assertions.assertTrue(determinedProperties.containsKey("property_2"));
        Assertions.assertTrue(determinedProperties.containsKey("property_3"));

        Assertions.assertEquals("propertyValue_fr_1", determinedProperties.get("property_1"));
        Assertions.assertEquals("propertyValue_fr_2", determinedProperties.get("property_2"));
        Assertions.assertEquals("propertyValue_fr_3", determinedProperties.get("property_3"));
    }

    @Test
    public void propertiesAndLocalizedPropertiesMerged_localizedPropertiesTookPrecedence()
    {
        var toTest = new PropertiesPriorityBasedDeterminingStrategy(new String[]{Locale.ENGLISH.toString(), Locale.ITALIAN.toString()});

        Map<String, Serializable> properties = new NodeResourcePropertyTestBuilder()
                .withProperty("property_1", "propertyValue_1")
                .withProperty("property_2", "propertyValue_2")
                .buildProperties();

        Map<String, Map<String, String>> localizedProperties = new NodeResourcePropertyTestBuilder()
                .withLocalizedPropertyForLanguage("property_2", Locale.ITALIAN, "propertyValue_it_2")
                .withLocalizedPropertyForLanguage("property_3", Locale.ITALIAN, "propertyValue_it_3")
                .buildLocalizedProperties();

        Map<String, Serializable> determinedProperties = toTest.determineProperties(properties, localizedProperties);

        Assertions.assertEquals(3, determinedProperties.size());

        Assertions.assertTrue(determinedProperties.containsKey("property_1"));
        Assertions.assertTrue(determinedProperties.containsKey("property_2"));
        Assertions.assertTrue(determinedProperties.containsKey("property_3"));

        Assertions.assertEquals("propertyValue_1", determinedProperties.get("property_1"));
        Assertions.assertEquals("propertyValue_it_2", determinedProperties.get("property_2"));
        Assertions.assertEquals("propertyValue_it_3", determinedProperties.get("property_3"));
    }

    @Test
    public void priorityListNotEmpty_localizedPropertiesNotEmpty_closestMatchChosen()
    {
        var toTest = new PropertiesPriorityBasedDeterminingStrategy(new String[]{"en_US", "en_GB", "en"});

        Map<String, Serializable> properties = new NodeResourcePropertyTestBuilder()
                .withProperty("property_1", "propertyValue_1")
                .buildProperties();

        Map<String, Map<String, String>> localizedProperties = new NodeResourcePropertyTestBuilder()
                .withLocalizedPropertyForLanguage("property_1", "en_CA", "propertyValue_en_CA_1")
                .buildLocalizedProperties();

        Map<String, Serializable> determinedProperties = toTest.determineProperties(properties, localizedProperties);

        Assertions.assertEquals(1, determinedProperties.size());
        Assertions.assertTrue(determinedProperties.containsKey("property_1"));
        Assertions.assertEquals("propertyValue_en_CA_1", determinedProperties.get("property_1"));
    }

    @Test
    public void priorityListNotEmpty_localizedPropertiesTwoLocales_closestMatchChosen()
    {
        var toTest = new PropertiesPriorityBasedDeterminingStrategy(new String[]{"en_US", "en_GB", "en"});

        Map<String, Serializable> properties = new NodeResourcePropertyTestBuilder()
                .withProperty("property_1", "propertyValue_1")
                .buildProperties();

        Map<String, Map<String, String>> localizedProperties = new NodeResourcePropertyTestBuilder()
                .withLocalizedPropertyForLanguage("property_1", "en_CA", "propertyValue_en_CA_1")
                .withLocalizedPropertyForLanguage("property_1", Locale.FRENCH, "propertyValue_fr_1")
                .buildLocalizedProperties();

        Map<String, Serializable> determinedProperties = toTest.determineProperties(properties, localizedProperties);

        Assertions.assertEquals(1, determinedProperties.size());
        Assertions.assertTrue(determinedProperties.containsKey("property_1"));
        Assertions.assertEquals("propertyValue_en_CA_1", determinedProperties.get("property_1"));
    }

    @Test
    public void priorityListNotEmpty_localizedPropertiesNotEmpty_closestMatchAlphabeticalOrderChosen()
    {
        var toTest = new PropertiesPriorityBasedDeterminingStrategy(new String[]{"en"});

        Map<String, Serializable> properties = new NodeResourcePropertyTestBuilder()
                .withProperty("property_1", "propertyValue_1")
                .buildProperties();

        Map<String, Map<String, String>> localizedProperties = new NodeResourcePropertyTestBuilder()
                .withLocalizedPropertyForLanguage("property_1", "en_GB", "propertyValue_en_GB_1")
                .withLocalizedPropertyForLanguage("property_1", "en_CA", "propertyValue_en_CA_1")
                .withLocalizedPropertyForLanguage("property_1", "en_US", "propertyValue_en_US_1")
                .buildLocalizedProperties();

        Map<String, Serializable> determinedProperties = toTest.determineProperties(properties, localizedProperties);

        Assertions.assertEquals(1, determinedProperties.size());
        Assertions.assertTrue(determinedProperties.containsKey("property_1"));
        Assertions.assertEquals("propertyValue_en_CA_1", determinedProperties.get("property_1"));
    }

    @Test
    public void priorityListNotEmpty_localizedPropertiesNotEmpty_exactMatchChosen()
    {
        var toTest = new PropertiesPriorityBasedDeterminingStrategy(new String[]{"de", "en"});

        Map<String, Serializable> properties = new NodeResourcePropertyTestBuilder()
                .withProperty("property_1", "propertyValue_1")
                .buildProperties();

        Map<String, Map<String, String>> localizedProperties = new NodeResourcePropertyTestBuilder()
                .withLocalizedPropertyForLanguage("property_1", "en_GB", "propertyValue_en_GB_1")
                .withLocalizedPropertyForLanguage("property_1", "en_CA", "propertyValue_en_CA_1")
                .withLocalizedPropertyForLanguage("property_1", "en_US", "propertyValue_en_US_1")
                .withLocalizedPropertyForLanguage("property_1", "en", "propertyValue_en_1")
                .buildLocalizedProperties();

        Map<String, Serializable> determinedProperties = toTest.determineProperties(properties, localizedProperties);

        Assertions.assertEquals(1, determinedProperties.size());
        Assertions.assertTrue(determinedProperties.containsKey("property_1"));
        Assertions.assertEquals("propertyValue_en_1", determinedProperties.get("property_1"));
    }

    @Test
    public void priorityListNotEmpty_localizedPropertiesNotEmpty_noMatchAlphabeticalOrderChosen()
    {
        var toTest = new PropertiesPriorityBasedDeterminingStrategy(new String[]{"de"});

        Map<String, Serializable> properties = new NodeResourcePropertyTestBuilder()
                .withProperty("property_1", "propertyValue_1")
                .buildProperties();

        Map<String, Map<String, String>> localizedProperties = new NodeResourcePropertyTestBuilder()
                .withLocalizedPropertyForLanguage("property_1", "en_GB", "propertyValue_en_GB_1")
                .withLocalizedPropertyForLanguage("property_1", "en_CA", "propertyValue_en_CA_1")
                .withLocalizedPropertyForLanguage("property_1", "en_US", "propertyValue_en_US_1")
                .withLocalizedPropertyForLanguage("property_1", "en", "propertyValue_en_1")
                .buildLocalizedProperties();

        Map<String, Serializable> determinedProperties = toTest.determineProperties(properties, localizedProperties);

        Assertions.assertEquals(1, determinedProperties.size());
        Assertions.assertTrue(determinedProperties.containsKey("property_1"));
        Assertions.assertEquals("propertyValue_en_1", determinedProperties.get("property_1"));
    }

    @Test
    public void priorityListNotEmpty_localizedPropertiesNotEmpty_closestENChosen()
    {
        var toTest = new PropertiesPriorityBasedDeterminingStrategy(new String[]{"en", "pl"});

        Map<String, Serializable> properties = new NodeResourcePropertyTestBuilder()
                .withProperty("property_1", "propertyValue_1")
                .buildProperties();

        Map<String, Map<String, String>> localizedProperties = new NodeResourcePropertyTestBuilder()
                .withLocalizedPropertyForLanguage("property_1", "en_GB", "propertyValue_en_GB_1")
                .withLocalizedPropertyForLanguage("property_1", "en_US", "propertyValue_en_US_1")
                .withLocalizedPropertyForLanguage("property_1", "pl", "propertyValue_pl_1")
                .buildLocalizedProperties();

        Map<String, Serializable> determinedProperties = toTest.determineProperties(properties, localizedProperties);

        Assertions.assertEquals(1, determinedProperties.size());
        Assertions.assertTrue(determinedProperties.containsKey("property_1"));
        Assertions.assertEquals("propertyValue_en_GB_1", determinedProperties.get("property_1"));
    }

    @Test
    public void shouldIgnoreNullAndBlankLocalizedValues()
    {
        var toTest = new PropertiesPriorityBasedDeterminingStrategy(new String[]{"en", "pl"});

        Map<String, Serializable> properties = new NodeResourcePropertyTestBuilder()
                .withProperty("property_1", "propertyValue_1")
                .buildProperties();

        Map<String, Map<String, String>> localizedProperties = new NodeResourcePropertyTestBuilder()
                .withLocalizedPropertyForLanguage("property_1", "en_GB", null)
                .withLocalizedPropertyForLanguage("property_1", "en_US", "")
                .withLocalizedPropertyForLanguage("property_1", "pl", "\t\n \r")
                .buildLocalizedProperties();

        Map<String, Serializable> determinedProperties = toTest.determineProperties(properties, localizedProperties);
        Assertions.assertEquals(1, determinedProperties.size());
        Assertions.assertTrue(determinedProperties.containsKey("property_1"));
        Assertions.assertEquals("propertyValue_1", determinedProperties.get("property_1"));
    }

    private Map<String, Map<String, String>> setupMultipleLocalizedPropertiesForAllAvailableLocales()
    {
        NodeResourcePropertyTestBuilder nodeResourcesBuilder = new NodeResourcePropertyTestBuilder();

        for (String language : Locale.getISOLanguages())
        {
            nodeResourcesBuilder.withLocalizedPropertyForLanguage("property_1", language, "propertyValue_" + language + "_1");
            nodeResourcesBuilder.withLocalizedPropertyForLanguage("property_2", language, "propertyValue_" + language + "_2");
            nodeResourcesBuilder.withLocalizedPropertyForLanguage("property_3", language, "propertyValue_" + language + "_3");
        }
        return nodeResourcesBuilder.buildLocalizedProperties();
    }

    private String[] allAvailableLocalesWithPriorityForGivenLocale(int priority, Locale topPriorityLocale)
    {
        List<String> localePriorities = new ArrayList<>(Arrays.asList(Locale.getISOLanguages()));
        localePriorities.removeIf(locale -> locale.equalsIgnoreCase(topPriorityLocale.getLanguage()));
        localePriorities.add(priority, topPriorityLocale.getLanguage());
        return localePriorities.toArray(new String[Locale.getISOLanguages().length]);
    }

    private static class NodeResourcePropertyTestBuilder
    {

        Map<String, Map<String, String>> localizedProperties = new HashMap<>();
        Map<String, Serializable> properties = new HashMap<>();

        NodeResourcePropertyTestBuilder withLocalizedPropertyForLanguage(String property, Locale locale, String propertyValue)
        {
            return withLocalizedPropertyForLanguage(property, locale.getLanguage(), propertyValue);
        }

        NodeResourcePropertyTestBuilder withLocalizedPropertyForLanguage(String property, String locale, String propertyValue)
        {
            if (localizedProperties.containsKey(property))
            {
                localizedProperties.get(property).put(locale, propertyValue);
            }
            else
            {
                localizedProperties.put(property, new HashMap<>() {
                    {
                        put(locale, propertyValue);
                    }
                });
            }
            return this;
        }

        NodeResourcePropertyTestBuilder withProperty(String propertyKey, String propertyValue)
        {
            properties.put(propertyKey, propertyValue);
            return this;
        }

        Map<String, Map<String, String>> buildLocalizedProperties()
        {
            return this.localizedProperties;
        }

        Map<String, Serializable> buildProperties()
        {
            return this.properties;
        }
    }
}
