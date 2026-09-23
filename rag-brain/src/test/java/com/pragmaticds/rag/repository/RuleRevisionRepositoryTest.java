package com.pragmaticds.rag.repository;

import com.pragmaticds.rag.domain.RuleRevision;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static com.pragmaticds.rag.TestBrains.DEFAULT_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class RuleRevisionRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16")
                    .asCompatibleSubstituteFor("postgres"));

    @Autowired
    private RuleRevisionRepository repository;

    @Test
    void latestRevisionWinsAndHistoryIsNewestFirst() {
        repository.saveAndFlush(new RuleRevision(DEFAULT_ID, "rules.hard", "v1", "test"));
        repository.saveAndFlush(new RuleRevision(DEFAULT_ID, "rules.hard", "v2", "test"));
        repository.saveAndFlush(new RuleRevision(DEFAULT_ID, "rules.guidance", "g1", "test"));

        assertEquals("v2", repository
                .findFirstByBrainIdAndRuleKeyOrderByCreatedAtDescIdDesc(DEFAULT_ID, "rules.hard")
                .orElseThrow().getContent());
        assertEquals(2, repository
                .findTop20ByBrainIdAndRuleKeyOrderByCreatedAtDescIdDesc(DEFAULT_ID, "rules.hard")
                .size());
    }

    @Test
    void nullContentRevisionIsAllowedAsRevertMarker() {
        repository.saveAndFlush(new RuleRevision(DEFAULT_ID, "rules.hard", null, "test"));
        assertNull(repository
                .findFirstByBrainIdAndRuleKeyOrderByCreatedAtDescIdDesc(DEFAULT_ID, "rules.hard")
                .orElseThrow().getContent());
    }
}
