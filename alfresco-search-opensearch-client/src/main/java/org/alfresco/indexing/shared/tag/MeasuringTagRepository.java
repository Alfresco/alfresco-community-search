/*-
 * #%L
 * Alfresco Search Community :: OpenSearch Client
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
package org.alfresco.indexing.shared.tag;

import java.util.Objects;
import java.util.Optional;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

public class MeasuringTagRepository implements TagRepository
{

    private final TagRepository repoToMeasure;
    private final Timer executionTimer;
    private final Counter failCounter;

    public MeasuringTagRepository(TagRepository repoToMeasure, final MeterRegistry meterRegistry)
    {
        this(repoToMeasure, meterRegistry, "alfresco.indexing.get.tag.name.");
    }

    public MeasuringTagRepository(TagRepository repoToMeasure, final MeterRegistry meterRegistry, final String metricNamePrefix)
    {
        this.repoToMeasure = Objects.requireNonNull(repoToMeasure);
        Objects.requireNonNull(meterRegistry);
        Objects.requireNonNull(metricNamePrefix);

        this.executionTimer = meterRegistry.timer(metricNamePrefix + "timer");
        this.failCounter = meterRegistry.counter(metricNamePrefix + "fails");
    }

    @Override
    public Optional<String> getTagName(final String tagId)
    {
        try
        {
            return executionTimer.record(() -> repoToMeasure.getTagName(tagId));
        }
        catch (RuntimeException ex)
        {
            failCounter.increment();
            throw ex;
        }
    }
}
