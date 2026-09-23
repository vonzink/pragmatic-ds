package com.pragmaticds.rag.repository;

import com.pragmaticds.rag.TestBrains;
import com.pragmaticds.rag.domain.BrainSourceWeightEvent;
import com.pragmaticds.rag.domain.WeightEventStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class BrainSourceWeightEventRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired
    BrainSourceWeightEventRepository repo;

    @Test
    void savesGeneratesIdAndTimestamp() {
        BrainSourceWeightEvent e = new BrainSourceWeightEvent(
                TestBrains.DEFAULT_ID, UUID.randomUUID(),
                1.0, 1.05, null, 8,
                WeightEventStatus.APPLIED.name(), "up>down", "learning-job");
        BrainSourceWeightEvent saved = repo.saveAndFlush(e);

        assertNotNull(saved.getId());
        assertNotNull(saved.getCreatedAt());
        assertEquals(WeightEventStatus.APPLIED.name(), saved.getStatus());
    }

    @Test
    void findsPendingOnlyOrderedNewestFirst() {
        UUID doc = UUID.randomUUID();
        repo.saveAndFlush(new BrainSourceWeightEvent(
                TestBrains.DEFAULT_ID, doc, 1.0, 1.05, null, 8,
                WeightEventStatus.APPLIED.name(), "applied", "learning-job"));
        repo.saveAndFlush(new BrainSourceWeightEvent(
                TestBrains.DEFAULT_ID, doc, 1.0, null, 0.85, 12,
                WeightEventStatus.PENDING.name(), "big move", "learning-job"));

        List<BrainSourceWeightEvent> pending = repo.findByBrainIdAndStatusOrderByCreatedAtDesc(
                TestBrains.DEFAULT_ID, WeightEventStatus.PENDING.name());
        assertEquals(1, pending.size());
        assertEquals(0.85, pending.getFirst().getProposedWeight(), 1e-9);
    }

    @Test
    void findTop50ReturnsRecentForBrain() {
        for (int i = 0; i < 3; i++) {
            repo.saveAndFlush(new BrainSourceWeightEvent(
                    TestBrains.DEFAULT_ID, UUID.randomUUID(), 1.0, 1.01, null, 5,
                    WeightEventStatus.APPLIED.name(), "n" + i, "learning-job"));
        }
        List<BrainSourceWeightEvent> recent =
                repo.findTop50ByBrainIdOrderByCreatedAtDesc(TestBrains.DEFAULT_ID);
        assertEquals(3, recent.size());
    }
}
