package com.pragmaticds.rag.repository;

import com.pragmaticds.rag.TestBrains;
import com.pragmaticds.rag.domain.FeedbackRating;
import com.pragmaticds.rag.domain.FeedbackSource;
import com.pragmaticds.rag.domain.RagAnswerFeedback;
import com.pragmaticds.rag.domain.RagTrace;
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

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class RagAnswerFeedbackRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired
    RagAnswerFeedbackRepository repo;

    @Autowired
    EntityManager em;

    private UUID insertTrace() {
        RagTrace t = new RagTrace();
        t.setBrainId(TestBrains.DEFAULT_ID);
        t.setUserQuestion("q?");
        em.persist(t);
        em.flush();
        return t.getId();
    }

    @Test
    void savesAndSetsGeneratedIdAndTimestamp() {
        UUID traceId = insertTrace();
        RagAnswerFeedback fb = new RagAnswerFeedback(
                traceId, TestBrains.DEFAULT_ID,
                FeedbackRating.UP.name(), FeedbackSource.END_USER.name(),
                "helpful", "sess-1", null);

        RagAnswerFeedback saved = repo.saveAndFlush(fb);

        assertNotNull(saved.getId(), "id must be generated");
        assertNotNull(saved.getCreatedAt(), "createdAt must be set @PrePersist");
        assertEquals(TestBrains.DEFAULT_ID, saved.getBrainId());
    }

    @Test
    void existsByTraceIdAndSessionIdEnforcesIdempotency() {
        UUID traceId = insertTrace();
        repo.saveAndFlush(new RagAnswerFeedback(
                traceId, TestBrains.DEFAULT_ID,
                FeedbackRating.UP.name(), FeedbackSource.END_USER.name(),
                null, "sess-A", null));

        assertTrue(repo.existsByTraceIdAndSessionId(traceId, "sess-A"));
        assertFalse(repo.existsByTraceIdAndSessionId(traceId, "sess-B"));
    }

    @Test
    void findByBrainIdAndCreatedAtAfterFiltersByTime() {
        UUID traceId = insertTrace();
        repo.saveAndFlush(new RagAnswerFeedback(
                traceId, TestBrains.DEFAULT_ID,
                FeedbackRating.DOWN.name(), FeedbackSource.ADMIN.name(),
                "wrong", null, "admin-1"));

        List<RagAnswerFeedback> recent = repo.findByBrainIdAndCreatedAtAfter(
                TestBrains.DEFAULT_ID, OffsetDateTime.now().minusMinutes(5));
        assertEquals(1, recent.size());

        List<RagAnswerFeedback> none = repo.findByBrainIdAndCreatedAtAfter(
                TestBrains.DEFAULT_ID, OffsetDateTime.now().plusMinutes(5));
        assertTrue(none.isEmpty());
    }
}
