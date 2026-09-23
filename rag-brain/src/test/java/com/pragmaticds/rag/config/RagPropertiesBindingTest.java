package com.pragmaticds.rag.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Binds {@link RagProperties} from the real application.yml and asserts every
 * nested group is populated.
 *
 * <p>This exists because of a production incident (2026-08-02): a convenience
 * constructor added to {@code RagProperties.Retrieval} for test ergonomics gave
 * that record TWO constructors, which makes Spring Boot's constructor binding
 * ambiguous. Binding silently yielded {@code null} for the whole group rather
 * than failing at startup, so the app booted healthy and then threw
 * NullPointerException on the first retrieval request — taking down /ask and
 * every analyzer that retrieves guideline context.
 *
 * <p>Nothing in the suite caught it: unit tests construct these records directly,
 * and the context-loads test never dereferenced the null. A null group is only
 * observable by binding the actual configuration, which is what this does.
 *
 * <p>Keep every nested group asserted here. If you add one, add it below.
 */
class RagPropertiesBindingTest {

    @Configuration
    @EnableConfigurationProperties(RagProperties.class)
    static class Enable {}

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(Enable.class);

    @Test
    void everyNestedGroupBindsFromApplicationYml() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            RagProperties props = context.getBean(RagProperties.class);

            assertThat(props.routing()).as("ragbrain.rag.routing").isNotNull();
            assertThat(props.retrieval()).as("ragbrain.rag.retrieval").isNotNull();
            assertThat(props.chunking()).as("ragbrain.rag.chunking").isNotNull();
            assertThat(props.storage()).as("ragbrain.rag.storage").isNotNull();
            assertThat(props.admin()).as("ragbrain.rag.admin").isNotNull();
            assertThat(props.analyze()).as("ragbrain.rag.analyze").isNotNull();
            assertThat(props.rateLimit()).as("ragbrain.rag.rate-limit").isNotNull();
        });
    }

    /**
     * The values RuntimeSettings dereferences on the retrieval hot path. A bound
     * group with garbage values would pass the null check above but still be wrong.
     */
    @Test
    void retrievalValuesMatchApplicationYml() {
        runner.run(context -> {
            RagProperties.Retrieval retrieval = context.getBean(RagProperties.class).retrieval();

            assertThat(retrieval.topK()).isEqualTo(8);
            assertThat(retrieval.minResults()).isEqualTo(3);
            assertThat(retrieval.confidenceThreshold()).isEqualTo(0.35);
            assertThat(retrieval.vectorWeight()).isEqualTo(0.65);
            assertThat(retrieval.keywordWeight()).isEqualTo(0.35);
            assertThat(retrieval.rerankEnabled()).isTrue();
            assertThat(retrieval.rerankCandidates()).isEqualTo(24);
            assertThat(retrieval.authorityOrderingEnabled()).isTrue();
            assertThat(retrieval.authorityTieBand()).isEqualTo(0.0);
        });
    }
}
