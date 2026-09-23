package com.pragmaticds.rag.controller;

import com.pragmaticds.rag.TestBrains;
import com.pragmaticds.rag.domain.Brain;
import com.pragmaticds.rag.pack.DomainPackRegistry;
import com.pragmaticds.rag.pack.PackTemplateService;
import com.pragmaticds.rag.repository.BrainDailyUsageRepository;
import com.pragmaticds.rag.repository.BrainRepository;
import com.pragmaticds.rag.service.ai.ModelRouterService;
import com.pragmaticds.rag.service.cost.SpendGuardService;
import com.pragmaticds.rag.service.retrieval.RetrievalService;
import com.pragmaticds.rag.service.sync.SyncService;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class BrainAdminActivateInvariantTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired
    private BrainRepository brains;

    @Test
    void activateFlipsTheDefaultWithoutViolatingTheUniqueIndex() {
        BrainAdminController controller = new BrainAdminController(
                brains, mock(SyncService.class), mock(DomainPackRegistry.class), mock(ModelRouterService.class),
                mock(PackTemplateService.class), mock(RetrievalService.class),
                mock(BrainDailyUsageRepository.class), mock(SpendGuardService.class));

        UUID secondId = UUID.randomUUID();
        Brain second = new Brain(secondId, "lending", "Lending Brain");
        second.setActive(true);
        second.setDefault(false);
        brains.saveAndFlush(second);

        controller.activate(secondId);

        assertEquals(secondId, brains.findDefaultBrain().orElseThrow().getId());
        assertTrue(brains.findById(TestBrains.DEFAULT_ID).orElseThrow().isActive());
        assertTrue(!brains.findById(TestBrains.DEFAULT_ID).orElseThrow().isDefault());
    }
}
