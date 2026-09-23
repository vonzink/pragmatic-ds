package com.pragmaticds.rag.repository;

import com.pragmaticds.rag.TestBrains;
import com.pragmaticds.rag.domain.BrainDailyUsage;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.transaction.TestTransaction;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class BrainDailyUsageRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired
    BrainDailyUsageRepository repo;

    @Autowired
    EntityManager em;

    @Test
    void savesAndReadsBackByCompositeKey() {
        LocalDate today = LocalDate.now();
        BrainDailyUsage usage = new BrainDailyUsage(
                TestBrains.DEFAULT_ID, today, 3, 1000L, 500L, new BigDecimal("0.012500"));
        repo.saveAndFlush(usage);
        em.clear();

        BrainDailyUsage found = repo.findByBrainIdAndUsageDate(TestBrains.DEFAULT_ID, today).orElseThrow();
        assertEquals(3, found.getRequestCount());
        assertEquals(1000L, found.getPromptTokens());
        assertEquals(500L, found.getCompletionTokens());
        assertEquals(0, new BigDecimal("0.012500").compareTo(found.getCostEstimateUsd()));
    }

    @Test
    void findByBrainIdAndUsageDateReturnsEmptyWhenNoRowYet() {
        assertTrue(repo.findByBrainIdAndUsageDate(TestBrains.DEFAULT_ID, LocalDate.now()).isEmpty());
    }

    @Test
    void upsertSpendInsertsOnFirstCall() {
        LocalDate today = LocalDate.now();
        repo.upsertSpend(TestBrains.DEFAULT_ID, today, 100L, 50L, new BigDecimal("0.001000"));
        em.flush();
        em.clear();

        BrainDailyUsage found = repo.findByBrainIdAndUsageDate(TestBrains.DEFAULT_ID, today).orElseThrow();
        assertEquals(1, found.getRequestCount());
        assertEquals(100L, found.getPromptTokens());
        assertEquals(50L, found.getCompletionTokens());
        assertEquals(0, new BigDecimal("0.001000").compareTo(found.getCostEstimateUsd()));
    }

    /**
     * Regression: {@code upsertSpend} is a {@code @Modifying} query that runs from
     * the deliberately non-transactional answer pipeline (AskService.recordSpend ->
     * SpendGuardService). With no ambient transaction, {@code executeUpdate} threw
     * {@code TransactionRequiredException}, surfacing as a 500 on every ANSWER (the
     * ESCALATE short-circuit never reaches recordSpend, which is why escalations
     * masked the bug). The query must manage its own transaction. This test ends
     * the @DataJpaTest ambient transaction to reproduce the production context.
     */
    @Test
    void upsertSpendManagesItsOwnTransactionWhenCalledWithoutOne() {
        // Seeded brain (satisfies the brain_id FK) + a date no other test uses, so
        // the row this commits (TestTransaction.end() means it is NOT rolled back)
        // cannot pollute the LocalDate.now()-based cases above.
        UUID brainId = TestBrains.DEFAULT_ID;
        LocalDate isolatedDay = LocalDate.of(2099, 1, 1);

        TestTransaction.end(); // drop the ambient tx -> mirror the non-transactional answer path

        assertDoesNotThrow(() ->
                repo.upsertSpend(brainId, isolatedDay, 100L, 50L, new BigDecimal("0.001000")));

        BrainDailyUsage found = repo.findByBrainIdAndUsageDate(brainId, isolatedDay).orElseThrow();
        assertEquals(1, found.getRequestCount());
        assertEquals(100L, found.getPromptTokens());
    }

    @Test
    void upsertSpendAccumulatesOnSubsequentCalls() {
        LocalDate today = LocalDate.now();
        repo.upsertSpend(TestBrains.DEFAULT_ID, today, 100L, 50L, new BigDecimal("0.001000"));
        repo.upsertSpend(TestBrains.DEFAULT_ID, today, 200L, 75L, new BigDecimal("0.002500"));
        em.flush();
        em.clear();

        BrainDailyUsage found = repo.findByBrainIdAndUsageDate(TestBrains.DEFAULT_ID, today).orElseThrow();
        assertEquals(2, found.getRequestCount());
        assertEquals(300L, found.getPromptTokens());
        assertEquals(125L, found.getCompletionTokens());
        assertEquals(0, new BigDecimal("0.003500").compareTo(found.getCostEstimateUsd()));
    }
}
