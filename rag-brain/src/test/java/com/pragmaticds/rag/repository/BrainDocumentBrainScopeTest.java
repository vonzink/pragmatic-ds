package com.pragmaticds.rag.repository;

import com.pragmaticds.rag.TestBrains;
import com.pragmaticds.rag.domain.Brain;
import com.pragmaticds.rag.domain.BrainDocument;
import com.pragmaticds.rag.domain.SourceType;
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

import static org.junit.jupiter.api.Assertions.assertEquals;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class BrainDocumentBrainScopeTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired
    BrainDocumentRepository docs;

    @Autowired
    BrainRepository brains;

    @Test
    void findersAreScopedByBrain() {
        UUID a = TestBrains.DEFAULT_ID;                 // seeded by V7
        Brain other = brains.save(new Brain(UUID.randomUUID(), "other", "Other"));
        docs.save(doc("a-active", a, true));
        docs.save(doc("a-inactive", a, false));
        docs.save(doc("b-active", other.getId(), true));

        assertEquals(2, docs.findByBrainId(a).size());
        assertEquals(1, docs.findByBrainIdAndActiveTrue(a).size());
        assertEquals(2, docs.countByBrainId(a));
        assertEquals(1, docs.countByBrainIdAndActiveTrue(a));
        assertEquals(1, docs.countByBrainId(other.getId()));
    }

    private BrainDocument doc(String name, UUID brainId, boolean active) {
        BrainDocument d = new BrainDocument();
        d.setTitle(name);
        d.setSourceName("s");
        d.setSourceType(SourceType.EDUCATIONAL);
        d.setFileName(name + ".pdf");
        d.setActive(active);
        d.setBrainId(brainId);
        return d;
    }
}
