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

import java.util.Locale;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.alfresco.database.connector.model.QName;
import org.alfresco.metrics.ReindexingMetrics;
import org.alfresco.reindexing.config.PrefixNamespaceConfig;

/**
 * Turns a {@link QName} into its {@code prefix:localName} form, and is the single place that decides what happens when a namespace URI has no registered prefix.
 */
public class QNamePrefixResolver
{
    private static final Logger LOGGER = LoggerFactory.getLogger(QNamePrefixResolver.class);

    private final PrefixNamespaceConfig prefixNamespaceConfig;
    /** Optional. Nullable in tests; wired in production via SharedBatchProcessingConfig. */
    private final ReindexingMetrics reindexingMetrics;

    public QNamePrefixResolver(PrefixNamespaceConfig prefixNamespaceConfig, ReindexingMetrics reindexingMetrics)
    {
        this.prefixNamespaceConfig = prefixNamespaceConfig;
        this.reindexingMetrics = reindexingMetrics;
    }

    /** Names the kind of thing being resolved so an unmapped namespace can be reported precisely. */
    public enum PrefixedItem
    {
        PROPERTY, ASPECT, TYPE;

        String label()
        {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * @return {@code prefix:localName}, or empty when the qname is {@code null} or its namespace URI has no registered prefix
     */
    public Optional<String> prefixedName(QName qname)
    {
        return prefixedName(qname, null);
    }

    /**
     * Resolves a name that is about to be indexed. An unmapped namespace is logged as the loss it is, naming the kind of item so the warning identifies what was dropped.
     *
     * @return {@code prefix:localName}, or empty when the namespace URI has no registered prefix
     */
    public Optional<String> prefixedName(String uri, String localName, PrefixedItem item)
    {
        String prefix = prefixNamespaceConfig.prefixUriMapper().getUriMapping(uri);
        if (prefix == null)
        {
            report(uri, localName, item);
            return Optional.empty();
        }
        return Optional.of(prefix + ":" + localName);
    }

    /**
     * @return {@code prefix:localName}, or empty when the qname is {@code null} or its namespace URI has no registered prefix
     */
    public Optional<String> prefixedName(QName qname, PrefixedItem item)
    {
        return Optional.ofNullable(qname).flatMap(qn -> prefixedName(qn.getUri(), qn.getLocalName(), item));
    }

    private void report(String uri, String localName, PrefixedItem item)
    {
        if (item == null)
        {
            LOGGER.warn("No prefix mapping for namespace {}; {} cannot be named", uri, localName);
        }
        else
        {
            LOGGER.warn("No prefix mapping for namespace {}; {} {} cannot be indexed", uri, item.label(), localName);
        }

        if (reindexingMetrics != null)
        {
            reindexingMetrics.reindexUnresolvedNamespace(uri);
        }
    }

    /**
     * Always produces something readable, for logs and reports where losing the entry is worse than naming it awkwardly.
     */
    public String describe(QName qname)
    {
        if (qname == null)
        {
            return "(unknown)";
        }
        return prefixedName(qname).orElseGet(() -> "{" + qname.getUri() + "}" + qname.getLocalName());
    }
}
