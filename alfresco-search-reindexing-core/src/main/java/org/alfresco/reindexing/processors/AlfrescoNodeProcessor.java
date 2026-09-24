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
package org.alfresco.reindexing.processors;

import static java.util.Optional.*;
import static java.util.Optional.empty;

import java.io.*;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import org.alfresco.database.connector.model.AlfrescoNode;
import org.alfresco.database.connector.model.ContentMetadata;
import org.alfresco.database.connector.model.NodeProperty;
import org.alfresco.database.connector.model.PropertyValue;
import org.alfresco.database.connector.model.PropertyValueType;
import org.alfresco.database.connector.model.QName;
import org.alfresco.elasticsearch.shared.ElasticsearchConstants;
import org.alfresco.metrics.ReindexingMetrics;
import org.alfresco.reindexing.config.PrefixNamespaceConfig;

/**
 * Base class including common operations on AlfrescoNode objects
 */
public abstract class AlfrescoNodeProcessor
{

    private static final Logger LOGGER = LoggerFactory.getLogger(AlfrescoNodeProcessor.class);

    private final PrefixNamespaceConfig prefixNamespaceConfig;
    /** Optional. Nullable in tests; wired in production via SharedBatchProcessingConfig. */
    private final ReindexingMetrics reindexingMetrics;

    public AlfrescoNodeProcessor(PrefixNamespaceConfig prefixNamespaceConfig)
    {
        this(prefixNamespaceConfig, null);
    }

    public AlfrescoNodeProcessor(PrefixNamespaceConfig prefixNamespaceConfig, ReindexingMetrics reindexingMetrics)
    {
        this.prefixNamespaceConfig = prefixNamespaceConfig;
        this.reindexingMetrics = reindexingMetrics;
    }

    protected Set<String> getAspects(AlfrescoNode node)
    {
        return node.getAspects().stream()
                .map(this::getPrefixedAspectName)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toSet());
    }

    protected Map<String, Serializable> getProperties(AlfrescoNode alfrescoNode)
    {

        Map<QName, List<NodeProperty>> items = alfrescoNode.getNodeProperties()
                .stream()
                .collect(Collectors.groupingBy(node -> QName.newTransientInstance(node.getPropertyKey().getUri(), node.getPropertyKey().getLocalName())));

        return items.entrySet()
                .stream()
                .map(entry -> extractEntryValue(alfrescoNode, entry))
                .flatMap(Optional::stream)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private Optional<Map.Entry<String, Serializable>> extractEntryValue(AlfrescoNode alfrescoNode, Map.Entry<QName, List<NodeProperty>> entry)
    {
        return getPrefixedPropertyName(entry.getKey())
                .flatMap(propertyPrefixedName -> extractSerializableForBatch(alfrescoNode, entry.getValue())
                        .map(propertyValue -> Map.entry(propertyPrefixedName, propertyValue)));

    }

    protected Optional<String> getPrefixedPropertyName(QName qname)
    {
        return resolve(qname, PrefixedItem.PROPERTY);
    }

    protected Optional<String> getPrefixedPropertyName(String uri, String localName)
    {
        return resolve(uri, localName, PrefixedItem.PROPERTY);
    }

    protected Optional<String> getPrefixedAspectName(QName qname)
    {
        return resolve(qname, PrefixedItem.ASPECT);
    }

    protected Optional<String> getPrefixedTypeName(QName qname)
    {
        return resolve(qname, PrefixedItem.TYPE);
    }

    private Optional<String> resolve(QName qname, PrefixedItem item)
    {
        return ofNullable(qname).flatMap(qn -> resolve(qn.getUri(), qn.getLocalName(), item));
    }

    private Optional<String> resolve(String uri, String localName, PrefixedItem item)
    {
        String prefix = prefixNamespaceConfig.prefixUriMapper().getUriMapping(uri);
        if (prefix == null)
        {
            LOGGER.warn("No prefix mapping for namespace {}; {} {} cannot be indexed",
                    uri, item.label(), localName);
            if (reindexingMetrics != null)
            {
                reindexingMetrics.reindexUnresolvedNamespace(uri);
            }
            return empty();
        }

        return Optional.of(prefix + ":" + localName);
    }

    /** Names the kind of thing being resolved so an unmapped namespace can be reported precisely. */
    private enum PrefixedItem
    {
        PROPERTY, ASPECT, TYPE;

        private String label()
        {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private Optional<Serializable> extractSerializableForBatch(AlfrescoNode alfrescoNode,
            List<NodeProperty> alfrescoNodeProperties)
    {

        List<Serializable> propertyValues = alfrescoNodeProperties
                .stream()
                .map(property -> getValue(alfrescoNode, property))
                .flatMap(Optional::stream)
                .collect(Collectors.toList());

        if (propertyValues.isEmpty())
        {
            return empty();
        }
        else if (propertyValues.size() == 1)
        {
            return of(propertyValues.get(0));
        }
        else
        {
            return of((Serializable) propertyValues);
        }
    }

    private Optional<Serializable> getValue(AlfrescoNode alfrescoNode, NodeProperty nodeProperty)
    {
        return getPersistedValue(alfrescoNode, nodeProperty).flatMap(persistedValue -> {
            String localName = nodeProperty.getPropertyKey().getLocalName();
            String prefixedName = getPrefixedPropertyName(nodeProperty.getPropertyKey().getUri(), localName).orElse(localName);
            switch (PropertyValueType.getPropertyValueType(nodeProperty.getPropertyValue().getActualType()))
            {
            case CONTENT_DATA_ID:
                return getContentValue(alfrescoNode, prefixedName, persistedValue);
            case DATE:
                return getDateValue(alfrescoNode, localName, persistedValue);
            case NODEREF:
                return getNodeRefValue(alfrescoNode, localName, persistedValue);
            default:
                return of(persistedValue);
            }
        });
    }

    private Optional<Serializable> getNodeRefValue(AlfrescoNode alfrescoNode, String localName, Serializable persistedValue)
    {
        try
        {
            String stringValue = (String) persistedValue;
            String[] protocolSplit = stringValue.split("://");

            String protocol = protocolSplit[0];

            String[] storeSplit = protocolSplit[1].split("/");
            String storeIdentifier = storeSplit[0];
            String id = storeSplit[1];

            return of((Serializable) Map.of("storeRef",
                    Map.of("identifier", storeIdentifier, "protocol", protocol),
                    "id", id));

        }
        catch (Exception e)
        {
            LOGGER.error("impossible to deserialize noderef property {} for node {}",
                    localName, alfrescoNode.getId());
            return empty();
        }
    }

    private Optional<ContentMetadata> getContentValue(AlfrescoNode alfrescoNode, String qName, Serializable propertyValue)
    {
        if (!qName.equals(ElasticsearchConstants.CONTENT_ATTRIBUTE_NAME))
        {
            LOGGER.info("Found content with localname {} for node {}." +
                    "Content different from cm:content won't be indexed. QName for content {}", qName.split(":")[1], alfrescoNode.getId(), qName);
            return empty();
        }

        return alfrescoNode.getContentData()
                .stream().filter(contentMetadata -> Objects.equals(contentMetadata.getId(), propertyValue))
                .findAny()
                .or(() -> {
                    LOGGER.error("content metadata not found for node {}", alfrescoNode.getId());
                    return empty();
                });

    }

    private Optional<ZonedDateTime> getDateValue(AlfrescoNode alfrescoNode, String localName, Serializable propertyValue)
    {

        try
        {
            return of(ZonedDateTime.parse((String) propertyValue, DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        }
        catch (Exception e)
        {
            LOGGER.error("impossible to get epoch value from {} for node {}", localName, alfrescoNode.getId());
            return empty();
        }
    }

    private Optional<Serializable> getPersistedValue(AlfrescoNode alfrescoNode, NodeProperty alfrescoNodeProperty)
    {

        PropertyValue propertyValue = alfrescoNodeProperty.getPropertyValue();

        switch (PropertyValueType.getPropertyValueType(propertyValue.getPersistedType()))
        {
        case NULL:
            return empty();
        case BOOLEAN:
            return of(propertyValue.getBooleanValue());
        case LONG:
            return of(propertyValue.getLongValue());
        case FLOAT:
            return of(propertyValue.getFloatValue());
        case DOUBLE:
            return of(propertyValue.getDoubleValue());
        case STRING:
            return of(propertyValue)
                    .map(PropertyValue::getStringValue);
        case SERIALIZABLE:
            return deserializeObject(propertyValue.getSerializableValue()).or(() -> {
                LOGGER.warn("error during deserialization of property {} for node {}",
                        alfrescoNodeProperty.getPropertyKey().getLocalName(), alfrescoNode.getId());
                return empty();
            });
        default:
            LOGGER.error("type not recognized. Impossible to to get the persisted value of property {} for node {}",
                    alfrescoNodeProperty.getPropertyKey().getLocalName(), alfrescoNode.getId());
            return empty();
        }
    }

    private Optional<Serializable> deserializeObject(byte[] rawValue)
    {
        try
        {
            ByteArrayInputStream bis = new ByteArrayInputStream(rawValue);
            ObjectInput in = new ObjectInputStream(bis);
            return of((Serializable) in.readObject());
        }
        catch (ClassNotFoundException | IOException e)
        {
            LOGGER.error("impossible to deserialize object: {}", e.getMessage());
        }

        return empty();
    }

    protected Map<String, Map<String, String>> getLocalizedProperties(AlfrescoNode alfrescoNode)
    {
        final LocalizedPropertiesExtractor extractor = new LocalizedPropertiesExtractor();
        alfrescoNode.getNodeProperties().forEach(extractor::add);
        return extractor.getProperties();
    }

    private class LocalizedPropertiesExtractor
    {
        private final Map<String, Map<String, String>> properties = new HashMap<>();

        private Map<String, Map<String, String>> getProperties()
        {
            return properties;
        }

        private void add(NodeProperty nodeProperty)
        {
            final Optional<String> possibleName = ofNullable(nodeProperty)
                    .filter(NodeProperty::isLocalized)
                    .flatMap(p -> getPrefixedPropertyName(p.getPropertyKey().getUri(), p.getPropertyKey().getLocalName()));

            possibleName.ifPresent(propertyName -> {
                String propertyLang = StringUtils.parseLocale(nodeProperty.getPropertyKey().getLocaleStr()).toString();
                String propertyValue = nodeProperty.getPropertyValue().getStringValue();

                properties.computeIfAbsent(propertyName, k -> new HashMap<>()).put(propertyLang, propertyValue);
            });
        }
    }

}
