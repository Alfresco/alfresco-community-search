/*-
 * #%L
 * Alfresco Search Community :: Batch Indexing
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import org.alfresco.reindexing.config.PrefixNamespaceConfig;

/**
 * The operator's escape hatch for a missing namespace (ACS-12851): point {@code alfresco.reindex.prefixes-file} at an edited map and restart.
 * <p>
 * Documented at {@code README.md} and set by batch-indexing's own {@code application.properties}, so it must take effect in this application's context.
 */
@DisplayName("alfresco.reindex.prefixes-file override in batch-indexing")
// Asserts run inside the ApplicationContextRunner callback, which PMD cannot see.
@SuppressWarnings("PMD.UnitTestShouldIncludeAssert")
class PrefixesFileOverrideTest
{
    private static final String CM_URI = "http://www.alfresco.org/model/content/1.0";
    private static final String CUSTOM_URI = "http://acme.example.org/model/custom/1.0";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PrefixNamespaceConfig.class);

    @Test
    @DisplayName("with no override configured the bundled Alfresco namespaces resolve")
    void bundledNamespacesResolveByDefault()
    {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(mapperOf(context).getUriMapping(CM_URI)).isEqualTo("cm");
        });
    }

    @Test
    @DisplayName("a custom namespace is absent from the bundled map, which is what makes the override necessary")
    void customNamespaceIsAbsentFromTheBundledMap()
    {
        runner.run(context -> assertThat(mapperOf(context).getUriMapping(CUSTOM_URI)).isNull());
    }

    @Test
    @DisplayName("an operator-supplied file resolves the custom namespace after a restart")
    void operatorSuppliedFileResolvesTheCustomNamespace(@TempDir Path tempDir) throws IOException
    {
        Path prefixesFile = writeMap(tempDir, CM_URI, "cm", CUSTOM_URI, "acme");

        runner.withPropertyValues("alfresco.reindex.prefixes-file=file:" + prefixesFile)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(mapperOf(context).getUriMapping(CUSTOM_URI)).isEqualTo("acme");
                });
    }

    @Test
    @DisplayName("an operator-supplied file keeps the Alfresco namespaces it carries")
    void operatorSuppliedFileKeepsAlfrescoNamespaces(@TempDir Path tempDir) throws IOException
    {
        Path prefixesFile = writeMap(tempDir, CM_URI, "cm", CUSTOM_URI, "acme");

        runner.withPropertyValues("alfresco.reindex.prefixes-file=file:" + prefixesFile)
                .run(context -> assertThat(mapperOf(context).getUriMapping(CM_URI)).isEqualTo("cm"));
    }

    @Test
    @DisplayName("a blank setting falls back to the bundled map, as the reindexing app configures it")
    void blankSettingFallsBackToTheBundledMap()
    {
        runner.withPropertyValues("alfresco.reindex.prefixes-file=")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(mapperOf(context).getUriMapping(CM_URI)).isEqualTo("cm");
                });
    }

    @Test
    @DisplayName("a map resolved at runtime still wins over the file, keeping the reindexing app's precedence")
    void resolvedMapStillWinsOverTheFile(@TempDir Path tempDir) throws IOException
    {
        Path prefixesFile = writeMap(tempDir, CM_URI, "cm", CUSTOM_URI, "acme");

        runner.withPropertyValues("alfresco.reindex.prefixes-file=file:" + prefixesFile)
                .run(context -> {
                    PrefixNamespaceConfig config = context.getBean(PrefixNamespaceConfig.class);
                    config.setPrefixUriMap(Map.of(CUSTOM_URI, "fromApi"));

                    assertThat(config.prefixUriMapper().getUriMapping(CUSTOM_URI)).isEqualTo("fromApi");
                });
    }

    @Test
    @DisplayName("the setting can be supplied as an environment variable, as a container deployment does")
    void theSettingCanBeSuppliedAsAnEnvironmentVariable(@TempDir Path tempDir) throws IOException
    {
        Path prefixesFile = writeMap(tempDir, CM_URI, "cm", CUSTOM_URI, "acme");

        runner.withInitializer(context -> context.getEnvironment().getPropertySources()
                .addFirst(new SystemEnvironmentPropertySource("systemEnvironment",
                        Map.of("ALFRESCO_REINDEX_PREFIXESFILE", "file:" + prefixesFile))))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(mapperOf(context).getUriMapping(CUSTOM_URI)).isEqualTo("acme");
                });
    }

    @Test
    @DisplayName("a configured file that cannot be read stops startup and names the location")
    void unreadableConfiguredFileStopsStartup(@TempDir Path tempDir)
    {
        Path missing = tempDir.resolve("nope.json");

        runner.withPropertyValues("alfresco.reindex.prefixes-file=file:" + missing)
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasStackTraceContaining(missing.toString()));
    }

    private static org.alfresco.database.connector.PrefixUriMapper mapperOf(
            org.springframework.context.ApplicationContext context)
    {
        return context.getBean(PrefixNamespaceConfig.class).prefixUriMapper();
    }

    private static Path writeMap(Path dir, String... uriPrefixPairs) throws IOException
    {
        StringBuilder json = new StringBuilder("{\n  \"prefixUriMap\": {\n");
        for (int i = 0; i < uriPrefixPairs.length; i += 2)
        {
            json.append("    \"").append(uriPrefixPairs[i]).append("\": \"").append(uriPrefixPairs[i + 1])
                    .append('"')
                    .append(i + 2 < uriPrefixPairs.length ? ",\n" : "\n");
        }
        json.append("  }\n}\n");

        Path file = dir.resolve("reindex.prefixes-file.json");
        Files.writeString(file, json.toString());
        return file;
    }
}
