package com.pragmaticds.docengine.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.pragmaticds.docengine.ingestion.AbstractIngestionIT;
import com.pragmaticds.docengine.platform.storage.BlobStoragePort;
import com.pragmaticds.docengine.retention.RetentionPurgeJob;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * The criterion-4 proof for the retention purge — the one data-destructive path in the engine. It
 * must delete every DB row AND every blob of a due package, delete NOTHING else, and never cross a
 * tenant. So the fixture stands up FIVE packages across two orgs and asserts, row by row and blob by
 * blob, that only the two due ones vanished:
 *
 * <ul>
 *   <li>A — ORG_DEV, soft-deleted, {@code purge_after} in the PAST → purged
 *   <li>E — ORG_OTHER, soft-deleted, {@code purge_after} in the PAST → purged (proves the sweep
 *       binds each org's own tenant, not just the default)
 *   <li>B — ORG_DEV, LIVE → untouched (no over-deletion within an org)
 *   <li>C — ORG_OTHER, LIVE → untouched (no cross-tenant deletion)
 *   <li>D — ORG_DEV, soft-deleted but {@code purge_after} in the FUTURE → untouched (not yet due)
 * </ul>
 */
class RetentionPurgeIT extends AbstractIngestionIT {

    @Autowired private RetentionPurgeJob purgeJob;
    @MockitoSpyBean private BlobStoragePort storageSpy;

    @Test
    void purges_every_due_package_completely_across_orgs_and_touches_nothing_else() {
        LifecycleFixtures.Seed dueDev =
                LifecycleFixtures.seedFullPackage(jdbc, blobStorage, ORG_DEV, "due-dev");
        LifecycleFixtures.Seed dueOther =
                LifecycleFixtures.seedFullPackage(jdbc, blobStorage, ORG_OTHER, "due-other");
        LifecycleFixtures.Seed liveDev =
                LifecycleFixtures.seedFullPackage(jdbc, blobStorage, ORG_DEV, "live-dev");
        LifecycleFixtures.Seed liveOther =
                LifecycleFixtures.seedFullPackage(jdbc, blobStorage, ORG_OTHER, "live-other");
        LifecycleFixtures.Seed notYetDue =
                LifecycleFixtures.seedFullPackage(jdbc, blobStorage, ORG_DEV, "future-dev");

        softDelete(dueDev, Instant.now().minus(Duration.ofHours(1)));
        softDelete(dueOther, Instant.now().minus(Duration.ofHours(1)));
        softDelete(notYetDue, Instant.now().plus(Duration.ofDays(10)));

        // Every standard seed starts with the full 16-row footprint and its four blobs, including
        // one successful immutable engine-result revision.
        assertThat(LifecycleFixtures.countByPackage(jdbc, dueDev)).isEqualTo(16);
        assertThat(LifecycleFixtures.countByPackage(jdbc, dueOther)).isEqualTo(16);

        int purged = purgeJob.purgeDuePackages(Instant.now());
        assertThat(purged).isGreaterThanOrEqualTo(2);

        // ── The two due packages: every row and every blob is gone. ──────────────────
        assertPurged(dueDev, ORG_DEV);
        assertPurged(dueOther, ORG_OTHER);

        // ── Everything else is untouched: rows and blobs intact, no purge audit. ─────
        assertIntact(liveDev);
        assertIntact(liveOther);
        assertIntact(notYetDue);
        assertNoPurgeAudit(liveDev);
        assertNoPurgeAudit(liveOther);
        assertNoPurgeAudit(notYetDue);

        // An already-purged package no longer appears in a sweep and never creates a duplicate
        // audit. Physical absence is an idempotent no-op, not another purge event.
        assertThat(purgeJob.purgeDuePackages(Instant.now())).isZero();
        assertThat(purgeAuditCount(dueDev)).isEqualTo(1);
        assertThat(purgeAuditCount(dueOther)).isEqualTo(1);
    }

    @Test
    void purge_deletes_all_historical_result_revisions_and_blobs_in_fk_safe_order() {
        LifecycleFixtures.Seed due =
                LifecycleFixtures.seedFullPackage(jdbc, blobStorage, ORG_DEV, "due-result-chain");
        LifecycleFixtures.ResultRevision second =
                LifecycleFixtures.appendSecondEngineResult(jdbc, blobStorage, due);
        softDelete(due, Instant.now().minus(Duration.ofHours(1)));

        assertThat(
                        jdbc.queryForObject(
                                "SELECT supersedes_result_id FROM engine_result WHERE id = ?",
                                UUID.class,
                                second.id()))
                .isEqualTo(due.engineResultId());
        assertThat(LifecycleFixtures.countByPackage(jdbc, due)).isEqualTo(17);
        assertThat(engineResultCount(due)).isEqualTo(2);
        assertThat(blobStorage.exists(due.engineResultBlobKey())).isTrue();
        assertThat(blobStorage.exists(second.blobKey())).isTrue();

        assertThat(purgeJob.purgeDuePackages(Instant.now())).isGreaterThanOrEqualTo(1);

        // The non-cascading job FK requires descriptors before the job. The self-predecessor FK
        // requires revision 2 before revision 1. A mutation of either order rolls the purge back,
        // so this end state pins both deletion constraints against the real database.
        assertThat(LifecycleFixtures.countByPackage(jdbc, due)).isZero();
        assertThat(engineResultCount(due)).isZero();
        assertThat(blobStorage.exists(due.engineResultBlobKey())).isFalse();
        assertThat(blobStorage.exists(second.blobKey())).isFalse();
        assertThat(auditCount(due, "rowsDeleted")).isEqualTo(17);
        assertThat(auditCount(due, "blobsDeleted")).isEqualTo(5);
    }

    @Test
    void blob_delete_failure_rolls_back_rows_then_missing_delete_is_idempotent_on_retry() {
        LifecycleFixtures.Seed due =
                LifecycleFixtures.seedFullPackage(jdbc, blobStorage, ORG_DEV, "due-delete-fault");
        softDelete(due, Instant.now().minus(Duration.ofHours(1)));

        // Fail exactly once on the second insertion-ordered key. The original blob is therefore
        // physically gone before the render delete fails, while DB work remains transactional.
        AtomicBoolean failOnce = new AtomicBoolean(true);
        doAnswer(
                        invocation -> {
                            String key = invocation.getArgument(0);
                            if (key.equals(due.renderBlobKey())
                                    && failOnce.compareAndSet(true, false)) {
                                throw new IllegalStateException("simulated delete failure: " + key);
                            }
                            return invocation.callRealMethod();
                        })
                .when(storageSpy)
                .delete(anyString());

        Logger logger = (Logger) LoggerFactory.getLogger(RetentionPurgeJob.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        int firstSweep;
        String captured;
        try {
            firstSweep = purgeJob.purgeDuePackages(Instant.now());
            captured =
                    List.copyOf(appender.list).stream()
                            .map(ILoggingEvent::getFormattedMessage)
                            .reduce("", (left, right) -> left + "\n" + right);
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(firstSweep).isZero();
        assertThat(blobStorage.exists(due.originalBlobKey()))
                .as("the physical delete before the injected failure cannot roll back")
                .isFalse();
        assertThat(blobStorage.exists(due.renderBlobKey())).isTrue();
        assertThat(blobStorage.exists(due.parserPayloadKey())).isTrue();
        assertThat(blobStorage.exists(due.engineResultBlobKey())).isTrue();
        assertThat(LifecycleFixtures.countByPackage(jdbc, due)).isEqualTo(16);
        assertThat(engineResultCount(due)).isEqualTo(1);
        assertThat(purgeAuditCount(due)).isZero();
        assertThat(captured)
                .contains(due.packageId().toString(), "IllegalStateException")
                .doesNotContain(
                        due.originalBlobKey(),
                        due.renderBlobKey(),
                        due.parserPayloadKey(),
                        due.engineResultBlobKey(),
                        due.engineResultDigest());

        // The one-time fault is now disabled. Local storage uses deleteIfExists, so retrying the
        // already-missing original is harmless and the package converges to exactly one audit.
        assertThat(purgeJob.purgeDuePackages(Instant.now())).isGreaterThanOrEqualTo(1);
        assertPurged(due, ORG_DEV);
        assertThat(purgeAuditCount(due)).isEqualTo(1);
    }

    /**
     * FIX 1 (HIGH): a purge must also delete the package's {@code review_decision} rows. A CORRECT
     * (and document-level decisions) leave rows whose {@code subject_id} — the extracted_field /
     * logical_document / page id — has NO foreign key (V8), so nothing cascades. Without an explicit
     * delete those rows outlive the purge with the reviewer-typed value (potential PII) intact,
     * orphaned forever. audit_event is the immutable trail and MUST survive; only review_decision is
     * purged.
     */
    @Test
    void purge_deletes_the_packages_review_decision_rows_and_touches_no_other_packages() {
        LifecycleFixtures.Seed due =
                LifecycleFixtures.seedFullPackage(jdbc, blobStorage, ORG_DEV, "due-with-decisions");
        LifecycleFixtures.Seed live =
                LifecycleFixtures.seedFullPackage(jdbc, blobStorage, ORG_DEV, "live-with-decisions");

        // Decisions spanning all three subject-id kinds the purge must enumerate.
        seedDecision(due, "EXTRACTED_FIELD", due.extractedFieldId(), "CORRECT", "9,999.99");
        seedDecision(due, "LOGICAL_DOCUMENT", due.logicalDocumentId(), "RECLASSIFY", "W2");
        seedDecision(due, "PAGE_ASSIGNMENT", due.pageId(), "REGROUP", "regrouped-secret");
        // Another package's decision must be untouched.
        seedDecision(live, "EXTRACTED_FIELD", live.extractedFieldId(), "CORRECT", "1,234.56");

        softDelete(due, Instant.now().minus(Duration.ofHours(1)));

        assertThat(reviewDecisionCount(due)).isEqualTo(3);
        assertThat(reviewDecisionCount(live)).isEqualTo(1);

        purgeJob.purgeDuePackages(Instant.now());

        assertThat(reviewDecisionCount(due))
                .as("the purge deletes the package's review_decision rows — no residual PII")
                .isZero();
        assertThat(reviewDecisionCount(live))
                .as("a live package's decisions are untouched")
                .isEqualTo(1);

        // audit_event survives — the immutable trail is never purged, only review_decision.
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM audit_event WHERE action = 'PACKAGE_PURGED'"
                                        + " AND subject_id = ?",
                                Long.class,
                                due.packageId()))
                .isEqualTo(1);
    }

    @Test
    void purge_deletes_the_packages_ai_interpretations_and_touches_no_other_packages() {
        LifecycleFixtures.Seed due =
                LifecycleFixtures.seedFullPackage(jdbc, blobStorage, ORG_DEV, "due-with-ai");
        LifecycleFixtures.Seed live =
                LifecycleFixtures.seedFullPackage(jdbc, blobStorage, ORG_DEV, "live-with-ai");

        seedInterpretation(due, "PAGE", due.pageId());
        seedInterpretation(due, "LOGICAL_DOCUMENT", due.logicalDocumentId());
        seedInterpretation(due, "EXTRACTED_FIELD", due.extractedFieldId());
        seedInterpretation(live, "LOGICAL_DOCUMENT", live.logicalDocumentId());
        softDelete(due, Instant.now().minus(Duration.ofHours(1)));

        assertThat(aiInterpretationCount(due)).isEqualTo(3);
        assertThat(aiInterpretationCount(live)).isEqualTo(1);

        purgeJob.purgeDuePackages(Instant.now());

        assertThat(aiInterpretationCount(due))
                .as("the purge leaves no orphaned AI-proposed values")
                .isZero();
        assertThat(aiInterpretationCount(live))
                .as("a live package's AI interpretations are untouched")
                .isEqualTo(1);
    }

    /**
     * FIX 1 (Spec 2, HIGH): a package-wide REGROUP decision is keyed on the PACKAGE id (subject_type
     * LOGICAL_DOCUMENT), not a field/document/page id — {@code RegroupService} writes it that way.
     * The Phase-7c enumeration (page ∪ logical_document ∪ extracted_field) never includes the
     * package itself, so without adding the package id to the purge's decision-subject set that row —
     * carrying the reviewer's grouping delta — outlives the purge, orphaned forever. It is the same
     * orphan class the review_decision purge already closes for the other three subject kinds.
     */
    @Test
    void purge_deletes_the_package_keyed_regroup_decision() {
        LifecycleFixtures.Seed due =
                LifecycleFixtures.seedFullPackage(jdbc, blobStorage, ORG_DEV, "due-with-regroup");
        LifecycleFixtures.Seed live =
                LifecycleFixtures.seedFullPackage(jdbc, blobStorage, ORG_DEV, "live-with-regroup");

        // A package-wide regroup: subject_id IS the package id (RegroupService keys it this way).
        seedDecision(due, "LOGICAL_DOCUMENT", due.packageId(), "REGROUP", "regrouped-secret");
        // Another package's package-keyed regroup must be untouched.
        seedDecision(live, "LOGICAL_DOCUMENT", live.packageId(), "REGROUP", "other-grouping");

        softDelete(due, Instant.now().minus(Duration.ofHours(1)));

        assertThat(packageKeyedDecisionCount(due)).isEqualTo(1);
        assertThat(packageKeyedDecisionCount(live)).isEqualTo(1);

        purgeJob.purgeDuePackages(Instant.now());

        assertThat(packageKeyedDecisionCount(due))
                .as("the purge deletes the package-keyed REGROUP decision — no residual grouping delta")
                .isZero();
        assertThat(packageKeyedDecisionCount(live))
                .as("a live package's package-keyed decision is untouched")
                .isEqualTo(1);
    }

    private long packageKeyedDecisionCount(LifecycleFixtures.Seed seed) {
        Long n =
                jdbc.queryForObject(
                        "SELECT count(*) FROM review_decision WHERE org_id = ? AND subject_id = ?",
                        Long.class,
                        seed.orgId(),
                        seed.packageId());
        return n == null ? 0 : n;
    }

    private void seedInterpretation(
            LifecycleFixtures.Seed seed, String subjectType, UUID subjectId) {
        jdbc.update(
                "INSERT INTO ai_interpretation"
                        + " (org_id, subject_type, subject_id, provider, model, prompt_version,"
                        + " interpretation) VALUES (?, ?, ?, 'test', 'synthetic', 'test/1',"
                        + " ?::jsonb)",
                seed.orgId(),
                subjectType,
                subjectId,
                "{\"suggestedText\":\"synthetic-sensitive-value\"}");
    }

    private long aiInterpretationCount(LifecycleFixtures.Seed seed) {
        Long n =
                jdbc.queryForObject(
                        "SELECT count(*) FROM ai_interpretation WHERE org_id = ? AND subject_id IN"
                                + " (?, ?, ?)",
                        Long.class,
                        seed.orgId(),
                        seed.pageId(),
                        seed.logicalDocumentId(),
                        seed.extractedFieldId());
        return n == null ? 0 : n;
    }

    private void seedDecision(
            LifecycleFixtures.Seed seed,
            String subjectType,
            UUID subjectId,
            String action,
            String value) {
        jdbc.update(
                "INSERT INTO review_decision (org_id, subject_type, subject_id, action,"
                        + " previous_value, new_value, decided_by) VALUES (?, ?, ?, ?, ?::jsonb,"
                        + " ?::jsonb, ?)",
                seed.orgId(),
                subjectType,
                subjectId,
                action,
                "{\"value\":\"old\"}",
                "{\"value\":\"" + value + "\"}",
                UUID.randomUUID());
    }

    private long reviewDecisionCount(LifecycleFixtures.Seed seed) {
        Long n =
                jdbc.queryForObject(
                        "SELECT count(*) FROM review_decision WHERE org_id = ? AND subject_id IN"
                                + " (?, ?, ?)",
                        Long.class,
                        seed.orgId(),
                        seed.extractedFieldId(),
                        seed.logicalDocumentId(),
                        seed.pageId());
        return n == null ? 0 : n;
    }

    private void assertPurged(LifecycleFixtures.Seed seed, UUID org) {
        assertThat(LifecycleFixtures.countByPackage(jdbc, seed))
                .as("all DB rows of a purged package are gone")
                .isZero();
        assertThat(blobStorage.exists(seed.originalBlobKey())).isFalse();
        assertThat(blobStorage.exists(seed.renderBlobKey())).isFalse();
        assertThat(blobStorage.exists(seed.parserPayloadKey())).isFalse();
        assertThat(blobStorage.exists(seed.engineResultBlobKey())).isFalse();

        // A PACKAGE_PURGED audit event, written by the SYSTEM actor under the RIGHT org, with
        // COUNTS only — no filename, no content, PII-free by construction.
        assertThat(
                        jdbc.queryForObject(
                                "SELECT actor_type FROM audit_event WHERE action = 'PACKAGE_PURGED'"
                                        + " AND subject_id = ?",
                                String.class,
                                seed.packageId()))
                .isEqualTo("SYSTEM");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT org_id FROM audit_event WHERE action = 'PACKAGE_PURGED' AND"
                                        + " subject_id = ?",
                                UUID.class,
                                seed.packageId()))
                .isEqualTo(org);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT (metadata->>'rowsDeleted')::int FROM audit_event WHERE"
                                        + " action = 'PACKAGE_PURGED' AND subject_id = ?",
                                Integer.class,
                                seed.packageId()))
                .isEqualTo(16);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT (metadata->>'blobsDeleted')::int FROM audit_event WHERE"
                                        + " action = 'PACKAGE_PURGED' AND subject_id = ?",
                                Integer.class,
                                seed.packageId()))
                .isEqualTo(4);
        // PII-free: the metadata carries EXACTLY the two count keys and nothing else. The blob
        // number is the deduped keys processed by a successful prototype purge; it does not claim
        // each object was newly removed (deleteIfExists makes retries idempotent).
        assertThat(
                        jdbc.queryForList(
                                "SELECT jsonb_object_keys(metadata) FROM audit_event WHERE action ="
                                        + " 'PACKAGE_PURGED' AND subject_id = ?",
                                String.class,
                                seed.packageId()))
                .containsExactlyInAnyOrder("rowsDeleted", "blobsDeleted");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT metadata::text FROM audit_event WHERE action ="
                                        + " 'PACKAGE_PURGED' AND subject_id = ?",
                                String.class,
                                seed.packageId()))
                .doesNotContain(
                        "due-",
                        "original",
                        ".png",
                        ".json",
                        "storage",
                        seed.engineResultBlobKey(),
                        seed.engineResultDigest());
    }

    private void assertIntact(LifecycleFixtures.Seed seed) {
        assertThat(LifecycleFixtures.countByPackage(jdbc, seed))
                .as("a package not due for purge keeps all its rows")
                .isEqualTo(16);
        assertThat(blobStorage.exists(seed.originalBlobKey())).isTrue();
        assertThat(blobStorage.exists(seed.renderBlobKey())).isTrue();
        assertThat(blobStorage.exists(seed.parserPayloadKey())).isTrue();
        assertThat(blobStorage.exists(seed.engineResultBlobKey())).isTrue();
    }

    private void assertNoPurgeAudit(LifecycleFixtures.Seed seed) {
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM audit_event WHERE action = 'PACKAGE_PURGED'"
                                        + " AND subject_id = ?",
                                Long.class,
                                seed.packageId()))
                .isZero();
    }

    private void softDelete(LifecycleFixtures.Seed seed, Instant purgeAfter) {
        jdbc.update(
                "UPDATE document_package SET deleted_at = now(), purge_after = ? WHERE id = ?",
                Timestamp.from(purgeAfter),
                seed.packageId());
    }

    private long engineResultCount(LifecycleFixtures.Seed seed) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM engine_result WHERE package_id = ? AND org_id = ?",
                Long.class,
                seed.packageId(),
                seed.orgId());
    }

    private int auditCount(LifecycleFixtures.Seed seed, String key) {
        return jdbc.queryForObject(
                "SELECT (metadata->>?)::int FROM audit_event WHERE action = 'PACKAGE_PURGED'"
                        + " AND subject_id = ?",
                Integer.class,
                key,
                seed.packageId());
    }

    private long purgeAuditCount(LifecycleFixtures.Seed seed) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM audit_event WHERE action = 'PACKAGE_PURGED' AND subject_id = ?",
                Long.class,
                seed.packageId());
    }
}
