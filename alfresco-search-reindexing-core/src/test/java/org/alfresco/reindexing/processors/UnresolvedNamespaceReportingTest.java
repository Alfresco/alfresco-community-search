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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.TYPE_DELETED;

import java.util.Map;
import java.util.Set;

import ch.qos.logback.classic.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import org.alfresco.database.connector.model.AlfrescoNode;
import org.alfresco.database.connector.model.NodeProperty;
import org.alfresco.database.connector.model.PropertyKey;
import org.alfresco.database.connector.model.PropertyValue;
import org.alfresco.database.connector.model.PropertyValueType;
import org.alfresco.database.connector.model.QName;
import org.alfresco.metrics.ReindexingMetrics;
import org.alfresco.reindexing.config.PrefixNamespaceConfig;
import org.alfresco.reindexing.config.UnresolvedNamespaceAction;
import org.alfresco.reindexing.model.ReindexingEvent;
import org.alfresco.repo.event.v1.model.NodeResource;

/**
 * What is recorded when a namespace has no prefix mapping (ACS-12851): a metric for every kind, and a recoverable record for a lost node.
 */
@DisplayName("Reporting an unresolved namespace")
class UnresolvedNamespaceReportingTest
{
    private static final String CM_URI = "http://www.alfresco.org/model/content/1.0";
    private static final String CUSTOM_URI = "http://acme.example.org/model/custom/1.0";

    private PrefixNamespaceConfig prefixMap;
    private ReindexingMetrics metrics;
    private UnresolvedNamespaceListener listener;

    @BeforeEach
    void setUp()
    {
        prefixMap = new PrefixNamespaceConfig();
        prefixMap.setPrefixUriMap(Map.of(CM_URI, "cm"));
        metrics = mock(ReindexingMetrics.class);
        listener = mock(UnresolvedNamespaceListener.class);
    }

    @Nested
    @DisplayName("the metric")
    class Metric
    {
        @Test
        @DisplayName("counts the namespace of an unresolved property")
        void countsAnUnresolvedProperty()
        {
            process(nodeWith(property(CUSTOM_URI, "invoiceNumber")));

            verify(metrics).reindexUnresolvedNamespace(CUSTOM_URI);
        }

        @Test
        @DisplayName("counts the namespace of an unresolved aspect")
        void countsAnUnresolvedAspect()
        {
            AlfrescoNode node = nodeWith();
            when(node.getAspects()).thenReturn(Set.of(QName.newTransientInstance(CUSTOM_URI, "invoiceable")));

            process(node);

            verify(metrics).reindexUnresolvedNamespace(CUSTOM_URI);
        }

        @Test
        @DisplayName("counts the namespace of an unresolved type")
        void countsAnUnresolvedType()
        {
            process(nodeOfCustomType());

            verify(metrics).reindexUnresolvedNamespace(CUSTOM_URI);
        }

        @Test
        @DisplayName("is not touched when every namespace resolves")
        void isNotTouchedWhenEverythingResolves()
        {
            process(nodeWith(property(CM_URI, "name")));

            verify(metrics, never()).reindexUnresolvedNamespace(any());
        }
    }

    @Nested
    @DisplayName("the dead-letter hook")
    class DeadLetterHook
    {
        @Test
        @DisplayName("is notified with the node, the namespace URI and the type name")
        void isNotifiedForALostNode()
        {
            AlfrescoNode node = nodeOfCustomType();
            when(node.getId()).thenReturn(4242L);
            when(node.getNodeRef()).thenReturn("workspace://SpacesStore/abcd");

            process(node);

            verify(listener).onUnresolvedNamespace(eq(4242L), eq("workspace://SpacesStore/abcd"),
                    any(), eq(CUSTOM_URI), eq("invoice"));
        }

        @Test
        @DisplayName("is not notified for a property, which only makes the document incomplete")
        void isNotNotifiedForAProperty()
        {
            process(nodeWith(property(CUSTOM_URI, "invoiceNumber")));

            verify(listener, never()).onUnresolvedNamespace(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("is not notified when the type resolves")
        void isNotNotifiedWhenTheTypeResolves()
        {
            AlfrescoNode node = nodeWith();
            when(node.getType()).thenReturn(QName.newTransientInstance(CM_URI, "content"));

            process(node);

            verify(listener, never()).onUnresolvedNamespace(any(), any(), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("onUnresolvedNamespace")
    class Action
    {
        @Test
        @DisplayName("filter drops the node without failing the item")
        void filterDropsTheNodeWithoutFailing()
        {
            assertThatCode(() -> process(nodeOfCustomType(), UnresolvedNamespaceAction.FILTER))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("skip fails the item, naming the namespace and the type")
        void skipFailsTheItem()
        {
            assertThatThrownBy(() -> process(nodeOfCustomType(), UnresolvedNamespaceAction.SKIP))
                    .isInstanceOf(UnresolvedNamespaceException.class)
                    .hasMessageContaining(CUSTOM_URI)
                    .hasMessageContaining("invoice");
        }

        @Test
        @DisplayName("skip still records the node before failing it")
        void skipStillRecordsTheNode()
        {
            AlfrescoNode node = nodeOfCustomType();
            when(node.getId()).thenReturn(99L);

            assertThatThrownBy(() -> process(node, UnresolvedNamespaceAction.SKIP))
                    .isInstanceOf(UnresolvedNamespaceException.class);

            verify(listener).onUnresolvedNamespace(eq(99L), any(), any(), eq(CUSTOM_URI), eq("invoice"));
        }

        @Test
        @DisplayName("skip leaves a resolvable node alone")
        void skipLeavesAResolvableNodeAlone()
        {
            AlfrescoNode node = nodeWith();
            when(node.getType()).thenReturn(QName.newTransientInstance(CM_URI, "content"));

            assertThatCode(() -> process(node, UnresolvedNamespaceAction.SKIP)).doesNotThrowAnyException();
        }
    }

    /**
     * An unresolved namespace is expected, deterministic and already reported at WARN with the URI, the kind and the name. The generic catch-all in {@code process()} must not add a second line at ERROR with a stack trace on top of it, or the quieter default mode and the louder ones disagree about severity for identical data loss.
     */
    @Nested
    @DisplayName("log noise")
    class LogNoise
    {
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
        @DisplayName("filter reports the loss without logging an ERROR")
        void filterLogsNoError()
        {
            process(nodeOfCustomType(), UnresolvedNamespaceAction.FILTER);

            assertThat(anywhere.formattedAtOrAbove(Level.WARN)).isNotEmpty();
            assertThat(anywhere.formattedAtOrAbove(Level.ERROR)).isEmpty();
        }

        @Test
        @DisplayName("skip reports the same loss, without adding an ERROR")
        void skipLogsNoError()
        {
            assertThatThrownBy(() -> process(nodeOfCustomType(), UnresolvedNamespaceAction.SKIP))
                    .isInstanceOf(UnresolvedNamespaceException.class);

            assertThat(anywhere.formattedAtOrAbove(Level.ERROR)).isEmpty();
        }

        @Test
        @DisplayName("stop reports the same loss, without adding an ERROR")
        void stopLogsNoError()
        {
            assertThatThrownBy(() -> process(nodeOfCustomType(), UnresolvedNamespaceAction.STOP))
                    .isInstanceOf(UnresolvedNamespaceException.class);

            assertThat(anywhere.formattedAtOrAbove(Level.ERROR)).isEmpty();
        }

        @Test
        @DisplayName("an unexpected failure is still logged at ERROR")
        void unexpectedFailureStillLogsAnError()
        {
            AlfrescoNode exploding = nodeOfCustomType();
            when(exploding.getNodeProperties()).thenThrow(new IllegalStateException("boom"));

            assertThatThrownBy(() -> process(exploding, UnresolvedNamespaceAction.FILTER))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(anywhere.formattedAtOrAbove(Level.ERROR)).isNotEmpty();
        }
    }

    @Nested
    @DisplayName("a node marked deleted")
    class DeletedNode
    {
        @Test
        @DisplayName("is still emitted as a delete when its type namespace is unresolved")
        void isStillEmittedAsADelete()
        {
            ReindexingEvent event = processReturning(deletedNodeOfCustomType(), UnresolvedNamespaceAction.SKIP);

            assertThat(nodeTypeOf(event)).isEqualTo(TYPE_DELETED);
        }

        @Test
        @DisplayName("is not failed by skip, which would leave it in the index")
        void isNotFailedBySkip()
        {
            assertThatCode(() -> process(deletedNodeOfCustomType(), UnresolvedNamespaceAction.SKIP))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("is not failed by stop")
        void isNotFailedByStop()
        {
            assertThatCode(() -> process(deletedNodeOfCustomType(), UnresolvedNamespaceAction.STOP))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("is not dead-lettered, since the delete is indexed")
        void isNotDeadLettered()
        {
            process(deletedNodeOfCustomType());

            verify(listener, never()).onUnresolvedNamespace(any(), any(), any(), any(), any());
        }
    }

    private static String nodeTypeOf(ReindexingEvent event)
    {
        return ((NodeResource) event.getRepoEvent().getData().getResource()).getNodeType();
    }

    private AlfrescoNode deletedNodeOfCustomType()
    {
        AlfrescoNode node = nodeOfCustomType();
        when(node.hasBeenMarkedAsDeleted()).thenReturn(true);
        return node;
    }

    private void process(AlfrescoNode node)
    {
        process(node, UnresolvedNamespaceAction.FILTER);
    }

    private void process(AlfrescoNode node, UnresolvedNamespaceAction action)
    {
        processReturning(node, action);
    }

    private ReindexingEvent processReturning(AlfrescoNode node, UnresolvedNamespaceAction action)
    {
        return new AlfrescoNodeToReindexingEventProcessor(prefixMap, metrics, listener, action, Set.of()).process(node);
    }

    private AlfrescoNode nodeOfCustomType()
    {
        AlfrescoNode node = nodeWith();
        when(node.getType()).thenReturn(QName.newTransientInstance(CUSTOM_URI, "invoice"));
        return node;
    }

    private static AlfrescoNode nodeWith(NodeProperty... properties)
    {
        AlfrescoNode node = mock(AlfrescoNode.class);
        when(node.getNodeProperties()).thenReturn(Set.of(properties));
        return node;
    }

    private static NodeProperty property(String uri, String localName)
    {
        PropertyKey key = new PropertyKey();
        key.setUri(uri);
        key.setLocalName(localName);

        PropertyValue value = new PropertyValue();
        value.setPersistedType(PropertyValueType.STRING.getOrd());
        value.setStringValue("whatever");

        NodeProperty nodeProperty = new NodeProperty();
        nodeProperty.setPropertyKey(key);
        nodeProperty.setPropertyValue(value);
        return nodeProperty;
    }
}
