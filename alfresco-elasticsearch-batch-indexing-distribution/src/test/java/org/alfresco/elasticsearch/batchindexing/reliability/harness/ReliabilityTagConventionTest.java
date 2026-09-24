/*-
 * #%L
 * Alfresco Elasticsearch Connector :: Batch Indexing Distribution
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
package org.alfresco.elasticsearch.batchindexing.reliability.harness;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Enforces the reliability-IT convention: every {@code *IT} class under {@code reliability.<sub>/} carries a {@code @Tag} matching its subpackage. Keeps the CI matrix in {@code .github/workflows/ci.yml} and the on-disk layout in sync — a misplaced or untagged IT fails this test before it silently drops out of a matrix job.
 */
public class ReliabilityTagConventionTest
{
    private static final String RELIABILITY_PACKAGE = "org.alfresco.elasticsearch.batchindexing.reliability";
    private static final String RELIABILITY_RESOURCE = RELIABILITY_PACKAGE.replace('.', '/');
    private static final String HARNESS_SUBPACKAGE = "harness";

    /** Subpackage → expected tag value. Underscores in package names map to hyphens in tag names. */
    private static final Map<String, String> TAG_BY_SUBPACKAGE = Map.of(
            "lifecycle", "lifecycle",
            "infra_recovery", "infra-recovery",
            "poison", "poison",
            "capacity", "capacity");

    @Test
    void every_reliability_it_carries_the_tag_matching_its_subpackage() throws IOException, URISyntaxException, ClassNotFoundException
    {
        Path root = Paths.get(getClass().getProtectionDomain().getCodeSource().getLocation().toURI())
                .resolve(RELIABILITY_RESOURCE);

        assertThat(root).as("compiled test-classes root for %s", RELIABILITY_PACKAGE).isDirectory();

        List<String> violations = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(root))
        {
            List<Path> classFiles = paths
                    .filter(p -> p.getFileName().toString().endsWith("IT.class"))
                    .filter(p -> !p.getFileName().toString().contains("$"))
                    .filter(p -> !root.relativize(p).getName(0).toString().equals(HARNESS_SUBPACKAGE))
                    .collect(Collectors.toList());

            for (Path classFile : classFiles)
            {
                Path relative = root.relativize(classFile);
                String subpackage = relative.getName(0).toString();
                String expectedTag = TAG_BY_SUBPACKAGE.get(subpackage);
                if (expectedTag == null)
                {
                    violations.add(classFile.getFileName() + " lives in unrecognised subpackage '" + subpackage
                            + "'; add it to " + TAG_BY_SUBPACKAGE.keySet() + " or move the class.");
                    continue;
                }

                String fqcn = RELIABILITY_PACKAGE + '.' + relative.toString()
                        .replace('/', '.')
                        .replace('\\', '.')
                        .replaceAll("\\.class$", "");
                Class<?> clazz = Class.forName(fqcn, false, getClass().getClassLoader());
                Tag[] tags = clazz.getAnnotationsByType(Tag.class);
                List<String> tagValues = Arrays.stream(tags).map(Tag::value).collect(Collectors.toList());
                if (!tagValues.contains(expectedTag))
                {
                    violations.add(fqcn + " is under '" + subpackage + "/' but does not carry @Tag(\""
                            + expectedTag + "\"); found tags=" + tagValues);
                }
            }
        }

        assertThat(violations).as("reliability IT tag/subpackage mismatches").isEmpty();
    }
}
