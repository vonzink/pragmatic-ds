package com.pragmaticds.rag.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.ClassUtils;

/**
 * The model clients' HTTP client is pinned to the JDK, whatever else is on the classpath.
 *
 * <p>2026-09-16: an AWS SDK bump put Apache HttpComponents 5 on the classpath, Spring Boot's
 * {@code detect()} switched every model call to it, and every Anthropic response was read as a
 * single "{" (JsonEOFException). This test runs WITH httpclient5 present — the same classpath
 * that broke production — and proves the factory ignores it.
 */
class AiHttpClientFactoryTest {

    @Test
    void model_calls_use_the_jdk_http_client_even_with_httpcomponents_on_the_classpath() {
        // The condition that broke production, asserted so the test cannot pass vacuously.
        assertThat(ClassUtils.isPresent("org.apache.hc.client5.http.impl.classic.HttpClients", null))
                .as("httpclient5 is on the test classpath (via the AWS SDK), as it is in production")
                .isTrue();

        ClientHttpRequestFactory factory = new AiHttpClientFactory(10_000, 60_000).requestFactory();

        assertThat(factory).isInstanceOf(JdkClientHttpRequestFactory.class);
    }

    @Test
    void builder_is_usable() {
        assertThat(new AiHttpClientFactory(10_000, 60_000).restClientBuilder().build()).isNotNull();
    }
}
