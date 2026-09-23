package com.pragmaticds.rag.migration;

import com.pragmaticds.rag.TestBrains;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Database contract for brain-scoped corpus collections and immutable snapshots. */
@Testcontainers
class V37MigrationTest {

    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Test
    void backfillsSharedAndNormalizedScopeCollectionsPerBrainWithoutCreatingSnapshots()
            throws Exception {
        String schema = newSchema();
        try {
            migrate(schema, "36");
            UUID secondBrain = insertBrain(schema);

            UUID shared = insertDocument(schema, TestBrains.DEFAULT_ID, null, "shared-v1", HASH_A);
            UUID income = insertDocument(schema, TestBrains.DEFAULT_ID, "income", "income-v1", HASH_B);
            UUID incomeV2 = insertDocument(schema, TestBrains.DEFAULT_ID, " Income V2 ", "income-v2", HASH_A);
            UUID secondShared = insertDocument(schema, secondBrain, null, "shared-v2", HASH_B);
            UUID secondIncome = insertDocument(schema, secondBrain, "income", "income-v2", HASH_A);

            migrate(schema, null);

            assertEquals(1, collectionCount(schema, TestBrains.DEFAULT_ID, "shared"));
            assertEquals(1, collectionCount(schema, TestBrains.DEFAULT_ID, "income"));
            assertEquals(1, collectionCount(schema, TestBrains.DEFAULT_ID, "income-v2"));
            assertEquals(1, collectionCount(schema, secondBrain, "shared"));
            assertEquals(1, collectionCount(schema, secondBrain, "income"));
            assertEquals(5, intValue(schema, "SELECT count(*) FROM brain_corpus_collection_document"));
            assertEquals(0, intValue(schema, "SELECT count(*) FROM brain_corpus_snapshot"));

            assertMembership(schema, TestBrains.DEFAULT_ID, "shared", shared);
            assertMembership(schema, TestBrains.DEFAULT_ID, "income", income);
            assertMembership(schema, TestBrains.DEFAULT_ID, "income-v2", incomeV2);
            assertMembership(schema, secondBrain, "shared", secondShared);
            assertMembership(schema, secondBrain, "income", secondIncome);
            assertEquals("Income V2", stringValue(schema,
                    "SELECT display_name FROM brain_corpus_collection "
                            + "WHERE brain_id = ? AND slug = 'income-v2'", TestBrains.DEFAULT_ID));
        } finally {
            dropSchema(schema);
        }
    }

    @Test
    void collectionShapeEnforcesVersionsStatesUniqueMembershipAndSameBrainCloneProvenance()
            throws Exception {
        String schema = newSchema();
        try {
            migrate(schema, null);
            UUID secondBrain = insertBrain(schema);
            UUID defaultDocument = insertDocument(schema, TestBrains.DEFAULT_ID, null, "v1", HASH_A);
            UUID secondDocument = insertDocument(schema, secondBrain, null, "v1", HASH_B);
            UUID source = insertCollection(schema, TestBrains.DEFAULT_ID, "source", "ACTIVE", 1, null);
            UUID disabled = insertCollection(schema, TestBrains.DEFAULT_ID, "disabled", "DISABLED", 2, source);
            UUID secondSource = insertCollection(schema, secondBrain, "source", "ACTIVE", 1, null);

            insertAudit(schema, TestBrains.DEFAULT_ID, "COLLECTION", source);
            insertAudit(schema, TestBrains.DEFAULT_ID, "SNAPSHOT", UUID.randomUUID());

            assertEquals(1, uniqueConstraintWithColumns(
                    schema, "brain_documents", "id", "brain_id"));
            assertEquals(1, uniqueConstraintWithColumns(
                    schema, "brain_corpus_collection", "id", "brain_id"));
            assertTrue(indexExists(schema, "idx_corpus_collection_brain_state"));
            assertTrue(indexExists(schema, "idx_corpus_membership_brain_document"));
            assertEquals(2L, longValue(schema,
                    "SELECT collection_version FROM brain_corpus_collection WHERE id = ?", disabled));

            insertMembership(schema, source, TestBrains.DEFAULT_ID, defaultDocument);
            assertRejected(() -> insertMembership(schema, source, TestBrains.DEFAULT_ID, defaultDocument));
            assertRejected(() -> insertMembership(schema, source, TestBrains.DEFAULT_ID, secondDocument));
            assertRejected(() -> insertMembership(schema, secondSource, secondBrain, defaultDocument));

            assertRejected(() -> insertCollection(
                    schema, TestBrains.DEFAULT_ID, "source", "ACTIVE", 1, null));
            assertRejected(() -> insertCollection(
                    schema, TestBrains.DEFAULT_ID, "bad-state", "PAUSED", 1, null));
            assertRejected(() -> insertCollection(
                    schema, TestBrains.DEFAULT_ID, "bad-version", "ACTIVE", 0, null));
            assertRejected(() -> insertCollection(
                    schema, TestBrains.DEFAULT_ID, "cross-brain-clone", "ACTIVE", 1, secondSource));
        } finally {
            dropSchema(schema);
        }
    }

    @Test
    void snapshotRowsAreImmutableOrderedBrainScopedAndRequireVersionedHashedDocuments()
            throws Exception {
        String schema = newSchema();
        try {
            migrate(schema, null);
            UUID secondBrain = insertBrain(schema);
            UUID document = insertDocument(schema, TestBrains.DEFAULT_ID, null, "2026.08", HASH_A);
            UUID secondDocument = insertDocument(schema, secondBrain, null, "2026.08", HASH_B);
            UUID collection = insertCollection(schema, TestBrains.DEFAULT_ID, "shared", "ACTIVE", 3, null);
            UUID unattachedCollection = insertCollection(
                    schema, TestBrains.DEFAULT_ID, "unattached", "ACTIVE", 1, null);
            UUID secondCollection = insertCollection(schema, secondBrain, "shared", "ACTIVE", 1, null);
            insertMembership(schema, collection, TestBrains.DEFAULT_ID, document);

            UUID snapshot = insertSnapshot(schema, TestBrains.DEFAULT_ID, HASH_A);
            insertSnapshotCollection(schema, snapshot, TestBrains.DEFAULT_ID, 0, collection, 3);
            insertSnapshotDocument(schema, snapshot, TestBrains.DEFAULT_ID, collection,
                    document, "2026.08", HASH_A);

            assertTrue(indexExists(schema, "idx_corpus_snapshot_brain_created"));
            assertTrue(indexExists(schema, "idx_corpus_snapshot_document_lookup"));
            assertEquals(1, uniqueConstraintWithColumns(
                    schema, "brain_corpus_snapshot", "brain_id", "manifest_sha256"));
            assertEquals(1, uniqueConstraintWithColumns(
                    schema, "brain_corpus_snapshot_collection", "snapshot_id", "collection_id"));

            assertRejected(() -> insertSnapshot(schema, TestBrains.DEFAULT_ID, HASH_A));
            UUID secondSnapshot = insertSnapshot(schema, secondBrain, HASH_A);
            assertTrue(secondSnapshot != null, "manifest hashes are unique per brain, not globally");

            assertRejected(() -> insertSnapshotCollection(
                    schema, snapshot, TestBrains.DEFAULT_ID, 0, unattachedCollection, 1));
            assertRejected(() -> insertSnapshotCollection(
                    schema, snapshot, TestBrains.DEFAULT_ID, -1, unattachedCollection, 1));
            assertRejected(() -> insertSnapshotCollection(
                    schema, snapshot, TestBrains.DEFAULT_ID, 1, unattachedCollection, 0));
            assertRejected(() -> insertSnapshotCollection(
                    schema, snapshot, TestBrains.DEFAULT_ID, 1, secondCollection, 1));

            assertRejected(() -> insertSnapshotDocument(schema, snapshot, TestBrains.DEFAULT_ID,
                    unattachedCollection, document, "2026.08", HASH_A));
            assertRejected(() -> insertSnapshotDocument(schema, snapshot, TestBrains.DEFAULT_ID,
                    collection, secondDocument, "2026.08", HASH_B));
            assertRejected(() -> insertSnapshotDocument(schema, snapshot, TestBrains.DEFAULT_ID,
                    collection, document, "", HASH_A));
            assertRejected(() -> insertSnapshotDocument(schema, snapshot, TestBrains.DEFAULT_ID,
                    collection, document, null, HASH_A));
            assertRejected(() -> insertSnapshotDocument(schema, snapshot, TestBrains.DEFAULT_ID,
                    collection, document, "2026.08", "ABC"));
            assertRejected(() -> insertSnapshotDocument(schema, snapshot, TestBrains.DEFAULT_ID,
                    collection, document, "2026.08", HASH_A));

            assertRejected(() -> execute(schema,
                    "UPDATE brain_corpus_snapshot SET manifest = '{}'::jsonb WHERE id = ?", snapshot));
            assertRejected(() -> execute(schema,
                    "DELETE FROM brain_corpus_snapshot WHERE id = ?", snapshot));
            assertRejected(() -> execute(schema,
                    "UPDATE brain_corpus_snapshot_collection SET collection_version = 4 "
                            + "WHERE snapshot_id = ? AND collection_id = ?", snapshot, collection));
            assertRejected(() -> execute(schema,
                    "DELETE FROM brain_corpus_snapshot_collection "
                            + "WHERE snapshot_id = ? AND collection_id = ?", snapshot, collection));
            assertRejected(() -> execute(schema,
                    "UPDATE brain_corpus_snapshot_document SET document_version = 'changed' "
                            + "WHERE snapshot_id = ? AND document_id = ?", snapshot, document));
            assertRejected(() -> execute(schema,
                    "DELETE FROM brain_corpus_snapshot_document "
                            + "WHERE snapshot_id = ? AND document_id = ?", snapshot, document));
        } finally {
            dropSchema(schema);
        }
    }

    @Test
    void documentDeletionCascadesCollectionMembershipWhileSnapshotsPinTheirDocuments()
            throws Exception {
        String schema = newSchema();
        try {
            migrate(schema, null);
            UUID brain = insertBrain(schema);
            UUID collection = insertCollection(schema, brain, "corpus", "ACTIVE", 1, null);

            UUID removable = insertDocument(schema, brain, null, "2026.08", HASH_A);
            insertMembership(schema, collection, brain, removable);
            UUID frozen = insertDocument(schema, brain, null, "2026.08", HASH_B);
            insertMembership(schema, collection, brain, frozen);
            UUID snapshot = insertSnapshot(schema, brain, HASH_A);
            insertSnapshotCollection(schema, snapshot, brain, 0, collection, 1);
            insertSnapshotDocument(schema, snapshot, brain, collection, frozen, "2026.08", HASH_B);

            // The admin document-delete route is not feature-flagged, so mutable collection
            // membership must never make a document undeletable. A backfilled corpus would
            // otherwise freeze every existing document in place while the feature is off.
            execute(schema, "DELETE FROM brain_documents WHERE id = ?", removable);
            assertEquals(0, intValue(schema, "SELECT count(*) FROM brain_corpus_collection_document "
                    + "WHERE document_id = ?", removable));

            // A frozen snapshot is a durable execution fact, so its evidence stays pinned.
            assertRejected(() -> execute(schema, "DELETE FROM brain_documents WHERE id = ?", frozen));
            assertEquals(1, intValue(schema, "SELECT count(*) FROM brain_documents WHERE id = ?", frozen));
            assertEquals(1, intValue(schema, "SELECT count(*) FROM brain_corpus_snapshot_document "
                    + "WHERE document_id = ?", frozen));
        } finally {
            dropSchema(schema);
        }
    }

    @Test
    void backfillSkipsInactiveDocumentsThatTheCollectionApiWouldReject() throws Exception {
        String schema = newSchema();
        try {
            migrate(schema, "36");
            UUID active = insertDocument(schema, TestBrains.DEFAULT_ID, null, "active-v1", HASH_A);
            UUID retired = insertDocument(schema, TestBrains.DEFAULT_ID, null, "retired-v1", HASH_B);
            UUID retiredScope = insertDocument(
                    schema, TestBrains.DEFAULT_ID, "income", "retired-v2", HASH_A);
            execute(schema, "UPDATE brain_documents SET is_active = FALSE WHERE id IN (?, ?)",
                    retired, retiredScope);

            migrate(schema, null);

            // replaceMembership rejects inactive documents, so a backfilled collection must not
            // contain rows its own API would refuse — otherwise it can never be round-tripped.
            assertMembership(schema, TestBrains.DEFAULT_ID, "shared", active);
            assertEquals(1, intValue(schema,
                    "SELECT count(*) FROM brain_corpus_collection_document"));
            assertEquals(0, collectionCount(schema, TestBrains.DEFAULT_ID, "income"));
        } finally {
            dropSchema(schema);
        }
    }

    private int collectionCount(String schema, UUID brainId, String slug) throws Exception {
        return intValue(schema, "SELECT count(*) FROM brain_corpus_collection "
                + "WHERE brain_id = ? AND slug = ?", brainId, slug);
    }

    private void assertMembership(String schema, UUID brainId, String slug, UUID documentId)
            throws Exception {
        assertEquals(1, intValue(schema,
                "SELECT count(*) FROM brain_corpus_collection_document membership "
                        + "JOIN brain_corpus_collection collection ON collection.id = membership.collection_id "
                        + "WHERE collection.brain_id = ? AND collection.slug = ? "
                        + "AND membership.document_id = ?",
                brainId, slug, documentId));
    }

    private UUID insertBrain(String schema) throws Exception {
        UUID id = UUID.randomUUID();
        execute(schema, "INSERT INTO brains (id, slug, display_name, is_default, is_active) "
                + "VALUES (?, ?, 'Second brain', false, true)", id, "brain-" + shortId(id));
        return id;
    }

    private UUID insertDocument(String schema, UUID brainId, String analyzerScope,
                                String version, String hash) throws Exception {
        UUID id = UUID.randomUUID();
        execute(schema, "INSERT INTO brain_documents "
                        + "(id, brain_id, title, source_name, source_type, file_name, visibility, "
                        + "trust_level, document_version, content_sha256, analyzer_scope) "
                        + "VALUES (?, ?, ?, 'Synthetic', 'AGENCY_GUIDELINE', ?, 'INTERNAL', "
                        + "'APPROVED', ?, ?, ?)",
                id, brainId, "Document " + shortId(id), shortId(id) + ".md",
                version, hash, analyzerScope);
        return id;
    }

    private UUID insertCollection(String schema, UUID brainId, String slug, String state,
                                  long version, UUID clonedFromId) throws Exception {
        UUID id = UUID.randomUUID();
        execute(schema, "INSERT INTO brain_corpus_collection "
                        + "(id, brain_id, slug, display_name, state, collection_version, cloned_from_id) "
                        + "VALUES (?, ?, ?, 'Synthetic collection', ?, ?, ?)",
                id, brainId, slug, state, version, clonedFromId);
        return id;
    }

    private void insertMembership(String schema, UUID collectionId, UUID brainId, UUID documentId)
            throws Exception {
        execute(schema, "INSERT INTO brain_corpus_collection_document "
                        + "(collection_id, brain_id, document_id) VALUES (?, ?, ?)",
                collectionId, brainId, documentId);
    }

    private void insertAudit(String schema, UUID brainId, String subjectType, UUID subjectId)
            throws Exception {
        execute(schema, "INSERT INTO lab_audit_event "
                        + "(brain_id, action, status, subject_type, subject_id, actor, metadata) "
                        + "VALUES (?, 'CORPUS_TEST', 'SUCCEEDED', ?, ?, 'test', "
                        + "'{\"documentCount\":1}'::jsonb)",
                brainId, subjectType, subjectId);
    }

    private UUID insertSnapshot(String schema, UUID brainId, String hash) throws Exception {
        UUID id = UUID.randomUUID();
        execute(schema, "INSERT INTO brain_corpus_snapshot "
                        + "(id, brain_id, manifest, manifest_sha256) "
                        + "VALUES (?, ?, '{\"manifestVersion\":1}'::jsonb, ?)",
                id, brainId, hash);
        return id;
    }

    private void insertSnapshotCollection(String schema, UUID snapshotId, UUID brainId,
                                          int position, UUID collectionId, long version)
            throws Exception {
        execute(schema, "INSERT INTO brain_corpus_snapshot_collection "
                        + "(snapshot_id, brain_id, position, collection_id, collection_version) "
                        + "VALUES (?, ?, ?, ?, ?)",
                snapshotId, brainId, position, collectionId, version);
    }

    private void insertSnapshotDocument(String schema, UUID snapshotId, UUID brainId,
                                        UUID collectionId, UUID documentId,
                                        String version, String hash) throws Exception {
        execute(schema, "INSERT INTO brain_corpus_snapshot_document "
                        + "(snapshot_id, brain_id, collection_id, document_id, document_version, "
                        + "content_sha256, visibility, trust_level) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 'INTERNAL', 'APPROVED')",
                snapshotId, brainId, collectionId, documentId, version, hash);
    }

    private int uniqueConstraintWithColumns(String schema, String table, String... columns)
            throws Exception {
        String expected = "{" + String.join(",", columns) + "}";
        return intValue(schema, "SELECT count(*) FROM information_schema.table_constraints tc "
                        + "WHERE tc.table_schema = current_schema() AND tc.table_name = ? "
                        + "AND tc.constraint_type = 'UNIQUE' AND ARRAY(SELECT key.column_name::text "
                        + "FROM information_schema.key_column_usage key WHERE key.constraint_schema "
                        + "= tc.constraint_schema AND key.constraint_name = tc.constraint_name "
                        + "ORDER BY key.ordinal_position) = ?::text[]",
                table, expected);
    }

    private boolean indexExists(String schema, String index) throws Exception {
        return intValue(schema, "SELECT count(*) FROM pg_indexes "
                + "WHERE schemaname = current_schema() AND indexname = ?", index) == 1;
    }

    private void migrate(String schema, String target) {
        var configuration = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration");
        if (target != null) {
            configuration.target(target);
        }
        configuration.load().migrate();
    }

    private String newSchema() {
        return "v37_" + UUID.randomUUID().toString().replace("-", "");
    }

    private void dropSchema(String schema) throws SQLException {
        try (Connection connection = connection(schema); Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    private void execute(String schema, String sql, Object... values) throws Exception {
        try (Connection connection = connection(schema);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            statement.executeUpdate();
        }
    }

    private String stringValue(String schema, String sql, Object... values) throws Exception {
        try (Connection connection = connection(schema);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next(), "query must return one row: " + sql);
                return result.getString(1);
            }
        }
    }

    private int intValue(String schema, String sql, Object... values) throws Exception {
        return Math.toIntExact(longValue(schema, sql, values));
    }

    private long longValue(String schema, String sql, Object... values) throws Exception {
        try (Connection connection = connection(schema);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next(), "query must return one row: " + sql);
                return result.getLong(1);
            }
        }
    }

    private Connection connection(String schema) throws SQLException {
        Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET search_path TO " + schema);
        }
        return connection;
    }

    private void bind(PreparedStatement statement, Object... values) throws SQLException {
        for (int index = 0; index < values.length; index++) {
            statement.setObject(index + 1, values[index]);
        }
    }

    private void assertRejected(SqlStatement statement) {
        assertThrows(Exception.class, statement::run);
    }

    private static String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }

    @FunctionalInterface
    private interface SqlStatement {
        void run() throws Exception;
    }
}
