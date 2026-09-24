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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.sniff.Sniffer;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;

@SuppressWarnings("PMD.UnitTestShouldIncludeAssert")
class SearchEngineRestClientSnifferConfigurationTest
{
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(BootConfiguration.class);

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class BootConfiguration
    {}

    @Test
    void defaultClientAndSniffer()
    {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(OpenSearchClient.class).hasSingleBean(Sniffer.class);
            assertThat(context.getBean(SearchEngineRestClientProperties.class).getSniffer().getRequestTimeout())
                    .isEqualTo(Duration.ofSeconds(1));
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void snifferCanBeEnabledOrDisabled(boolean enabled)
    {
        contextRunner.withPropertyValues("spring.elasticsearch.restclient.sniffer.enabled=" + enabled)
                .run(context -> assertThat(context.containsBean("elasticsearchSniffer")).isEqualTo(enabled));
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 5})
    void configuredSnifferTimeout(int seconds)
    {
        contextRunner.withPropertyValues("spring.elasticsearch.restclient.sniffer.request-timeout=" + seconds + "s")
                .run(context -> {
                    assertThat(context).hasSingleBean(Sniffer.class);
                    assertThat(context.getBean(SearchEngineRestClientProperties.class).getSniffer().getRequestTimeout())
                            .isEqualTo(Duration.ofSeconds(seconds));
                });
    }

    @Test
    void pathPrefixAndLegacyProperties()
    {
        SearchEngineProperties properties = new SearchEngineProperties();
        properties.setPathPrefix("/proxy");
        DeprecatedSearchEngineRestClientProperties legacy = new DeprecatedSearchEngineRestClientProperties();
        assertThat(new SearchEngineRestClientConfigurations.RestClientBuilderConfiguration(properties, legacy).getPathPrefix())
                .isEqualTo("/proxy");
        legacy.setReadTimeout(Duration.ofSeconds(5));
        assertThat(new SearchEngineRestClientConfigurations.RestClientBuilderConfiguration(properties, legacy).getPathPrefix())
                .isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"basic", " BASIC ", "Basic"})
    void basicModeIsNormalized(String mode)
    {
        contextRunner.withPropertyValues("spring.elasticsearch.auth-mode=" + mode,
                "spring.elasticsearch.restclient.sniffer.enabled=false")
                .run(context -> {
                    assertThat(context).hasSingleBean(OpenSearchClient.class);
                    assertThat(context.getBean(SearchEngineProperties.class).getAuthMode()).isEqualTo("basic");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "basix"})
    void invalidModeFailsEvenWithCustomClient(String mode)
    {
        assertThatThrownBy(() -> new SearchEngineProperties().setAuthMode(mode))
                .hasMessageContaining("Unsupported spring.elasticsearch.auth-mode");
        contextRunner.withBean(OpenSearchClient.class, () -> mock(OpenSearchClient.class))
                .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        new MapPropertySource("mode", Map.of("spring.elasticsearch.auth-mode", mode))))
                .run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"aws-iam", " AWS-IAM "})
    void iamIsEnterpriseOnlyEvenWithCustomClient(String mode)
    {
        contextRunner.withBean(OpenSearchClient.class, () -> mock(OpenSearchClient.class))
                .withPropertyValues("spring.elasticsearch.auth-mode=" + mode)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage(
                            "AWS IAM authentication and spring.elasticsearch.aws-region/aws-service require Enterprise");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"aws-region", "aws-service", "awsRegion", "awsService", "aws_region", "aws_service"})
    void connectorAwsSettingsRejectedWithoutIamMode(String key)
    {
        for (boolean customClient : new boolean[]{false, true})
        {
            ApplicationContextRunner runner = customClient
                    ? contextRunner.withBean(OpenSearchClient.class, () -> mock(OpenSearchClient.class))
                    : contextRunner;
            runner.withPropertyValues("spring.elasticsearch." + key + "=")
                    .run(context -> assertThat(context).hasFailed());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"SPRING_ELASTICSEARCH_AWS_REGION", "SPRING_ELASTICSEARCH_AWS_SERVICE",
            "SPRING_ELASTICSEARCH_AWSREGION", "SPRING_ELASTICSEARCH_AWSSERVICE"})
    void environmentAwsSettingsRejected(String key)
    {
        contextRunner.withPropertyValues("spring.elasticsearch.auth-mode=basic")
                .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        new SystemEnvironmentPropertySource("systemEnvironment", Map.of(key, "synthetic"))))
                .run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"SPRING_ELASTICSEARCH_AUTH_MODE", "SPRING_ELASTICSEARCH_AUTHMODE"})
    void environmentIamModeRejected(String key)
    {
        contextRunner.withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                new SystemEnvironmentPropertySource("systemEnvironment", Map.of(key, " AWS-IAM "))))
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void systemPropertyRejectedAndAmbientCredentialsIgnored()
    {
        contextRunner.withSystemProperties("spring.elasticsearch.awsRegion=synthetic")
                .run(context -> assertThat(context).hasFailed());
        contextRunner.withPropertyValues("spring.elasticsearch.restclient.sniffer.enabled=false")
                .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        new SystemEnvironmentPropertySource("systemEnvironment",
                                Map.of("AWS_REGION", "us-east-1", "AWS_ACCESS_KEY_ID", "synthetic",
                                        "AWS_SECRET_ACCESS_KEY", "synthetic"))))
                .run(context -> assertThat(context).hasSingleBean(OpenSearchClient.class));
    }

    @Test
    void customClientBacksOffWithoutOverriding()
    {
        OpenSearchClient client = mock(OpenSearchClient.class);
        contextRunner.withBean("customClient", OpenSearchClient.class, () -> client)
                .withPropertyValues("spring.elasticsearch.restclient.sniffer.enabled=false")
                .run(context -> {
                    assertThat(context).hasSingleBean(OpenSearchClient.class);
                    assertThat(context.getBean(OpenSearchClient.class)).isSameAs(client);
                });
    }
}
