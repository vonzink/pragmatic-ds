package com.pragmaticds.rag.config;

import com.pragmaticds.rag.TestBrains;
import com.pragmaticds.rag.domain.Brain;
import com.pragmaticds.rag.repository.BrainRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(DefaultBrainSeeder.class)
@TestPropertySource(properties = {
        "brain.slug=mortgage",
        "brain.pack=packs/generic",
        "brain.corpus.bucket=example-bucket",
        "brain.corpus.prefix=rag-brain/",
        "brain.corpus.region=us-west-1",
        "ragbrain.rag.routing.default-provider=anthropic",
        "spring.ai.anthropic.chat.options.model=claude-haiku-4-5",
        "ragbrain.rag.routing.fallback-provider=openai",
        "spring.ai.openai.chat.options.model=gpt-4.1-nano"
})
class DefaultBrainSeederTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired
    private BrainRepository brains;

    @Autowired
    private DefaultBrainSeeder seeder;

    @Test
    void reconcilesDefaultBrainFromConfig() {
        seeder.run(null);

        Brain def = brains.findDefaultBrain().orElseThrow();
        assertEquals("mortgage", def.getSlug());
        assertEquals("packs/generic", def.getPackRef());
        assertEquals("s3", def.getSourceType());
        assertEquals("example-bucket", def.getS3Bucket());
        assertEquals("rag-brain/", def.getS3Prefix());
        assertEquals("us-west-1", def.getS3Region());
        assertEquals("anthropic", def.getAnswerProvider());
        assertEquals("claude-haiku-4-5", def.getAnswerModel());
        assertEquals("openai", def.getUtilityProvider());
        assertEquals("gpt-4.1-nano", def.getUtilityModel());
    }

    @Test
    void isIdempotent() {
        seeder.run(null);
        seeder.run(null);
        assertEquals(1, brains.count());
        Brain def = brains.findDefaultBrain().orElseThrow();
        assertEquals("mortgage", def.getSlug());
        assertEquals("s3", def.getSourceType());
        assertEquals("example-bucket", def.getS3Bucket());
        assertEquals("anthropic", def.getAnswerProvider());
        assertEquals("claude-haiku-4-5", def.getAnswerModel());
        assertEquals("openai", def.getUtilityProvider());
        assertEquals("gpt-4.1-nano", def.getUtilityModel());
    }

    @Test
    void reconcilesTheSeededBrainNotWhicheverIsCurrentlyDefault() {
        // Promote a second brain to default; the seeded brain stays active but
        // non-default, still holding its slug. On restart the seeder must reconcile
        // the SEEDED brain by fixed id — reconciling the promoted brain instead would
        // set its slug to the env slug and collide with the seeded brain's unique
        // slug, crashing boot.
        Brain seeded = brains.findById(TestBrains.DEFAULT_ID).orElseThrow();
        seeded.setDefault(false);
        brains.saveAndFlush(seeded);
        Brain promoted = new Brain(UUID.randomUUID(), "lending", "Lending Brain");
        promoted.setActive(true);
        promoted.setDefault(true);
        brains.saveAndFlush(promoted);

        assertDoesNotThrow(() -> {
            seeder.run(null);
            brains.flush();
        });

        assertEquals("mortgage", brains.findById(TestBrains.DEFAULT_ID).orElseThrow().getSlug());
        Brain promotedAfter = brains.findById(promoted.getId()).orElseThrow();
        assertEquals("lending", promotedAfter.getSlug(), "promoted brain must be left untouched");
        assertTrue(promotedAfter.isDefault());
    }

    @Test
    void doesNotRevertAdminEditsWhenEnvUnchanged() {
        seeder.run(null); // first reconcile applies env config and stores the fingerprint

        // Simulate an admin editing the default brain through the dashboard/API.
        Brain edited = brains.findById(TestBrains.DEFAULT_ID).orElseThrow();
        edited.setAnswerModel("claude-opus-4-8");
        brains.saveAndFlush(edited);

        seeder.run(null); // restart with the same env must NOT clobber the edit

        assertEquals("claude-opus-4-8",
                brains.findById(TestBrains.DEFAULT_ID).orElseThrow().getAnswerModel());
    }
}
