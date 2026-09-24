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

import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * SDK-free selection and edition validation for the two supported search authentication modes.
 */
public final class SearchEngineAuthentication
{
    /** Supplied by the Enterprise IAM auto-configuration, independently of client backoff. */
    public interface AwsIamCapability
    {}

    public SearchEngineAuthentication(SearchEngineProperties properties, Environment environment, boolean awsIamAvailable)
    {
        properties.setAuthMode(resolveAuthMode(environment));
        Binder binder = Binder.get(environment);
        if (!awsIamAvailable && (SearchEngineProperties.AUTH_MODE_AWS_IAM.equals(properties.getAuthMode())
                || environment.containsProperty("spring.elasticsearch.aws-region")
                || environment.containsProperty("spring.elasticsearch.aws-service")
                || binder.bind("spring.elasticsearch.aws-region", String.class).isBound()
                || binder.bind("spring.elasticsearch.aws-service", String.class).isBound()))
        {
            throw new IllegalStateException(
                    "AWS IAM authentication and spring.elasticsearch.aws-region/aws-service require Enterprise");
        }
    }

    private static String resolveAuthMode(Environment environment)
    {
        // Retain the documented AUTH_MODE environment spelling as well as Boot's AUTHMODE spelling.
        return SearchEngineProperties.normalizeAuthMode(Binder.get(environment)
                .bind("spring.elasticsearch.auth-mode", String.class)
                .orElseGet(() -> environment.getProperty("spring.elasticsearch.auth-mode", SearchEngineProperties.DEFAULT_AUTH_MODE)));
    }

    private abstract static class AuthModeCondition extends SpringBootCondition
    {
        private final String mode;

        AuthModeCondition(String mode)
        {
            super();
            this.mode = mode;
        }

        @Override
        public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata)
        {
            return new ConditionOutcome(mode.equals(resolveAuthMode(context.getEnvironment())),
                    "Search authentication mode selection");
        }
    }

    public static final class BasicCondition extends AuthModeCondition
    {
        public BasicCondition()
        {
            super(SearchEngineProperties.AUTH_MODE_BASIC);
        }
    }

    public static final class AwsIamCondition extends AuthModeCondition
    {
        public AwsIamCondition()
        {
            super(SearchEngineProperties.AUTH_MODE_AWS_IAM);
        }
    }
}
