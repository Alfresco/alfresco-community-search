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
package org.alfresco.reindexing.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import org.alfresco.database.connector.model.QName;
import org.alfresco.metrics.ReindexingMetrics;
import org.alfresco.reindexing.config.PrefixNamespaceConfig;
import org.alfresco.reindexing.util.QNamePrefixResolver.PrefixedItem;

/**
 * Naming of {@link QName}s, shared by the indexing path and the folder subtree report.
 */
class QNamePrefixResolverTest
{
    private static final String CM_URI = "http://www.alfresco.org/model/content/1.0";
    private static final String CUSTOM_URI = "http://example.com/custom";

    private PrefixNamespaceConfig config;
    private QNamePrefixResolver resolver;
    private ReindexingMetrics reindexingMetrics;

    @BeforeEach
    void setUp()
    {
        config = new PrefixNamespaceConfig();
        config.setPrefixUriMap(Map.of(CM_URI, "cm"));
        reindexingMetrics = mock(ReindexingMetrics.class);
        resolver = new QNamePrefixResolver(config, reindexingMetrics);
    }

    @Test
    @DisplayName("resolves a registered namespace to prefix:localName")
    void resolvesRegisteredNamespace()
    {
        assertThat(resolver.prefixedName(QName.newTransientInstance(CM_URI, "content")))
                .contains("cm:content");
    }

    @Test
    @DisplayName("QName has no toString, which is what made the raw value unusable")
    void qnameHasNoUsefulToString()
    {
        QName type = QName.newTransientInstance(CM_URI, "content");

        // Pins the reason this class exists. QName overrides equals/hashCode but not toString, so String.valueOf() produced 'org.alfresco.database.connector.model.QName@1f2e3d4c' in the subtree report.
        assertThat(String.valueOf(type)).startsWith(QName.class.getName() + "@");
        assertThat(resolver.describe(type)).isEqualTo("cm:content");
    }

    @Test
    @DisplayName("an unregistered namespace has no prefixed name")
    void unregisteredNamespaceIsEmpty()
    {
        assertThat(resolver.prefixedName(QName.newTransientInstance("http://example.com/custom", "thing")))
                .isEmpty();
    }

    @Test
    @DisplayName("describe falls back to the canonical long form rather than dropping the type")
    void describeFallsBackToLongForm()
    {
        // A report accounts for every node, so losing a whole type would make the totals disagree
        // with the histogram. The long form still identifies it and makes the gap obvious.
        assertThat(resolver.describe(QName.newTransientInstance("http://example.com/custom", "thing")))
                .isEqualTo("{http://example.com/custom}thing");
    }

    @Test
    @DisplayName("describe tolerates a null type")
    void describeHandlesNull()
    {
        assertThat(resolver.describe(null)).isEqualTo("(unknown)");
        assertThat(resolver.prefixedName(null)).isEmpty();
    }

    @Test
    @DisplayName("picks up prefixes registered after construction")
    void reflectsLaterPrefixUpdates()
    {
        QName custom = QName.newTransientInstance(CUSTOM_URI, "thing");
        assertThat(resolver.describe(custom)).isEqualTo("{http://example.com/custom}thing");

        // PrefixNamespaceConfig starts from a bundled fallback and is replaced with live API data
        // on ApplicationStartedEvent. Caching the map at construction would pin the fallback.
        config.setPrefixUriMap(Map.of(CUSTOM_URI, "ex"));

        assertThat(resolver.describe(custom)).isEqualTo("ex:thing");
    }

    @Test
    @DisplayName("records the namespace of an unresolved qname")
    void recordsUnresolvedNamespace()
    {
        resolver.prefixedName(QName.newTransientInstance(CUSTOM_URI, "thing"));

        verify(reindexingMetrics).reindexUnresolvedNamespace(CUSTOM_URI);
    }

    @Test
    @DisplayName("records nothing when the namespace resolves")
    void recordsNothingWhenResolved()
    {
        resolver.prefixedName(QName.newTransientInstance(CM_URI, "content"));

        verify(reindexingMetrics, never()).reindexUnresolvedNamespace(any());
    }

    @Test
    @DisplayName("works without metrics, which are not wired in every context")
    void toleratesAbsentMetrics()
    {
        QNamePrefixResolver withoutMetrics = new QNamePrefixResolver(config, null);

        assertThatCode(() -> withoutMetrics.prefixedName(QName.newTransientInstance(CUSTOM_URI, "thing")))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @EnumSource(PrefixedItem.class)
    @DisplayName("the indexing overload reports the same namespace whatever kind of item was lost")
    void recordsUnresolvedNamespaceForEveryItemKind(PrefixedItem item)
    {
        // The kind only sharpens the log line. Counting it here, rather than at each call site, is
        // the point of centralising: the metric had twice been forgotten when callers resolved names
        // themselves.
        assertThat(resolver.prefixedName(QName.newTransientInstance(CUSTOM_URI, "thing"), item)).isEmpty();

        verify(reindexingMetrics).reindexUnresolvedNamespace(CUSTOM_URI);
    }

    @ParameterizedTest
    @EnumSource(PrefixedItem.class)
    @DisplayName("the indexing overload resolves exactly as the plain one does")
    void itemKindDoesNotChangeTheResolvedName(PrefixedItem item)
    {
        assertThat(resolver.prefixedName(QName.newTransientInstance(CM_URI, "content"), item))
                .contains("cm:content");

        verify(reindexingMetrics, never()).reindexUnresolvedNamespace(any());
    }

    @Test
    @DisplayName("the indexing overload tolerates a null qname")
    void indexingOverloadHandlesNull()
    {
        assertThat(resolver.prefixedName(null, PrefixedItem.TYPE)).isEmpty();

        // Nothing to report: a null qname is not an unmapped namespace.
        verify(reindexingMetrics, never()).reindexUnresolvedNamespace(any());
    }
}
