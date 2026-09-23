package com.pragmaticds.rag.repository;

import com.pragmaticds.rag.domain.BrainSetting;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class BrainSettingRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16")
                    .asCompatibleSubstituteFor("postgres"));

    @Autowired
    private BrainSettingRepository repository;

    @Test
    void savesAndReadsASetting() {
        repository.save(new BrainSetting("answer.model", "claude-haiku-4-5", "test"));

        BrainSetting loaded = repository.findById("answer.model").orElseThrow();
        assertEquals("claude-haiku-4-5", loaded.getValue());
        assertEquals("test", loaded.getUpdatedBy());
        assertNotNull(loaded.getUpdatedAt());
    }

    @Test
    void upsertOverwritesValueByKey() {
        repository.saveAndFlush(new BrainSetting("retrieval.top-k", "8", "test"));
        BrainSetting existing = repository.findById("retrieval.top-k").orElseThrow();
        existing.setValue("12");
        existing.setUpdatedBy("test2");
        repository.saveAndFlush(existing);

        assertEquals(1, repository.count());
        assertEquals("12", repository.findById("retrieval.top-k").orElseThrow().getValue());
    }
}
