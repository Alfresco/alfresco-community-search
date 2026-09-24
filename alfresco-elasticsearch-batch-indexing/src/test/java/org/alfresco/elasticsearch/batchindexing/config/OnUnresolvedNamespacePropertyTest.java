/*-
 * #%L
 * Alfresco Elasticsearch Connector :: Batch Indexing Module
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
package org.alfresco.elasticsearch.batchindexing.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import org.alfresco.reindexing.config.ReindexingCoreProperties;
import org.alfresco.reindexing.config.UnresolvedNamespaceAction;

/**
 * Binding of {@code alfresco.reindex.onUnresolvedNamespace}. Both shipped {@code application.properties} files set it in lower case, so relaxed binding to the enum has to hold.
 */
@DisplayName("alfresco.reindex.onUnresolvedNamespace binding")
// Asserts run inside the ApplicationContextRunner callback, which PMD cannot see.
@SuppressWarnings("PMD.UnitTestShouldIncludeAssert")
class OnUnresolvedNamespacePropertyTest
{
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Binding.class);

    /** ApplicationContextRunner does not enable @ConfigurationProperties binding on its own. */
    @EnableConfigurationProperties(ReindexingCoreProperties.class)
    static class Binding
    {}

    @Test
    @DisplayName("defaults to filter when unset")
    void defaultsToFilter()
    {
        runner.run(context -> assertThat(actionOf(context)).isEqualTo(UnresolvedNamespaceAction.FILTER));
    }

    @Test
    @DisplayName("binds the lower-case values the shipped properties use")
    void bindsLowerCaseValues()
    {
        assertBinds("filter", UnresolvedNamespaceAction.FILTER);
        assertBinds("skip", UnresolvedNamespaceAction.SKIP);
        assertBinds("stop", UnresolvedNamespaceAction.STOP);
    }

    @Test
    @DisplayName("binds regardless of case")
    void bindsRegardlessOfCase()
    {
        assertBinds("SKIP", UnresolvedNamespaceAction.SKIP);
        assertBinds("Stop", UnresolvedNamespaceAction.STOP);
    }

    @Test
    @DisplayName("an unrecognised value stops startup and names the property")
    void unrecognisedValueStopsStartup()
    {
        runner.withPropertyValues("alfresco.reindex.onUnresolvedNamespace=banana")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasStackTraceContaining("on-unresolved-namespace")
                        .hasStackTraceContaining("UnresolvedNamespaceAction"));
    }

    @Test
    @DisplayName("a blank value leaves the default in place rather than binding null")
    void blankValueLeavesTheDefault()
    {
        runner.withPropertyValues("alfresco.reindex.onUnresolvedNamespace=")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    // A null here would NPE in the processor's action check.
                    assertThat(actionOf(context)).isEqualTo(UnresolvedNamespaceAction.FILTER);
                });
    }

    private void assertBinds(String value, UnresolvedNamespaceAction expected)
    {
        runner.withPropertyValues("alfresco.reindex.onUnresolvedNamespace=" + value)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(actionOf(context)).isEqualTo(expected);
                });
    }

    private static UnresolvedNamespaceAction actionOf(org.springframework.context.ApplicationContext context)
    {
        return context.getBean(ReindexingCoreProperties.class).getOnUnresolvedNamespace();
    }
}
