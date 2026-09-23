package com.pragmaticds.rag.lab;

import com.pragmaticds.rag.domain.Brain;
import com.pragmaticds.rag.lab.domain.LabAuditEvent;
import com.pragmaticds.rag.lab.service.LabAuditService;
import com.pragmaticds.rag.repository.BrainRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The audit trail actually receives rows.
 *
 * <p><b>Why this exists.</b> On the 2026-09-01 production deployment {@code lab_audit_event} held
 * <em>zero</em> rows — not "missing some", empty, through every collection create, membership
 * replace and release attempt since the plane went live. Postgres named the statement:
 *
 * <pre>update lab_audit_event set action=$1,... where id=$11</pre>
 *
 * an UPDATE where an INSERT belonged, refused by the append-only trigger. The same shape appeared
 * on {@code lab_instance_release}, which is why authoring a candidate returned
 * {@code RELEASE_REQUEST_FAILED}.
 *
 * <p><b>Why nothing caught it.</b> Every existing test that needs a release in the database
 * inserts one with {@code jdbc.update("INSERT INTO lab_instance_release ...")} — a fixture
 * shortcut that never exercises the repository production writes through. The suite proved what
 * the rows mean and never proved they arrive. This test writes through the real service, then
 * counts what landed, because a row that was never written is indistinguishable from a feature
 * nobody used until someone asks the table for it.
 *
 * <p><b>Why counting is the assertion.</b> {@link LabAuditService#record} is best-effort by
 * design: an audit failure is logged as a class name and swallowed, so the caller cannot see it.
 * That is the right trade for a lifecycle note — an audit write should not fail a user's
 * operation — but it means "no exception" proves nothing at all here. Only the row proves it.
 */
@SpringBootTest(properties = {
        "ragbrain.instances.enabled=true",
        "ragbrain.instances.execution.enabled=false",
        "ragbrain.instances.connector-enabled=false",
        "ragbrain.instances.promotion-enabled=false",
        "ragbrain.lab.enabled=false",
        "ragbrain.rag.admin.api-key=audit-write-it-key"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class LabAuditWriteIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired LabAuditService audit;
    @Autowired PlatformTransactionManager transactions;
    @Autowired BrainRepository brains;
    @Autowired JdbcTemplate jdbc;

    private UUID brainId;

    @BeforeEach
    void seedBrain() {
        brainId = UUID.randomUUID();
        Brain brain = new Brain(brainId, "audit-write-" + brainId, "Audit write");
        brain.setActive(true);
        brains.save(brain);
    }

    @Test
    void bestEffortAuditActuallyWritesARow() {
        audit.record(brainId, "COLLECTION_CREATE", LabAuditEvent.Status.SUCCEEDED,
                LabAuditEvent.SubjectType.COLLECTION, UUID.randomUUID(), null, Map.of());

        assertEquals(1, rowsFor("COLLECTION_CREATE"),
                "record() swallows write failures, so only the row proves the trail exists");
    }

    /**
     * Every subject type the enum declares must be writable. {@code COLLECTION}, {@code SNAPSHOT}
     * and {@code GROUP} arrived after V34's original six and were widened into the CHECK by V37
     * and V41 — a Java enum and a database CHECK that drift apart fail exactly here, at the first
     * row using the newer value.
     */
    @Test
    void everyDeclaredSubjectTypeIsWritable() {
        for (LabAuditEvent.SubjectType subject : LabAuditEvent.SubjectType.values()) {
            audit.record(brainId, "PROBE_" + subject.name(), LabAuditEvent.Status.SUCCEEDED,
                    subject, UUID.randomUUID(), null, Map.of());
        }

        for (LabAuditEvent.SubjectType subject : LabAuditEvent.SubjectType.values()) {
            assertEquals(1, rowsFor("PROBE_" + subject.name()),
                    "no row written for subject type " + subject);
        }
    }

    /**
     * The fail-closed mode is what a sensitive read depends on: it must commit or refuse the
     * caller. A deployment where it always refuses has no readable surface at all, so this
     * asserts both halves — it does not throw, and the row is there afterwards.
     */
    @Test
    void failClosedAuditCommitsRatherThanRefusing() {
        assertDoesNotThrow(() -> audit.recordRequired(
                brainId, "RELEASE_READ", LabAuditEvent.Status.SUCCEEDED,
                LabAuditEvent.SubjectType.RELEASE, UUID.randomUUID(), null, Map.of()));

        assertEquals(1, rowsFor("RELEASE_READ"));
    }

    /**
     * A refusal is the row that matters most: {@code REQUIRES_NEW} exists so a rolled-back
     * operation still leaves the record that it was attempted. A trail holding successes only is
     * the failure this guards.
     */
    @Test
    void aRefusalIsRecordedWithItsFailureCode() {
        audit.record(brainId, "RELEASE_CREATE", LabAuditEvent.Status.DENIED,
                LabAuditEvent.SubjectType.RELEASE, UUID.randomUUID(),
                "RELEASE_PRECONDITION_FAILED", Map.of());

        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM lab_audit_event "
                        + "WHERE brain_id = ? AND action = 'RELEASE_CREATE' "
                        + "AND status = 'DENIED' AND failure_code = 'RELEASE_PRECONDITION_FAILED'",
                Integer.class, brainId));
    }

    /**
     * The metadata shape production actually writes: long counts alongside booleans. Every other
     * case here passes {@code Map.of()}, which {@link LabAuditService} stores as NULL metadata —
     * and that emptiness is precisely why this suite stayed green while the deployment lost every
     * audit row. A mutable {@code Map} behind a JSON type is deep-copied into Hibernate's
     * dirty-check snapshot by a round-trip through the format mapper, which returns a {@code Long}
     * as an {@code Integer}; the snapshot then never equals the live value, so the same flush that
     * inserted the row also scheduled an {@code UPDATE} of it, V34's append-only trigger refused
     * that with {@code LAB_ROW_IMMUTABLE}, and the whole {@code REQUIRES_NEW} transaction —
     * insert included — rolled back. {@code @Immutable} on {@link LabAuditEvent} stops the UPDATE
     * being composed at all; this is the case that notices if it ever comes back.
     */
    @Test
    void auditRowCarryingLongCountsLands() {
        audit.record(brainId, "COUNTED_WRITE", LabAuditEvent.Status.SUCCEEDED,
                LabAuditEvent.SubjectType.RUN, UUID.randomUUID(), null,
                Map.of("inputTokens", 8192L, "retrievedChunks", 12, "fallbackUsed", false));

        assertEquals(1, rowsFor("COUNTED_WRITE"),
                "a metadata map holding Long counts is the shape production writes; "
                        + "an empty map cannot stand in for it");
    }

    /**
     * The shape production actually calls in: {@code REQUIRES_NEW} nested inside an outer
     * transaction that already holds pending JPA work.
     *
     * <p>The standalone cases above pass in CI while the same code fails on the deployment, so
     * the difference is not the audit write itself — it is the context around it. Every real
     * caller records from inside a {@code @Transactional} service method mid-operation; nothing
     * before this exercised that, which is why a green suite sat on top of an empty audit table.
     *
     * <p>What this pins down: the inner transaction must commit its own row without the outer
     * transaction's pending entities riding along on its flush. If the persistence context is
     * shared across the boundary, the inner commit flushes the outer's dirty entities too, and an
     * UPDATE against one of the append-only tables is refused — surfacing, misleadingly, as the
     * audit write failing.
     */
    @Test
    void auditCommitsFromInsideAnOuterTransactionHoldingPendingWork() {
        new TransactionTemplate(transactions).executeWithoutResult(outer -> {
            Brain pending = new Brain(UUID.randomUUID(), "outer-" + UUID.randomUUID(), "Outer");
            pending.setActive(true);
            brains.save(pending);

            audit.record(brainId, "NESTED_WRITE", LabAuditEvent.Status.SUCCEEDED,
                    LabAuditEvent.SubjectType.RELEASE, UUID.randomUUID(), null, Map.of());
        });

        assertEquals(1, rowsFor("NESTED_WRITE"),
                "an audit row written from inside an outer transaction must still land");
    }

    /**
     * The same nesting, but the outer transaction rolls back afterwards. This is the property
     * {@code REQUIRES_NEW} exists for: a refused operation must still leave the record that it
     * was attempted, or the trail holds successes only.
     */
    @Test
    void auditSurvivesTheRollbackOfTheOperationItRecords() {
        try {
            new TransactionTemplate(transactions).executeWithoutResult(outer -> {
                audit.record(brainId, "DOOMED_WRITE", LabAuditEvent.Status.DENIED,
                        LabAuditEvent.SubjectType.RELEASE, UUID.randomUUID(),
                        "RELEASE_PRECONDITION_FAILED", Map.of());
                throw new IllegalStateException("the operation fails after auditing itself");
            });
        } catch (IllegalStateException expected) {
            // The caller's failure is the point; the audit row is what must outlive it.
        }

        assertEquals(1, rowsFor("DOOMED_WRITE"),
                "REQUIRES_NEW exists so a rolled-back operation still leaves its record");
    }

    private int rowsFor(String action) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM lab_audit_event WHERE brain_id = ? AND action = ?",
                Integer.class, brainId, action);
        return count == null ? 0 : count;
    }
}
