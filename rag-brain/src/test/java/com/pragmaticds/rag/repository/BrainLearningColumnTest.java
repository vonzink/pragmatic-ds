package com.pragmaticds.rag.repository;

import com.pragmaticds.rag.domain.Brain;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class BrainLearningColumnTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired
    BrainRepository brains;

    @Autowired
    EntityManager em;

    @Test
    void newBrainDefaultsLearningDisabled() {
        Brain b = new Brain(UUID.randomUUID(), "learn-default-" + UUID.randomUUID(), "Default Off");
        brains.saveAndFlush(b);
        em.clear();

        Brain reloaded = brains.findById(b.getId()).orElseThrow();
        assertFalse(reloaded.isLearningEnabled(), "learning must default to false");
    }

    @Test
    void learningFlagRoundTrips() {
        Brain b = new Brain(UUID.randomUUID(), "learn-on-" + UUID.randomUUID(), "Learning On");
        b.setLearningEnabled(true);
        brains.saveAndFlush(b);
        em.clear();

        Brain reloaded = brains.findById(b.getId()).orElseThrow();
        assertTrue(reloaded.isLearningEnabled(), "true must persist and reload");
    }
}
