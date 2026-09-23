package com.pragmaticds.rag.repository;

import com.pragmaticds.rag.TestBrains;
import com.pragmaticds.rag.domain.BrainDocument;
import com.pragmaticds.rag.domain.BrainSourceWeight;
import com.pragmaticds.rag.domain.SourceType;
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

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class BrainSourceWeightRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired
    BrainSourceWeightRepository repo;

    @Autowired
    EntityManager em;

    private UUID insertDocument() {
        BrainDocument doc = new BrainDocument();
        doc.setBrainId(TestBrains.DEFAULT_ID);
        doc.setTitle("Doc");
        doc.setSourceName("src");
        doc.setSourceType(SourceType.AGENCY_GUIDELINE);
        doc.setFileName("doc.pdf");
        em.persist(doc);
        em.flush();
        return doc.getId();
    }

    @Test
    void savesAndReadsBackByCompositeKey() {
        UUID docId = insertDocument();
        BrainSourceWeight w = new BrainSourceWeight(
                TestBrains.DEFAULT_ID, docId, 1.15, 7, "learning-job");
        repo.saveAndFlush(w);
        em.clear();

        BrainSourceWeight found = repo.findByBrainIdAndDocumentId(TestBrains.DEFAULT_ID, docId).orElseThrow();
        assertEquals(1.15, found.getWeight(), 1e-9);
        assertEquals(7, found.getFeedbackCount());
        assertEquals("learning-job", found.getUpdatedBy());
        assertTrue(found.getUpdatedAt() != null);
    }

    @Test
    void findByBrainIdReturnsAllForBrain() {
        UUID d1 = insertDocument();
        UUID d2 = insertDocument();
        repo.saveAndFlush(new BrainSourceWeight(TestBrains.DEFAULT_ID, d1, 1.1, 5, "learning-job"));
        repo.saveAndFlush(new BrainSourceWeight(TestBrains.DEFAULT_ID, d2, 0.9, 6, "learning-job"));

        List<BrainSourceWeight> all = repo.findByBrainId(TestBrains.DEFAULT_ID);
        assertEquals(2, all.size());
    }

    @Test
    void deleteByBrainIdClearsWeights() {
        UUID docId = insertDocument();
        repo.saveAndFlush(new BrainSourceWeight(TestBrains.DEFAULT_ID, docId, 1.2, 9, "learning-job"));

        repo.deleteByBrainId(TestBrains.DEFAULT_ID);
        em.flush();
        em.clear();

        assertTrue(repo.findByBrainId(TestBrains.DEFAULT_ID).isEmpty());
    }
}
