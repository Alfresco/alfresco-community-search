/*-
 * #%L
 * Alfresco Search Community :: Batch Indexing Distribution
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

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Common base for all reliability integration tests in this module. Applies the {@code RELIABILITY_TESTS=true} env-var gate so subclasses inherit it, and wires the {@link ReliabilityDiagnosticsExtension} so every failing test automatically dumps batch-indexing container state and log tail — no per-test try/catch needed. Each concrete IT still builds its own {@link org.testcontainers.containers.ComposeContainer} and adds a per-package {@link org.junit.jupiter.api.Tag} matching its CI job name.
 */
@EnabledIfEnvironmentVariable(named = "RELIABILITY_TESTS", matches = "true")
@ExtendWith(ReliabilityDiagnosticsExtension.class)
public class BaseReliabilityIT
{
    protected BaseReliabilityIT()
    {}
}
