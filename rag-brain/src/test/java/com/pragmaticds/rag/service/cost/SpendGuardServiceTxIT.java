package com.pragmaticds.rag.service.cost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pragmaticds.rag.domain.Brain;
import com.pragmaticds.rag.repository.BrainDailyUsageRepository;
import com.pragmaticds.rag.repository.BrainRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Verifies recordSpend commits with NO ambient transaction, matching the real
 * ask-path call site (AskService.ask is deliberately non-@Transactional — see
 * its class-level comment — so this is the only write on that path that isn't
 * self-transactional). Must be @SpringBootTest, not @DataJpaTest: @DataJpaTest
 * wraps each test method in its own transaction, which would silently supply
 * the ambient transaction this test is specifically trying to prove is absent.
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class SpendGuardServiceTxIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired SpendGuardService spendGuardService;
    @Autowired BrainDailyUsageRepository usageRepository;
    @Autowired BrainRepository brainRepository;
    @Autowired Clock clock;

    // recordSpend runs on the ask path with NO ambient @Transactional
    // (AskService.ask is deliberately non-transactional). The @Modifying upsert
    // must open its own transaction or it throws TransactionRequiredException.
    @Test
    void recordSpendCommitsWithoutAnAmbientTransaction() {
        Brain brain = brainRepository.save(
                new Brain(UUID.randomUUID(), "spend-tx-" + UUID.randomUUID(), "Spend Tx"));
        UUID brainId = brain.getId();

        spendGuardService.recordSpend(brainId, "claude-haiku-4-5", 1000L, 500L);

        // Same clock as the service (UTC bean) — LocalDate.now() with the system zone
        // disagrees with the written usage_date between 18:00 and 24:00 MDT.
        var row = usageRepository.findByBrainIdAndUsageDate(brainId, LocalDate.now(clock));
        assertTrue(row.isPresent(), "spend row must be committed after recordSpend");
        assertEquals(1000L, row.get().getPromptTokens());
        assertEquals(500L, row.get().getCompletionTokens());
    }
}
