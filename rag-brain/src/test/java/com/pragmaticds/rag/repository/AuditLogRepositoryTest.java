package com.pragmaticds.rag.repository;

import com.pragmaticds.rag.domain.AuditLog;
import com.pragmaticds.rag.domain.Brain;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.PageRequest;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.pragmaticds.rag.TestBrains;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class AuditLogRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16")
                    .asCompatibleSubstituteFor("postgres"));

    @Autowired
    AuditLogRepository repository;

    @Autowired
    BrainRepository brains;

    /** A second brain row; ai_audit_logs.brain_id is a foreign key to brains. */
    private static final UUID OTHER_BRAIN = UUID.fromString("00000000-0000-0000-0000-00000000beef");

    private AuditLog log(String question, boolean escalated) {
        return log(question, escalated, TestBrains.DEFAULT_ID);
    }

    private AuditLog log(String question, boolean escalated, UUID brainId) {
        AuditLog entry = new AuditLog();
        entry.setUserQuestion(question);
        entry.setFallbackUsed(false);
        entry.setHumanEscalationRequired(escalated);
        entry.setBrainId(brainId);
        return entry;
    }

    // The Overview and Audit screens showed every brain's traffic no matter which
    // brain was selected: the search never filtered on brain_id. Both variants must.
    @Test
    void searchIsScopedToOneBrain() {
        brains.save(new Brain(OTHER_BRAIN, "other-brain", "Other Brain"));
        repository.save(log("What is PMI?", false));
        repository.save(log("Will I be approved?", true));
        repository.save(log("Where is the pipeline page?", false, OTHER_BRAIN));
        repository.save(log("What is a rate lock?", true, OTHER_BRAIN));

        assertEquals(2, repository.search(TestBrains.DEFAULT_ID, false, PageRequest.of(0, 10)).getTotalElements());
        assertEquals(2, repository.search(OTHER_BRAIN, false, PageRequest.of(0, 10)).getTotalElements());
        assertEquals(1, repository.search(OTHER_BRAIN, true, PageRequest.of(0, 10)).getTotalElements());
        assertEquals(1, repository.search(TestBrains.DEFAULT_ID, false, "what is", PageRequest.of(0, 10)).getTotalElements());
        assertEquals(1, repository.search(OTHER_BRAIN, false, "what is", PageRequest.of(0, 10)).getTotalElements());
        assertEquals(0, repository.search(OTHER_BRAIN, false, "pmi", PageRequest.of(0, 10)).getTotalElements());
    }

    @Test
    void brainIdRoundTripsThroughPersistence() {
        AuditLog saved = repository.save(log("What is PMI?", false));
        AuditLog reloaded = repository.findById(saved.getId()).orElseThrow();
        assertEquals(TestBrains.DEFAULT_ID, reloaded.getBrainId(),
                "brain_id must persist and reload on the audit record");
    }

    @Test
    void searchFiltersEscalationAndQuestionSubstring() {
        repository.save(log("What is PMI?", false));
        repository.save(log("Will I be approved?", true));
        repository.save(log("What is an FHA loan?", false));

        UUID brain = TestBrains.DEFAULT_ID;
        // no-q overload (null/blank q → three-param variant)
        assertEquals(3, repository.search(brain, false, PageRequest.of(0, 10)).getTotalElements());
        assertEquals(1, repository.search(brain, true, PageRequest.of(0, 10)).getTotalElements());
        // q overload
        assertEquals(2, repository.search(brain, false, "what is", PageRequest.of(0, 10)).getTotalElements());
        assertEquals(1, repository.search(brain, false, "fha", PageRequest.of(0, 10)).getTotalElements());
        assertEquals(0, repository.search(brain, true, "pmi", PageRequest.of(0, 10)).getTotalElements());
    }
}
