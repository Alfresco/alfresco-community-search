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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ch.qos.logback.classic.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ExecutionContext;

import org.alfresco.database.connector.model.AlfrescoNode;
import org.alfresco.database.connector.model.ChildAssocMetaData;
import org.alfresco.database.connector.model.NodeProperty;
import org.alfresco.database.connector.model.Path;
import org.alfresco.database.connector.model.PropertyKey;
import org.alfresco.database.connector.model.PropertyValue;
import org.alfresco.database.connector.model.PropertyValueType;
import org.alfresco.database.connector.model.QName;
import org.alfresco.indexing.shared.ElasticsearchProperties;
import org.alfresco.indexing.shared.config.FilteringConfig;
import org.alfresco.indexing.shared.model.BulkRequestDto;
import org.alfresco.metrics.ReindexingMetrics;
import org.alfresco.reindexing.config.PrefixNamespaceConfig;
import org.alfresco.reindexing.config.ReindexingCoreProperties;
import org.alfresco.reindexing.model.ReindexingEvent;
import org.alfresco.reindexing.opensearch.ElasticSearchUpdateOperations;
import org.alfresco.reindexing.util.QNamePrefixResolver;
import org.alfresco.reindexing.writers.ElasticsearchPathWriter;
import org.alfresco.repo.event.v1.model.EventData;
import org.alfresco.repo.event.v1.model.NodeResource;
import org.alfresco.repo.event.v1.model.RepoEvent;

/**
 * What happens when a node carries a namespace URI absent from the prefix map (ACS-12851).
 * <p>
 * All four cases are grouped so the operator-visible signal can be compared side by side: what the document loses, and what the operator is told. Each group asserts both.
 * <p>
 * Covers the default {@code onUnresolvedNamespace=filter}. The other two actions are in {@link UnresolvedNamespaceReportingTest}, and the dead-letter record in {@code DlqUnresolvedNamespaceListenerTest}.
 */
@DisplayName("Unresolved namespace URI")
@SuppressWarnings("PMD.CouplingBetweenObjects") // the path case drives the real writer
class UnresolvedNamespaceBehaviourTest
{
    private static final String CM_URI = "http://www.alfresco.org/model/content/1.0";
    private static final String CUSTOM_URI = "http://acme.example.org/model/custom/1.0";
    /** A URI that legitimately maps to the empty prefix, as the bundled map's "" entry does. */
    private static final String DEFAULT_NS_URI = "http://www.alfresco.org/model/default/1.0";

    /** Knows the Alfresco namespaces, not the customer's â€” the shape of every stock deployment. */
    private PrefixNamespaceConfig prefixMap;

    @BeforeEach
    void setUp()
    {
        prefixMap = new PrefixNamespaceConfig();
        prefixMap.setPrefixUriMap(Map.of(CM_URI, "cm"));
    }

    @Nested
    @DisplayName("on a property")
    class UnmappedProperty
    {
        // Reporting lives in QNamePrefixResolver, the single place that decides what an unmapped
        // namespace means. AlfrescoNodeProcessor only supplies the kind of item being named.
        private final LogSpy log = LogSpy.of(QNamePrefixResolver.class);

        @BeforeEach
        void attach()
        {
            log.attach();
        }

        @AfterEach
        void detach()
        {
            log.detach();
        }

        @Test
        @DisplayName("the property is dropped from the document")
        void thePropertyIsDroppedFromTheDocument()
        {
            ReindexingEvent event = process(nodeWith(property(CUSTOM_URI, "invoiceNumber", "INV-1")));

            assertThat(resourceOf(event).getProperties()).doesNotContainKey("acme:invoiceNumber");
        }

        @Test
        @DisplayName("reports the lost property, its namespace URI and its local name")
        void reportsTheLostProperty()
        {
            process(nodeWith(property(CUSTOM_URI, "invoiceNumber", "INV-1")));

            assertReportsLoss(log, "property", "invoiceNumber");
        }
    }

    @Nested
    @DisplayName("on an aspect")
    class UnmappedAspect
    {
        private final LogSpy log = LogSpy.of(QNamePrefixResolver.class);

        @BeforeEach
        void attach()
        {
            log.attach();
        }

        @AfterEach
        void detach()
        {
            log.detach();
        }

        @Test
        @DisplayName("the aspect is dropped from the document")
        void theAspectIsDroppedFromTheDocument()
        {
            ReindexingEvent event = process(nodeWithAspect());

            assertThat(resourceOf(event).getAspectNames()).doesNotContain("acme:invoiceable");
        }

        @Test
        @DisplayName("reports the lost aspect, its namespace URI and its local name")
        void reportsTheLostAspect()
        {
            process(nodeWithAspect());

            assertReportsLoss(log, "aspect", "invoiceable");
        }

        private AlfrescoNode nodeWithAspect()
        {
            AlfrescoNode node = nodeWith();
            when(node.getAspects()).thenReturn(Set.of(QName.newTransientInstance(CUSTOM_URI, "invoiceable")));
            return node;
        }
    }

    @Nested
    @DisplayName("on the node type")
    class UnmappedNodeType
    {
        /** Root logger: the report comes from the emitter, not this processor. */
        private final LogSpy anywhere = LogSpy.ofRoot();

        @BeforeEach
        void attach()
        {
            anywhere.attach();
        }

        @AfterEach
        void detach()
        {
            anywhere.detach();
        }

        @Test
        @DisplayName("nodeType is left unset on the emitted event")
        void nodeTypeIsLeftUnset()
        {
            ReindexingEvent event = process(nodeOfCustomType());

            assertThat(resourceOf(event).getNodeType()).isNull();
        }

        @Test
        @DisplayName("the type filter discards the whole node, even with nothing blacklisted")
        void theTypeFilterDiscardsTheWholeNode()
        {
            ReindexingEvent survived = filterWithNothingBlacklisted(process(nodeOfCustomType()));

            // Returning null makes Spring Batch count the item in filterCount, so the job still reports success.
            assertThat(survived).isNull();
        }

        @Test
        @DisplayName("reports the lost type, its namespace URI and its local name")
        void reportsTheLostType()
        {
            process(nodeOfCustomType());

            assertReportsLoss(anywhere, "type", "invoice");
        }

        @Test
        @DisplayName("reports that the node will not be indexed, not only that a prefix failed")
        void reportsThatTheNodeWillNotBeIndexed()
        {
            filterWithNothingBlacklisted(process(nodeOfCustomType()));

            assertThat(anywhere.formattedAtOrAbove(Level.WARN))
                    .anySatisfy(message -> assertThat(message)
                            .containsIgnoringCase("node")
                            .containsIgnoringCase("will not be indexed"));
        }

        private ReindexingEvent filterWithNothingBlacklisted(ReindexingEvent event)
        {
            FilteringConfig nothingBlacklisted = new FilteringConfig();
            nothingBlacklisted.setNodeTypes(List.of());
            try
            {
                return new ReindexEventNodeTypeFilterProcessor(nothingBlacklisted).process(event);
            }
            catch (Exception e)
            {
                throw new IllegalStateException(e);
            }
        }

        private AlfrescoNode nodeOfCustomType()
        {
            AlfrescoNode node = nodeWith();
            when(node.getType()).thenReturn(QName.newTransientInstance(CUSTOM_URI, "invoice"));
            return node;
        }
    }

    /**
     * Two different setups, one identical rendering.
     * <p>
     * {@code ChildAssocMetaData.asString} (database-connector-api, ACS-12217) branches on {@code prefix == null || prefix.isBlank()} and returns the bare name for both, so nothing downstream can tell an unmapped namespace from one that has no prefix. Scenario 2 requires that. Whether scenario 1 needs to differ is open: no consumer that must distinguish them has been identified, and the indexer cannot write a prefix it does not know.
     */
    @Nested
    @DisplayName("on a path element")
    class UnmappedPathElement
    {
        /** Deliberately identical in both scenarios below. */
        private static final String EXPECTED_RENDERING = "/invoices/cm:documentLibrary";

        private final LogSpy anywhere = LogSpy.ofRoot();

        @BeforeEach
        void attach()
        {
            anywhere.attach();
        }

        @AfterEach
        void detach()
        {
            anywhere.detach();
        }

        @Test
        @DisplayName("scenario 1: the namespace is absent from the map -> bare name")
        void namespaceAbsentFromTheMapRendersAsBareName()
        {
            prefixMap.setPrefixUriMap(Map.of(CM_URI, "cm"));

            String rendered = renderInvoicesPathFor(CUSTOM_URI);

            assertThat(rendered).isEqualTo(EXPECTED_RENDERING);
        }

        @Test
        @DisplayName("scenario 2: the namespace maps to a blank prefix -> bare name, by design (ACS-12217)")
        void namespaceMappedToBlankPrefixRendersAsBareName()
        {
            prefixMap.setPrefixUriMap(Map.of(CM_URI, "cm", DEFAULT_NS_URI, ""));

            String rendered = renderInvoicesPathFor(DEFAULT_NS_URI);

            assertThat(rendered).isEqualTo(EXPECTED_RENDERING);
        }

        @Test
        @DisplayName("counts the namespace of an unprefixed path element")
        void countsTheNamespaceOfAnUnprefixedPathElement()
        {
            ReindexingMetrics metrics = mock(ReindexingMetrics.class);

            writePathContaining(childAssoc(CUSTOM_URI, "invoices"), metrics);

            verify(metrics).reindexUnresolvedNamespace(CUSTOM_URI);
        }

        @Test
        @DisplayName("reports the unprefixed path element, its namespace URI and its local name")
        void reportsTheUnprefixedPathElement()
        {
            prefixMap.setPrefixUriMap(Map.of(CM_URI, "cm"));

            writePathContaining(childAssoc(CUSTOM_URI, "invoices"));

            assertReportsLoss(anywhere, "path", "invoices");
        }

        private String renderInvoicesPathFor(String invoicesNamespaceUri)
        {
            Path path = new Path();
            path.add(childAssoc(CM_URI, "documentLibrary"));
            path.add(childAssoc(invoicesNamespaceUri, "invoices"));
            return path.asString(prefixMap.prefixUriMapper());
        }

        /** Drives the real writer, so the report can come from wherever the fix puts it. */
        private void writePathContaining(ChildAssocMetaData element)
        {
            writePathContaining(element, null);
        }

        private void writePathContaining(ChildAssocMetaData element, ReindexingMetrics metrics)
        {
            Path path = new Path();
            path.add(childAssoc(CM_URI, "documentLibrary"));
            path.add(element);

            try
            {
                ElasticsearchPathWriter writer = new ElasticsearchPathWriter(
                        elasticsearchProperties(), reindexProperties(), prefixMap, noOpElasticsearch(),
                        null, null, metrics);
                writer.open(new ExecutionContext());
                writer.write(Chunk.of(pathEvent(path)));
            }
            catch (Exception e)
            {
                throw new IllegalStateException(e);
            }
        }

        private ElasticsearchProperties elasticsearchProperties()
        {
            ElasticsearchProperties properties = mock(ElasticsearchProperties.class);
            when(properties.getIndexName()).thenReturn("alfresco");
            return properties;
        }

        private ReindexingCoreProperties reindexProperties()
        {
            ReindexingCoreProperties properties = mock(ReindexingCoreProperties.class);
            when(properties.getWriterRetryCount()).thenReturn(1);
            when(properties.getWriterRetryDelay()).thenReturn(1L);
            return properties;
        }

        private ElasticSearchUpdateOperations noOpElasticsearch() throws IOException, InterruptedException
        {
            ElasticSearchUpdateOperations operations = mock(ElasticSearchUpdateOperations.class);
            BulkRequestDto response = new BulkRequestDto();
            response.setNumberOfActions(1);
            response.setFailuresCount(0);
            when(operations.updateElasticSearchWithBulkRequests(any(), anyInt(), anyLong())).thenReturn(response);
            return operations;
        }

        private ReindexingEvent pathEvent(Path path)
        {
            NodeResource resource = NodeResource.builder().setId("workspace://SpacesStore/aaaa").build();
            EventData<NodeResource> data = EventData.<NodeResource> builder().setResource(resource).build();
            RepoEvent<EventData<NodeResource>> repoEvent = RepoEvent.<EventData<NodeResource>> builder()
                    .setType("org.alfresco.event.node.Updated")
                    .setData(data)
                    .setTime(ZonedDateTime.of(2026, 6, 1, 12, 0, 0, 0, ZoneOffset.UTC))
                    .setId("evt-1")
                    .build();
            return ReindexingEvent.builder()
                    .withRepoEvent(repoEvent)
                    .withPrimaryPath(path)
                    .withAllPaths(Set.of(path))
                    .withNodeDbId(1L)
                    .build();
        }

        private ChildAssocMetaData childAssoc(String uri, String name)
        {
            ChildAssocMetaData assoc = new ChildAssocMetaData();
            assoc.setUri(uri);
            assoc.setName(name);
            return assoc;
        }
    }

    /**
     * The one reporting contract all four cases meet: a WARN or worse naming what was lost, its namespace URI and its local name.
     */
    private static void assertReportsLoss(LogSpy log, String kind, String localName)
    {
        assertThat(log.formattedAtOrAbove(Level.WARN))
                .as("a WARN naming the %s, %s and %s", kind, CUSTOM_URI, localName)
                .anySatisfy(message -> assertThat(message)
                        .containsIgnoringCase(kind)
                        .contains(CUSTOM_URI)
                        .contains(localName));
    }

    // --- fixtures ---

    private ReindexingEvent process(AlfrescoNode node)
    {
        return new AlfrescoNodeToReindexingEventProcessor(prefixMap, Set.of()).process(node);
    }

    private static NodeResource resourceOf(ReindexingEvent event)
    {
        return (NodeResource) event.getData().getResource();
    }

    private static AlfrescoNode nodeWith(NodeProperty... properties)
    {
        AlfrescoNode node = mock(AlfrescoNode.class);
        when(node.getNodeProperties()).thenReturn(Set.of(properties));
        return node;
    }

    private static NodeProperty property(String uri, String localName, String value)
    {
        PropertyKey key = new PropertyKey();
        key.setUri(uri);
        key.setLocalName(localName);

        PropertyValue propertyValue = new PropertyValue();
        propertyValue.setPersistedType(PropertyValueType.STRING.getOrd());
        propertyValue.setStringValue(value);

        NodeProperty property = new NodeProperty();
        property.setPropertyKey(key);
        property.setPropertyValue(propertyValue);
        return property;
    }
}
