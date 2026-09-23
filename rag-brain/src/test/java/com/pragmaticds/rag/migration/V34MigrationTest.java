package com.pragmaticds.rag.migration;

import com.pragmaticds.rag.TestBrains;
import com.pragmaticds.rag.TestRunGroups;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Constraint pins for V34, the additive Income Lab prototype schema.
 *
 * <p>Every assertion here is a plan bullet made executable: brain-scoped composite identities, one
 * production pointer per {@code (brain_id, instance_slug)}, immutable release rows, immutable
 * terminal run-document/result identities, unique run idempotency keys, bounded processing leases,
 * constrained lifecycle values including {@code INTERRUPTED}, ascending revision/history indexes,
 * ciphertext-only payload/message storage, FK-safe deletion order, and a value-free
 * {@code lab_document_registration}.
 *
 * <p>V38 deliberately relaxes one V34 guarantee: engine package identity is no longer globally
 * unique, because one immutable parse must be selectable by several instances in a brain. The
 * cross-brain isolation that uniqueness bought moved to {@code lab_engine_package_binding}, and
 * {@code V38MigrationTest} owns that pin now. Everything else V34 established still holds here.
 *
 * <p>Runs without a surrounding transaction ({@link Propagation#NOT_SUPPORTED}) because most pins
 * are negative: a rejected statement aborts its transaction, so autocommit is the only way to
 * assert one rejection and then keep going. Every row therefore uses freshly generated identifiers.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
class V34MigrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    /** A well-formed digest the immutability trigger must still refuse to write. */
    private static final String REWRITTEN_SHA = "b".repeat(64);
    private static final String INCOME = "income";

    @Autowired
    JdbcTemplate jdbc;

    /** V35 makes the prototype's formerly free-form Income identity an explicit registry row. */
    @BeforeEach
    void ensureIncomeRegistryFixture() {
        jdbc.update("INSERT INTO lab_instance (brain_id, slug, display_name, purpose) "
                        + "VALUES (?, ?, 'Income', 'Evaluate parsed income documents') "
                        + "ON CONFLICT (brain_id, slug) DO NOTHING",
                TestBrains.DEFAULT_ID, INCOME);
    }

    // ---------------------------------------------------------------- shape and privacy

    /** The tables V34 itself creates. Later migrations pin their own shapes. */
    private static final List<String> V34_TABLES = List.of(
            "lab_instance_release", "lab_instance_pointer", "lab_document_registration",
            "lab_run", "lab_run_document", "lab_run_payload", "lab_discussion_exchange",
            "lab_discussion_message", "lab_audit_event");

    @Test
    void createsEveryLabPrototypeTable() {
        for (String table : V34_TABLES) {
            assertEquals(1, tableCount(table), "expected table " + table);
        }
    }

    @Test
    void labDocumentRegistrationIsValueFree() {
        // Brain/instance/package/job/source UUIDs plus lifecycle timestamps ONLY: no filename,
        // bytes, values, source URL, storage key, or content hash.
        assertEquals(
                List.of("brain_id", "engine_job_id", "engine_package_id", "engine_source_id", "id",
                        "instance_slug", "last_read_at", "registered_at",
                        // V38 additions: still identity and mode only, no value ever.
                        "registration_mode", "selected_revision", "source_set_sha256"),
                columnsOf("lab_document_registration"));
    }

    @Test
    void noLabColumnCanCarryABytesFilenameOrSourceUrl() {
        for (String fragment : List.of("file", "url", "uri", "storage", "bucket", "path", "mime",
                "plaintext", "body", "prompt", "token", "secret", "credential", "raw", "content",
                "question", "answer", "report", "finding")) {
            Object[] arguments = new Object[V34_TABLES.size() + 1];
            for (int index = 0; index < V34_TABLES.size(); index++) {
                arguments[index] = V34_TABLES.get(index);
            }
            arguments[V34_TABLES.size()] = "%" + fragment + "%";
            List<String> offenders = jdbc.queryForList(
                    "SELECT table_name || '.' || column_name FROM information_schema.columns "
                            + "WHERE table_name IN ("
                            + String.join(",", java.util.Collections.nCopies(V34_TABLES.size(), "?"))
                            + ") AND column_name LIKE ?",
                    String.class, arguments);
            assertEquals(List.of(), offenders, "no Lab column may be named like '" + fragment + "'");
        }
    }

    @Test
    void theOnlyBinaryColumnsAreTheSixCiphertextAndNonceColumns() {
        // Enumerated rather than pattern-matched: a bytea column is where ciphertext lives, so a
        // new one has to be a deliberate act somebody signed off on. V42 added the third pair,
        // for a registration's sealed loan facts — income and Adjusted Value, which are borrower
        // financial figures and exist in the database only as ciphertext.
        assertEquals(
                List.of("lab_discussion_message.ciphertext", "lab_discussion_message.nonce",
                        "lab_registration_loan_facts.ciphertext",
                        "lab_registration_loan_facts.nonce",
                        "lab_run_payload.ciphertext", "lab_run_payload.nonce"),
                jdbc.queryForList(
                        "SELECT table_name || '.' || column_name FROM information_schema.columns "
                                + "WHERE table_name LIKE 'lab\\_%' AND udt_name = 'bytea' "
                                + "ORDER BY table_name, column_name",
                        String.class));
    }

    @Test
    void payloadAndMessageStorageIsCiphertextOnly() {
        // Their only character/JSON columns are the bounded enumerated discriminators; there is
        // no text, jsonb, or varchar column a plaintext report or discussion body could occupy.
        assertEquals(
                List.of("lab_run_payload.cipher_algorithm", "lab_run_payload.payload_type"),
                textLikeColumnsOf("lab_run_payload"));
        assertEquals(
                List.of("lab_discussion_message.cipher_algorithm", "lab_discussion_message.role"),
                textLikeColumnsOf("lab_discussion_message"));
    }

    // ---------------------------------------------------------------- releases and pointer

    @Test
    void onlyOneProductionPointerExistsPerBrainAndInstance() {
        assertEquals(2, primaryKeyColumnCount("lab_instance_pointer"),
                "lab_instance_pointer PK must be (brain_id, instance_slug)");

        UUID first = insertRelease(RELEASE_NUMBERS.incrementAndGet(), sha());
        UUID second = insertRelease(RELEASE_NUMBERS.incrementAndGet(), sha());
        jdbc.update("DELETE FROM lab_instance_pointer WHERE brain_id = ? AND instance_slug = ?",
                TestBrains.DEFAULT_ID, INCOME);
        insertPointer(first);

        assertRejected("a second production pointer for one (brain, instance)",
                () -> insertPointer(second));
    }

    @Test
    void releaseRowsAreImmutable() {
        UUID release = insertRelease(RELEASE_NUMBERS.incrementAndGet(), sha());

        assertRejected("updating an immutable release row", () -> jdbc.update(
                "UPDATE lab_instance_release SET manifest_sha256 = ? WHERE id = ?",
                REWRITTEN_SHA, release));
    }

    @Test
    void releaseNumbersAndManifestDigestsAreUniquePerBrainAndInstance() {
        insertRelease(4, sha());

        assertRejected("a duplicate release number", () -> insertRelease(4, sha()));

        String shared = sha();
        insertRelease(5, shared);
        assertRejected("a duplicate manifest digest, so repeated bootstrap is idempotent",
                () -> insertRelease(6, shared));
    }

    @Test
    void releaseProvenanceModeAndDigestShapeAreConstrained() {
        assertRejected("an unknown provenance mode",
                () -> jdbc.update(
                        "INSERT INTO lab_instance_release (id, brain_id, instance_slug, "
                                + "release_number, provenance_mode, manifest, manifest_sha256) "
                                + "VALUES (?, ?, ?, ?, 'WHATEVER', '{}'::jsonb, ?)",
                        UUID.randomUUID(), TestBrains.DEFAULT_ID, INCOME,
                        RELEASE_NUMBERS.incrementAndGet(), sha()));
        assertRejected("a manifest digest that is not lowercase 64-hex",
                () -> insertRelease(RELEASE_NUMBERS.incrementAndGet(), "NOTAHASH"));
    }

    // ---------------------------------------------------------------- registration binding

    @Test
    void anEnginePackageStillCannotCrossBrains() {
        // V34 bought this with a global unique on engine_package_id, which also forbade the
        // same-brain reuse Phase 3 exists to allow. V38 moved the guarantee to
        // lab_engine_package_binding; this asserts the isolation survived that move rather than
        // quietly disappearing with the constraint that used to provide it. V38MigrationTest
        // owns the positive case, that several instances in one brain may share a package.
        UUID pkg = UUID.randomUUID();
        insertRegistration(pkg, UUID.randomUUID(), INCOME);

        UUID otherBrain = UUID.randomUUID();
        jdbc.update("INSERT INTO brains (id, slug, display_name, is_default, is_active) "
                        + "VALUES (?, ?, 'Other brain', false, true)",
                otherBrain, "brain-" + otherBrain.toString().substring(0, 8));

        assertRejected("binding one engine package to a second brain",
                () -> jdbc.update("INSERT INTO lab_engine_package_binding "
                        + "(engine_package_id, brain_id) VALUES (?, ?)", pkg, otherBrain));
    }

    @Test
    void registrationInstanceSlugsAreBounded() {
        assertRejected("an unbounded instance slug",
                () -> insertRegistration(UUID.randomUUID(), UUID.randomUUID(), "Income Lab!"));
    }

    // ---------------------------------------------------------------- lab_run identity

    @Test
    void runIdempotencyKeysAreUniquePerBrainAndInstance() {
        String key = "key-" + UUID.randomUUID();
        insertProcessingRun(key);

        assertRejected("a replayed idempotency key inserting a second run",
                () -> insertProcessingRun(key));
    }

    @Test
    void aProcessingRunHoldsABoundedLeaseAndNoAnalysisRunId() {
        UUID run = insertProcessingRun("key-" + UUID.randomUUID());

        assertEquals(Boolean.TRUE, jdbc.queryForObject(
                "SELECT lease_expires_at IS NOT NULL AND terminal_at IS NULL "
                        + "AND analysis_run_id IS NULL FROM lab_run WHERE id = ?",
                Boolean.class, run));
        assertRejected("a PROCESSING run without a lease", () -> jdbc.update(
                "UPDATE lab_run SET lease_expires_at = NULL WHERE id = ?", run));
        assertRejected("an out-of-range attempt count", () -> jdbc.update(
                "UPDATE lab_run SET attempt = 99 WHERE id = ?", run));
    }

    @Test
    void aSuccessfulTerminalRunRequiresItsAnalysisRunId() {
        UUID run = insertProcessingRun("key-" + UUID.randomUUID());

        assertRejected("a SUCCEEDED run with no linked analysis_runs row", () -> jdbc.update(
                "UPDATE lab_run SET status = 'SUCCEEDED', lease_expires_at = NULL, "
                        + "terminal_at = now() WHERE id = ?", run));

        UUID analysisRun = insertAnalysisRun(OffsetDateTime.now());
        assertEquals(1, succeed(run, analysisRun));
    }

    @Test
    void analysisRunIdIsNullableWhilePROCESSINGAndUniqueWhenPresent() {
        UUID analysisRun = insertAnalysisRun(OffsetDateTime.now());
        succeed(insertProcessingRun("key-" + UUID.randomUUID()), analysisRun);

        UUID rival = insertProcessingRun("key-" + UUID.randomUUID());
        assertRejected("two Lab runs claiming one analysis_runs row",
                () -> succeed(rival, analysisRun));

        assertRejected("an analysis_run_id with no analysis_runs row",
                () -> succeed(rival, UUID.randomUUID()));
        assertRejected("pinning an analyzer row while still PROCESSING", () -> jdbc.update(
                "UPDATE lab_run SET analysis_run_id = ? WHERE id = ?",
                insertAnalysisRun(OffsetDateTime.now()), rival));
    }

    @Test
    void aRunReachesOneTerminalStateExactlyOnce() {
        UUID run = insertProcessingRun("key-" + UUID.randomUUID());
        assertEquals(1, jdbc.update(
                "UPDATE lab_run SET status = 'FAILED', failure_code = 'ENGINE_TIMEOUT', "
                        + "lease_expires_at = NULL, terminal_at = now() WHERE id = ?", run));

        assertRejected("a second terminal transition", () -> jdbc.update(
                "UPDATE lab_run SET status = 'INTERRUPTED' WHERE id = ?", run));
    }

    @Test
    void aRunCannotChangeItsReleaseOrDocumentIdentities() {
        UUID run = insertProcessingRun("key-" + UUID.randomUUID());
        UUID otherRelease = insertRelease(RELEASE_NUMBERS.incrementAndGet(), sha());

        assertRejected("repointing a run at another release", () -> jdbc.update(
                "UPDATE lab_run SET release_id = ? WHERE id = ?", otherRelease, run));
        assertRejected("repointing a run at another registration", () -> jdbc.update(
                "UPDATE lab_run SET registration_id = ? WHERE id = ?",
                insertRegistration(UUID.randomUUID(), UUID.randomUUID(), INCOME), run));
        assertRejected("rewriting a run's idempotency key", () -> jdbc.update(
                "UPDATE lab_run SET idempotency_key = 'stolen' WHERE id = ?", run));
    }

    @Test
    void runLifecycleValuesAreConstrainedAndIncludeInterrupted() {
        UUID run = insertProcessingRun("key-" + UUID.randomUUID());

        assertRejected("an unknown lifecycle value", () -> jdbc.update(
                "UPDATE lab_run SET status = 'DONE', lease_expires_at = NULL, "
                        + "terminal_at = now() WHERE id = ?", run));
        assertEquals(1, jdbc.update(
                "UPDATE lab_run SET status = 'INTERRUPTED', failure_code = 'RUN_LEASE_EXPIRED', "
                        + "lease_expires_at = NULL, terminal_at = now() WHERE id = ?", run));
    }

    @Test
    void runHistoryIsIndexedNewestFirst() {
        assertTrue(indexDefinition("idx_lab_run_history")
                        .contains("created_at DESC"),
                "run history must be indexed newest first");
    }

    // ---------------------------------------------------------------- run documents

    @Test
    void runDocumentIdentitiesAreImmutableAndDigestShaped() {
        UUID run = insertProcessingRun("key-" + UUID.randomUUID());
        UUID document = insertRunDocument(run, UUID.randomUUID(), 1);

        assertRejected("mutating a terminal run-document identity", () -> jdbc.update(
                "UPDATE lab_run_document SET package_revision = 2 WHERE id = ?", document));
        assertRejected("a malformed envelope digest",
                () -> insertRunDocument(run, UUID.randomUUID(), 1, "NOT-A-DIGEST", 512L));
        assertRejected("a non-positive revision",
                () -> insertRunDocument(run, UUID.randomUUID(), 0));
        assertRejected("a zero-length envelope",
                () -> insertRunDocument(run, UUID.randomUUID(), 1, sha(), 0L));
    }

    @Test
    void runDocumentRevisionsAreIndexedAscending() {
        String definition = indexDefinition("idx_lab_run_doc_revision");
        assertTrue(definition.contains("engine_package_id") && definition.contains("package_revision"),
                "revision history must be indexed by (package, revision): " + definition);
        assertTrue(!definition.toUpperCase(Locale.ROOT).contains("DESC"),
                "revision history index must be ascending: " + definition);
    }

    // ---------------------------------------------------------------- ciphertext storage

    @Test
    void payloadStorageAcceptsOnlyAesGcmCiphertextWithATwelveByteNonce() {
        UUID run = insertProcessingRun("key-" + UUID.randomUUID());

        assertRejected("an 11-byte nonce", () -> insertPayload(run, 11, 32, "AES-256-GCM"));
        assertRejected("a non-AES-GCM algorithm", () -> insertPayload(run, 12, 32, "ROT13"));
        assertRejected("a ciphertext shorter than the GCM tag",
                () -> insertPayload(run, 12, 8, "AES-256-GCM"));

        UUID payload = insertPayload(run, 12, 32, "AES-256-GCM");
        assertRejected("a second payload of one type for one run",
                () -> insertPayload(run, 12, 32, "AES-256-GCM"));
        assertRejected("mutating stored ciphertext", () -> jdbc.update(
                "UPDATE lab_run_payload SET ciphertext = ? WHERE id = ?", new byte[32], payload));
    }

    @Test
    void discussionMessagesAreImmutableCiphertextInTwoRolesPerExchange() {
        UUID exchange = insertExchange(insertProcessingRun("key-" + UUID.randomUUID()), 1);
        UUID user = insertMessage(exchange, "USER", 1);
        insertMessage(exchange, "ASSISTANT", 2);

        assertRejected("a third message in one exchange",
                () -> insertMessage(exchange, "USER", 3));
        assertRejected("an unknown message role",
                () -> insertMessage(exchange, "SYSTEM", 4));
        assertRejected("mutating a stored message", () -> jdbc.update(
                "UPDATE lab_discussion_message SET ordinal = 9 WHERE id = ?", user));
    }

    // ---------------------------------------------------------------- discussion exchanges

    @Test
    void exchangesAreUniquelyKeyedPerRunWithTwoMonotonicSlots() {
        UUID run = insertProcessingRun("key-" + UUID.randomUUID());
        UUID first = insertExchange(run, 1);
        assertNotNull(first);

        assertEquals("1/2", slotsOf(first));

        assertEquals("3/4", slotsOf(insertExchange(run, 2)));

        assertRejected("a duplicate transcript position", () -> insertExchange(run, 2));
        assertRejected("hand-assigned overlapping slots", () -> jdbc.update(
                "INSERT INTO lab_discussion_exchange (id, run_id, idempotency_key, "
                        + "sequence_number, user_ordinal, assistant_ordinal, status, "
                        + "lease_expires_at) VALUES (?, ?, ?, 3, 2, 3, 'PROCESSING', now())",
                UUID.randomUUID(), run, "key-" + UUID.randomUUID()));
    }

    @Test
    void exchangeKeysAreUniquePerRunAndLifecycleIsBounded() {
        UUID run = insertProcessingRun("key-" + UUID.randomUUID());
        String key = "msg-" + UUID.randomUUID();
        UUID exchange = insertExchange(run, 1, key);

        assertRejected("a replayed message key inserting a second exchange",
                () -> insertExchange(run, 2, key));
        assertRejected("an unknown exchange lifecycle value", () -> jdbc.update(
                "UPDATE lab_discussion_exchange SET status = 'SENT', lease_expires_at = NULL, "
                        + "terminal_at = now() WHERE id = ?", exchange));
        assertEquals(1, jdbc.update(
                "UPDATE lab_discussion_exchange SET status = 'INTERRUPTED', "
                        + "failure_code = 'EXCHANGE_LEASE_EXPIRED', lease_expires_at = NULL, "
                        + "terminal_at = now() WHERE id = ?", exchange));
        assertRejected("a second terminal transition", () -> jdbc.update(
                "UPDATE lab_discussion_exchange SET status = 'SUCCEEDED' WHERE id = ?", exchange));
    }

    // ---------------------------------------------------------------- audit

    @Test
    void auditEventsAreAppendOnly() {
        UUID event = insertAudit(null);

        assertRejected("updating an append-only audit row", () -> jdbc.update(
                "UPDATE lab_audit_event SET status = 'SUCCEEDED' WHERE id = ?", event));
        assertRejected("deleting an append-only audit row", () -> jdbc.update(
                "DELETE FROM lab_audit_event WHERE id = ?", event));
    }

    @Test
    void auditMetadataHoldsCountsAndBooleansOnly() {
        assertNotNull(insertAudit("{\"documentCount\": 2, \"reviewRequired\": true}"));

        assertRejected("audit metadata carrying a string",
                () -> insertAudit("{\"note\": \"anything at all\"}"));
        assertRejected("audit metadata carrying a nested object",
                () -> insertAudit("{\"nested\": {\"count\": 1}}"));
        assertRejected("audit metadata carrying an array",
                () -> insertAudit("{\"values\": [1, 2]}"));
    }

    @Test
    void auditRowsCarryActionStatusSubjectAndBoundedActorOnly() {
        assertEquals(
                List.of("action", "actor", "brain_id", "correlation_id", "created_at",
                        "failure_code", "id", "metadata", "status", "subject_id", "subject_type"),
                columnsOf("lab_audit_event"));
        // Bounded proxy actor and correlation id — never an unbounded identity string.
        assertEquals(64, characterMaxLength("lab_audit_event", "actor"));
        assertEquals(64, characterMaxLength("lab_audit_event", "correlation_id"));
    }

    // ---------------------------------------------------------------- deletion order

    @Test
    void labRowsMustBeDeletedInFkSafeOrder() {
        UUID run = insertProcessingRun("key-" + UUID.randomUUID());
        UUID document = insertRunDocument(run, UUID.randomUUID(), 1);
        UUID payload = insertPayload(run, 12, 32, "AES-256-GCM");
        UUID exchange = insertExchange(run, 1);
        UUID message = insertMessage(exchange, "USER", 1);
        UUID analysisRun = insertAnalysisRun(OffsetDateTime.now());
        succeed(run, analysisRun);

        assertRejected("deleting a run underneath its children",
                () -> jdbc.update("DELETE FROM lab_run WHERE id = ?", run));
        assertRejected("deleting the linked analyzer row underneath the Lab run",
                () -> jdbc.update("DELETE FROM analysis_runs WHERE id = ?", analysisRun));

        assertEquals(1, jdbc.update("DELETE FROM lab_discussion_message WHERE id = ?", message));
        assertEquals(1, jdbc.update("DELETE FROM lab_discussion_exchange WHERE id = ?", exchange));
        assertEquals(1, jdbc.update("DELETE FROM lab_run_payload WHERE id = ?", payload));
        assertEquals(1, jdbc.update("DELETE FROM lab_run_document WHERE id = ?", document));
        assertEquals(1, jdbc.update("DELETE FROM lab_run WHERE id = ?", run));
        assertEquals(1, jdbc.update("DELETE FROM analysis_runs WHERE id = ?", analysisRun));
    }

    // ---------------------------------------------------------------- helpers

    private static void assertRejected(String what, Runnable statement) {
        assertThrows(DataAccessException.class, statement::run, "must reject " + what);
    }

    private static String sha() {
        return UUID.randomUUID().toString().replace("-", "").repeat(2);
    }

    /** Release numbers must not collide between tests that share one autocommitting database. */
    private static final AtomicInteger RELEASE_NUMBERS = new AtomicInteger(1_000);

    private String slotsOf(UUID exchangeId) {
        return jdbc.queryForObject(
                "SELECT user_ordinal || '/' || assistant_ordinal FROM lab_discussion_exchange "
                        + "WHERE id = ?", String.class, exchangeId);
    }

    private int tableCount(String table) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_name = ?",
                Integer.class, table);
        return count == null ? 0 : count;
    }

    private List<String> columnsOf(String table) {
        return jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = ? "
                        + "ORDER BY column_name",
                String.class, table);
    }

    private List<String> textLikeColumnsOf(String table) {
        return jdbc.queryForList(
                "SELECT table_name || '.' || column_name FROM information_schema.columns "
                        + "WHERE table_name = ? AND udt_name IN ('text', 'varchar', 'jsonb', 'json') "
                        + "ORDER BY column_name",
                String.class, table);
    }

    private int characterMaxLength(String table, String column) {
        Integer length = jdbc.queryForObject(
                "SELECT character_maximum_length FROM information_schema.columns "
                        + "WHERE table_name = ? AND column_name = ?",
                Integer.class, table, column);
        return length == null ? 0 : length;
    }

    private int primaryKeyColumnCount(String table) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.key_column_usage k "
                        + "JOIN information_schema.table_constraints c "
                        + "  ON k.constraint_name = c.constraint_name "
                        + "WHERE c.table_name = ? AND c.constraint_type = 'PRIMARY KEY'",
                Integer.class, table);
        return count == null ? 0 : count;
    }

    private String indexDefinition(String indexName) {
        return jdbc.queryForObject("SELECT indexdef FROM pg_indexes WHERE indexname = ?",
                String.class, indexName);
    }

    private UUID insertRelease(int number, String manifestSha) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO lab_instance_release (id, brain_id, instance_slug, "
                        + "release_number, provenance_mode, manifest, manifest_sha256) "
                        + "VALUES (?, ?, ?, ?, 'PRODUCTION', '{\"analyzer\":\"income-v2\"}'::jsonb, ?)",
                id, TestBrains.DEFAULT_ID, INCOME, number, manifestSha);
        return id;
    }

    private void insertPointer(UUID releaseId) {
        jdbc.update("INSERT INTO lab_instance_pointer (brain_id, instance_slug, "
                        + "production_release_id) VALUES (?, ?, ?)",
                TestBrains.DEFAULT_ID, INCOME, releaseId);
    }

    private UUID insertRegistration(UUID packageId, UUID jobId, String slug) {
        UUID id = UUID.randomUUID();
        // V38 binds a package to exactly one brain, and registrations carry a composite FK to
        // that binding, so the binding has to exist first.
        jdbc.update("INSERT INTO lab_engine_package_binding (engine_package_id, brain_id) "
                        + "VALUES (?, ?) ON CONFLICT (engine_package_id) DO NOTHING",
                packageId, TestBrains.DEFAULT_ID);
        jdbc.update("INSERT INTO lab_document_registration (id, brain_id, instance_slug, "
                        + "engine_package_id, engine_job_id, engine_source_id) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                id, TestBrains.DEFAULT_ID, slug, packageId, jobId, UUID.randomUUID());
        return id;
    }

    private UUID insertProcessingRun(String idempotencyKey) {
        UUID id = UUID.randomUUID();
        // V39 made group membership mandatory, so a run fixture now carries the one-member group
        // V39's own backfill would have given it. See TestRunGroups.
        TestRunGroups.insertSoloGroup(jdbc, id, TestBrains.DEFAULT_ID);
        jdbc.update("INSERT INTO lab_run (id, brain_id, instance_slug, idempotency_key, "
                        + "release_id, registration_id, status, lease_expires_at, "
                        + TestRunGroups.membershipColumns() + ") "
                        + "VALUES (?, ?, ?, ?, ?, ?, 'PROCESSING', now() + interval '5 minutes', "
                        + TestRunGroups.membershipValues(id) + ")",
                id, TestBrains.DEFAULT_ID, INCOME, idempotencyKey,
                insertRelease(RELEASE_NUMBERS.incrementAndGet(), sha()),
                insertRegistration(UUID.randomUUID(), UUID.randomUUID(), INCOME));
        return id;
    }

    private int succeed(UUID runId, UUID analysisRunId) {
        return jdbc.update("UPDATE lab_run SET status = 'SUCCEEDED', analysis_run_id = ?, "
                + "lease_expires_at = NULL, terminal_at = now() WHERE id = ?", analysisRunId, runId);
    }

    private UUID insertRunDocument(UUID runId, UUID packageId, int revision) {
        return insertRunDocument(runId, packageId, revision, sha(), 4_096L);
    }

    private UUID insertRunDocument(
            UUID runId, UUID packageId, int revision, String envelopeSha, long sizeBytes) {
        UUID id = UUID.randomUUID();
        UUID registration = jdbc.queryForObject(
                "SELECT registration_id FROM lab_run WHERE id = ?", UUID.class, runId);
        jdbc.update("INSERT INTO lab_run_document (id, run_id, registration_id, "
                        + "engine_package_id, package_revision, processing_job_id, "
                        + "parse_generation, envelope_version, canonicalization_version, "
                        + "envelope_sha256, envelope_size_bytes, source_set_sha256, "
                        + "reuse_eligibility, document_count, page_count) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 1, '1.0.0', 'DOCENGINE-C14N-1', ?, ?, ?, "
                        + "'ELIGIBLE', 1, 2)",
                id, runId, registration, packageId, revision, UUID.randomUUID(), envelopeSha,
                sizeBytes, sha());
        return id;
    }

    private UUID insertPayload(UUID runId, int nonceLength, int ciphertextLength, String algorithm) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO lab_run_payload (id, run_id, payload_type, cipher_algorithm, "
                        + "nonce, ciphertext) VALUES (?, ?, 'ANALYSIS_OUTPUT', ?, ?, ?)",
                id, runId, algorithm, new byte[nonceLength], new byte[ciphertextLength]);
        return id;
    }

    private UUID insertExchange(UUID runId, int sequence) {
        return insertExchange(runId, sequence, "msg-" + UUID.randomUUID());
    }

    private UUID insertExchange(UUID runId, int sequence, String idempotencyKey) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO lab_discussion_exchange (id, run_id, idempotency_key, "
                        + "sequence_number, user_ordinal, assistant_ordinal, status, "
                        + "lease_expires_at) VALUES (?, ?, ?, ?, ?, ?, 'PROCESSING', "
                        + "now() + interval '2 minutes')",
                id, runId, idempotencyKey, sequence, 2 * sequence - 1, 2 * sequence);
        return id;
    }

    private UUID insertMessage(UUID exchangeId, String role, int ordinal) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO lab_discussion_message (id, exchange_id, role, ordinal, "
                        + "cipher_algorithm, nonce, ciphertext) "
                        + "VALUES (?, ?, ?, ?, 'AES-256-GCM', ?, ?)",
                id, exchangeId, role, ordinal, new byte[12], new byte[48]);
        return id;
    }

    private UUID insertAudit(String metadataJson) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO lab_audit_event (id, brain_id, action, status, subject_type, "
                        + "subject_id, actor, correlation_id, metadata) "
                        + "VALUES (?, ?, 'RUN_START', 'FAILED', 'RUN', ?, "
                        + "'anonymous-admin-prototype', ?, ?::jsonb)",
                id, TestBrains.DEFAULT_ID, UUID.randomUUID(), "corr-" + UUID.randomUUID(),
                metadataJson);
        return id;
    }

    private UUID insertAnalysisRun(OffsetDateTime createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO analysis_runs (id, brain_id, analyzer_slug, envelope_version, "
                        + "status, created_at) VALUES (?, ?, 'income', 'v2', 'SUCCESS', ?)",
                id, TestBrains.DEFAULT_ID, Timestamp.from(createdAt.toInstant()));
        return id;
    }
}
