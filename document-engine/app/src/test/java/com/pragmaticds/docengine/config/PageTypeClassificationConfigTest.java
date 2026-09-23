package com.pragmaticds.docengine.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.pragmaticds.docengine.platform.ai.PageTypeClassificationPort;
import com.pragmaticds.docengine.platform.ai.StubPageTypeClassificationAdapter;
import com.pragmaticds.docengine.platform.ai.VertexCredentialsProvider;
import com.pragmaticds.docengine.platform.ai.VertexGeminiPageTypeClassificationAdapter;
import java.time.Instant;
import java.util.Date;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Every incomplete state of the page-classification provider must land on the stub — which
 * downstream means "the page stays UNKNOWN", the answer the engine already gives today. The failure
 * this pins is not a wrong type but a REFUSED BOOT: a model seam that throws on a blank project id
 * takes the whole deterministic pipeline down with it.
 */
class PageTypeClassificationConfigTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(PageTypeClassificationConfig.class);

    @Test
    void defaults_to_stub() {
        runner.run(
                context ->
                        assertThat(context.getBean(PageTypeClassificationPort.class))
                                .isInstanceOf(StubPageTypeClassificationAdapter.class));
    }

    @Test
    void unsupported_provider_fails_closed_to_stub() {
        runner.withPropertyValues("docengine.ai.page-classification.provider=future-provider")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context.getBean(PageTypeClassificationPort.class))
                                    .isInstanceOf(StubPageTypeClassificationAdapter.class);
                        });
    }

    @Test
    void fully_configured_vertex_gemini_selects_vertex() {
        vertexRunner()
                .withPropertyValues(
                        "docengine.ai.page-classification.provider=vertex-gemini",
                        "docengine.ai.page-classification.model=synthetic-gemini-model",
                        "docengine.ai.vertex.project=synthetic-project",
                        "docengine.ai.vertex.region=us-central1")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context.getBean(PageTypeClassificationPort.class))
                                    .isInstanceOf(VertexGeminiPageTypeClassificationAdapter.class);
                        });
    }

    @Test
    void missing_vertex_project_fails_closed_to_stub() {
        vertexRunner()
                .withPropertyValues(
                        "docengine.ai.page-classification.provider=vertex-gemini",
                        "docengine.ai.vertex.project=",
                        "docengine.ai.vertex.region=us-central1")
                .run(
                        context ->
                                assertThat(context.getBean(PageTypeClassificationPort.class))
                                        .isInstanceOf(StubPageTypeClassificationAdapter.class));
    }

    @Test
    void missing_vertex_region_fails_closed_to_stub() {
        vertexRunner()
                .withPropertyValues(
                        "docengine.ai.page-classification.provider=vertex-gemini",
                        "docengine.ai.vertex.project=synthetic-project",
                        "docengine.ai.vertex.region=")
                .run(
                        context ->
                                assertThat(context.getBean(PageTypeClassificationPort.class))
                                        .isInstanceOf(StubPageTypeClassificationAdapter.class));
    }

    @Test
    void unresolvable_vertex_credentials_fail_closed_to_stub() {
        runner.withBean(
                        VertexCredentialsProvider.class,
                        () ->
                                () -> {
                                    throw new java.io.IOException("synthetic ADC failure");
                                })
                .withPropertyValues(
                        "docengine.ai.page-classification.provider=vertex-gemini",
                        "docengine.ai.vertex.project=synthetic-project",
                        "docengine.ai.vertex.region=us-central1")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context.getBean(PageTypeClassificationPort.class))
                                    .isInstanceOf(StubPageTypeClassificationAdapter.class);
                        });
    }

    /**
     * The test profile has no opt-out: an IT that scripts its own port overrides the bean, but no
     * property combination may put a live provider behind a test run's network.
     */
    @Test
    void test_profile_always_selects_stub() {
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("test"))
                .withPropertyValues(
                        "docengine.ai.page-classification.provider=vertex-gemini",
                        "docengine.ai.vertex.project=synthetic-project",
                        "docengine.ai.vertex.region=us-central1")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context.getBeansOfType(PageTypeClassificationPort.class))
                                    .hasSize(1);
                            assertThat(context.getBean(PageTypeClassificationPort.class))
                                    .isInstanceOf(StubPageTypeClassificationAdapter.class);
                        });
    }

    private ApplicationContextRunner vertexRunner() {
        GoogleCredentials credentials =
                GoogleCredentials.create(
                        new AccessToken(
                                "synthetic-access-token",
                                Date.from(Instant.now().plusSeconds(3600))));
        return runner.withBean(VertexCredentialsProvider.class, () -> () -> credentials);
    }
}
