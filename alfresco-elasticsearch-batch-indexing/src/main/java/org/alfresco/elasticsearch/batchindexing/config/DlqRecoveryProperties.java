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

import jakarta.annotation.PostConstruct;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for the {@code reindexDlq} one-shot drain.
 */
@Getter
@Setter
@ToString
@Configuration
@ConfigurationProperties(prefix = "alfresco.reindex.recovery.dlq")
public class DlqRecoveryProperties
{
    /** Delete the DLQ doc after a node reindexes cleanly (nothing re-records a failure for it during the run). */
    private boolean deleteOnSuccess = true;

    /** Cap on the number of DLQ documents scanned per run. A warning is logged if the cap is hit. */
    private int maxItems = 10_000;

    @PostConstruct
    public void validate()
    {
        if (maxItems <= 0)
        {
            throw new IllegalArgumentException(
                    "alfresco.reindex.recovery.dlq.maxItems must be > 0 but was " + maxItems);
        }
    }
}
