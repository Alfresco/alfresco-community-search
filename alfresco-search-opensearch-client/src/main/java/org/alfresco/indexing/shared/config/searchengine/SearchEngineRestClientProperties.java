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
package org.alfresco.indexing.shared.config.searchengine;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "spring.elasticsearch.restclient")
public class SearchEngineRestClientProperties
{
    private final Sniffer sniffer = new Sniffer();

    public Sniffer getSniffer()
    {
        return this.sniffer;
    }

    public static class Sniffer
    {

        /**
         * Whether the sniffer is enabled.
         */
        private boolean enabled = true;

        /**
         * Interval between consecutive ordinary sniff executions.
         */
        private Duration interval = Duration.ofMinutes(5);

        /**
         * Delay of a sniff execution scheduled after a failure.
         */
        private Duration delayAfterFailure = Duration.ofMinutes(1);

        /**
         * Request timeout for the sniff request.
         */
        private Duration requestTimeout = Duration.ofSeconds(1);

        public boolean isEnabled()
        {
            return this.enabled;
        }

        public void setEnabled(boolean enabled)
        {
            this.enabled = enabled;
        }

        public Duration getInterval()
        {
            return this.interval;
        }

        public void setInterval(Duration interval)
        {
            this.interval = interval;
        }

        public Duration getDelayAfterFailure()
        {
            return this.delayAfterFailure;
        }

        public void setDelayAfterFailure(Duration delayAfterFailure)
        {
            this.delayAfterFailure = delayAfterFailure;
        }

        public Duration getRequestTimeout()
        {
            return this.requestTimeout;
        }

        public void setRequestTimeout(Duration requestTimeout)
        {
            this.requestTimeout = requestTimeout;
        }
    }
}
