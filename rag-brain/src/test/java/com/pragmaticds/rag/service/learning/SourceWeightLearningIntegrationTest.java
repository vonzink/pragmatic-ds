package com.pragmaticds.rag.service.learning;

import com.pragmaticds.rag.config.ClusterJobLock;
import com.pragmaticds.rag.config.LearningProperties;
import com.pragmaticds.rag.domain.WeightEventStatus;
import com.pragmaticds.rag.repository.BrainRepository;
import com.pragmaticds.rag.repository.BrainSourceWeightEventRepository;
import com.pragmaticds.rag.repository.BrainSourceWeightRepository;
import com.pragmaticds.rag.repository.RagAnswerFeedbackRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.transaction.TestTransaction;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Drives the real {@link SourceWeightLearningService#adjust(UUID)} /
 * {@link SourceWeightLearningService#runOnce()} against a real Postgres, stubbing
 * only the two resolver interfaces (trace-&gt;docs and top-authority) so it does
 * not depend on the trace-capture group's internals.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import({SourceWeightService.class})
class SourceWeightLearningIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired DataSource dataSource;
    @Autowired BrainRepository brainRepo;
    @Autowired RagAnswerFeedbackRepository feedbackRepo;
    @Autowired BrainSourceWeightRepository weightRepo;
    @Autowired BrainSourceWeightEventRepository eventRepo;
    @Autowired SourceWeightService weightService;
    @Autowired PlatformTransactionManager txManager;

    private static final LearningProperties PROPS =
            new LearningProperties(0.8, 1.2, 0.05, 5, 3, 0.10, 0.98);

    private SourceWeightLearningService serviceFor(UUID docId, boolean topAuthority) {
        SourceWeightLearningService.TraceDocumentResolver traceDocs =
                traceIds -> {
                    // Every seeded trace in this test retrieved exactly docId.
                    var map = new java.util.HashMap<UUID, List<UUID>>();
                    for (UUID t : traceIds) map.put(t, List.of(docId));
                    return map;
                };
        SourceWeightLearningService.DocumentTrustResolver trust = d -> topAuthority;
        ClusterJobLock clusterJobLock = mock(ClusterJobLock.class);
        when(clusterJobLock.runIfLeader(anyLong(), anyString(), any())).thenAnswer(inv -> {
            ((Runnable) inv.getArgument(2)).run();
            return true;
        });
        return new SourceWeightLearningService(
                feedbackRepo, eventRepo, brainRepo, weightService, traceDocs, trust, PROPS,
                clusterJobLock, false);
    }

    @Test
    void appliesWeightChangeWhenEnabledBrainHasEnoughUpvotes() {
        // adjust() invalidates the weight cache only after its transaction commits (R2
        // fix); @DataJpaTest's default transaction never commits, so it must be run in a
        // real, separately-committed transaction to observe that eviction here.
        TestTransaction.end();
        UUID brainId = seedBrain(true);
        UUID docId = seedDocument(brainId, "APPROVED");
        for (int i = 0; i < 5; i++) insertFeedback(brainId, seedTrace(brainId), "UP", "END_USER");

        new TransactionTemplate(txManager).executeWithoutResult(
                status -> serviceFor(docId, false).adjust(brainId));

        double weight = weightService.weightFor(brainId, docId);
        assertTrue(weight > 1.0, "5 upvotes should raise the weight");
        assertEquals(1.05, weight, 1e-9);
        List<?> applied = eventRepo.findByBrainIdAndStatusOrderByCreatedAtDesc(brainId, WeightEventStatus.APPLIED.name());
        assertEquals(1, applied.size());
    }

    @Test
    void disabledBrainIsSkippedByRunOnce() {
        UUID brainId = seedBrain(false); // learning OFF
        UUID docId = seedDocument(brainId, "APPROVED");
        for (int i = 0; i < 10; i++) insertFeedback(brainId, seedTrace(brainId), "UP", "END_USER");

        serviceFor(docId, false).runOnce(); // iterates all brains, must skip this one

        assertEquals(1.0, weightService.weightFor(brainId, docId), 1e-9);
        assertTrue(weightRepo.findByBrainId(brainId).isEmpty());
    }

    @Test
    void belowMinEvidenceMakesNoChange() {
        UUID brainId = seedBrain(true);
        UUID docId = seedDocument(brainId, "APPROVED");
        for (int i = 0; i < 4; i++) insertFeedback(brainId, seedTrace(brainId), "UP", "END_USER"); // < 5

        serviceFor(docId, false).adjust(brainId);

        assertEquals(1.0, weightService.weightFor(brainId, docId), 1e-9);
        assertTrue(weightRepo.findByBrainId(brainId).isEmpty());
    }

    @Test
    void downvotesAgainstTopAuthoritySourceRouteToPending() {
        UUID brainId = seedBrain(true);
        UUID docId = seedDocument(brainId, "AUTHORITATIVE");
        for (int i = 0; i < 6; i++) insertFeedback(brainId, seedTrace(brainId), "DOWN", "END_USER");

        serviceFor(docId, /* topAuthority */ true).adjust(brainId);

        // No weight applied; a PENDING review event exists instead.
        assertTrue(weightRepo.findByBrainId(brainId).isEmpty());
        var pending = eventRepo.findByBrainIdAndStatusOrderByCreatedAtDesc(brainId, WeightEventStatus.PENDING.name());
        assertEquals(1, pending.size());
    }

    @Test
    void reRunningOverTheSameFeedbackBurstMovesTheWeightOnlyOnce() {
        // adjust() invalidates the weight cache only after its transaction commits (R2
        // fix); @DataJpaTest's default transaction never commits, so it must be run in a
        // real, separately-committed transaction to observe that eviction here.
        TestTransaction.end();
        UUID brainId = seedBrain(true);
        UUID docId = seedDocument(brainId, "APPROVED");
        for (int i = 0; i < 5; i++) insertFeedback(brainId, seedTrace(brainId), "UP", "END_USER");

        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(status -> serviceFor(docId, false).adjust(brainId));
        double afterFirst = weightService.weightFor(brainId, docId);
        assertEquals(1.05, afterFirst, 1e-9, "first pass applies the bounded up-move");

        // Second pass over the SAME burst: rows were consumed (processed_at set), so
        // it is a no-op. Without the processed-cursor the burst would be re-tallied
        // and ratchet the weight further toward the clamp bound.
        tx.executeWithoutResult(status -> serviceFor(docId, false).adjust(brainId));

        assertEquals(afterFirst, weightService.weightFor(brainId, docId), 1e-9,
                "second pass over consumed feedback must not move the weight again");
        List<?> applied = eventRepo.findByBrainIdAndStatusOrderByCreatedAtDesc(
                brainId, WeightEventStatus.APPLIED.name());
        assertEquals(1, applied.size(), "no duplicate APPLIED event on the second run");
    }

    @Test
    void reRunningOverAPendingBurstDoesNotReEmitTheProposal() {
        UUID brainId = seedBrain(true);
        UUID docId = seedDocument(brainId, "AUTHORITATIVE");
        for (int i = 0; i < 6; i++) insertFeedback(brainId, seedTrace(brainId), "DOWN", "END_USER");

        serviceFor(docId, /* topAuthority */ true).adjust(brainId);
        // Second pass: the same downvote burst is already consumed, so no new PENDING.
        serviceFor(docId, true).adjust(brainId);

        assertTrue(weightRepo.findByBrainId(brainId).isEmpty(), "still no weight applied");
        var pending = eventRepo.findByBrainIdAndStatusOrderByCreatedAtDesc(
                brainId, WeightEventStatus.PENDING.name());
        assertEquals(1, pending.size(),
                "a rejected/pending proposal must not be re-emitted every run");
    }

    // --- seeding helpers (raw JDBC, mirrors OpsDataRetentionServiceTest style) ---

    private UUID seedBrain(boolean learningEnabled) {
        UUID id = UUID.randomUUID();
        new JdbcTemplate(dataSource).update(
                "INSERT INTO brains (id, slug, display_name, is_default, is_active, learning_enabled, created_at, updated_at) "
                        + "VALUES (?, ?, ?, false, true, ?, now(), now())",
                id, "brain-" + id.toString().substring(0, 8), "Test Brain", learningEnabled);
        return id;
    }

    private UUID seedDocument(UUID brainId, String trustLevel) {
        UUID id = UUID.randomUUID();
        // brain_documents NOT-NULL-without-default columns per V1/V3/V7: title,
        // source_name, source_type, file_name, brain_id. visibility/trust_level
        // have defaults from V13/V14 but trust_level is overridden per test case.
        new JdbcTemplate(dataSource).update(
                "INSERT INTO brain_documents (id, brain_id, title, source_name, source_type, file_name, trust_level) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                id, brainId, "Test Doc", "doc-" + id.toString().substring(0, 8), "educational", "test.pdf", trustLevel);
        return id;
    }

    private UUID seedTrace(UUID brainId) {
        UUID id = UUID.randomUUID();
        new JdbcTemplate(dataSource).update(
                "INSERT INTO rag_traces (id, brain_id, user_question, created_at) VALUES (?, ?, ?, now())",
                id, brainId, "q");
        return id;
    }

    private void insertFeedback(UUID brainId, UUID traceId, String rating, String source) {
        new JdbcTemplate(dataSource).update(
                "INSERT INTO rag_answer_feedback (id, trace_id, brain_id, rating, source, session_id, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), traceId, brainId, rating, source,
                UUID.randomUUID().toString(), Timestamp.from(OffsetDateTime.now().toInstant()));
    }
}
