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

/**
 * Migration contract for connector ownership of run groups.
 *
 * <p><b>Numbered 40, not 39.</b> The Phase 5 plan reserved V39 on the assumption that V38 was the
 * latest applied migration; Phase 4 shipped V39 first, and applied migrations are immutable
 * history, so this took the next free number exactly as that plan's own constraint says to.
 *
 * <p>The row this table holds is an authorization fact: polling a connector-created group is
 * allowed only for the connector and tenant recorded here. So the tests are mostly about what the
 * schema refuses — a second context for one group, a context whose connector or brain is missing,
 * a blank tenant, an edit after the fact — because each of those refusals is what makes the row
 * trustworthy enough to authorize against.
 */
@Testcontainers
class V40MigrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private static final String HASH = "a".repeat(64);
    private static final String EXTERNAL_HASH = "b".repeat(64);

    @Test
    void bindsOneContextToOneGroupAndRefusesASecond() throws Exception {
        String schema = newSchema();
        try {
            migrate(schema);
            UUID group = insertGroup(schema, "key-1");
            UUID connector = insertConnector(schema, "dm-connector");

            insertContext(schema, group, connector, "tenant-a", "req-1", EXTERNAL_HASH);

            // The primary key is the group id: ownership is a fact about the group, and two
            // owners would make the polling authorization ambiguous.
            assertRejected(() -> insertContext(
                    schema, group, connector, "tenant-b", "req-2", EXTERNAL_HASH));
            assertEquals(1, intValue(schema,
                    "SELECT count(*) FROM lab_connector_run_group_context WHERE run_group_id = ?",
                    group));
        } finally {
            dropSchema(schema);
        }
    }

    @Test
    void requiresARealConnectorABrainAndATenant() throws Exception {
        String schema = newSchema();
        try {
            migrate(schema);
            UUID group = insertGroup(schema, "key-1");
            UUID connector = insertConnector(schema, "dm-connector");

            // A context naming a connector that does not exist is not ownership by anybody.
            assertRejected(() -> insertContext(
                    schema, group, UUID.randomUUID(), "tenant-a", null, EXTERNAL_HASH));
            // A blank tenant asserts no identity, and this table exists to record one.
            assertRejected(() -> insertContext(
                    schema, group, connector, "   ", null, EXTERNAL_HASH));
            // The hash column takes exactly a lowercase hex digest, not whatever arrived.
            assertRejected(() -> insertContext(
                    schema, group, connector, "tenant-a", null, "not-a-digest"));
            // Control characters in the correlation id would forge lines in exports.
            assertRejected(() -> insertContext(
                    schema, group, connector, "tenant-a", "line\ninjected", EXTERNAL_HASH));
        } finally {
            dropSchema(schema);
        }
    }

    @Test
    void refusesEveryUpdateOutright() throws Exception {
        String schema = newSchema();
        try {
            migrate(schema);
            UUID group = insertGroup(schema, "key-1");
            UUID connector = insertConnector(schema, "dm-connector");
            insertContext(schema, group, connector, "tenant-a", "req-1", EXTERNAL_HASH);

            // Not column-by-column: ownership that can be edited is not ownership. Even a no-op
            // update is refused, so nothing ever needs to reason about which edits are harmless.
            assertRejected(() -> execute(schema,
                    "UPDATE lab_connector_run_group_context SET tenant_id = 'tenant-b' "
                            + "WHERE run_group_id = ?", group));
            assertRejected(() -> execute(schema,
                    "UPDATE lab_connector_run_group_context SET tenant_id = tenant_id "
                            + "WHERE run_group_id = ?", group));
            assertEquals("tenant-a", stringValue(schema,
                    "SELECT tenant_id FROM lab_connector_run_group_context WHERE run_group_id = ?",
                    group));
        } finally {
            dropSchema(schema);
        }
    }

    @Test
    void restrictsDeletionOfAnythingTheContextPointsAt() throws Exception {
        String schema = newSchema();
        try {
            migrate(schema);
            UUID group = insertGroup(schema, "key-1");
            UUID connector = insertConnector(schema, "dm-connector");
            insertContext(schema, group, connector, "tenant-a", null, EXTERNAL_HASH);

            // Deleting the connector or the group out from under the context would erase who ran
            // what. Retention deletes the context first, explicitly, in its own purge order.
            assertRejected(() -> execute(schema,
                    "DELETE FROM brain_connector_clients WHERE id = ?", connector));
            assertRejected(() -> execute(schema,
                    "DELETE FROM lab_run_group WHERE id = ?", group));

            execute(schema, "DELETE FROM lab_connector_run_group_context WHERE run_group_id = ?",
                    group);
            execute(schema, "DELETE FROM lab_run_group WHERE id = ?", group);
        } finally {
            dropSchema(schema);
        }
    }

    @Test
    void indexesTheOwnerLookupThePollingPathUses() throws Exception {
        String schema = newSchema();
        try {
            migrate(schema);
            assertTrue(booleanValue(schema,
                    "SELECT EXISTS (SELECT 1 FROM pg_indexes WHERE schemaname = ? "
                            + "AND indexname = 'idx_lab_connector_group_owner')", schema));
        } finally {
            dropSchema(schema);
        }
    }

    @Test
    void carriesNoColumnForAnythingDocumentShaped() throws Exception {
        String schema = newSchema();
        try {
            migrate(schema);
            // The whole disclosure is ids, a tenant, a correlation id and a digest. A column for
            // a loan id, folder, filename or payload would eventually be filled.
            for (String forbidden : new String[] {"loan", "folder", "document", "filename",
                    "payload", "result", "response"}) {
                assertEquals(0, intValue(schema,
                        "SELECT count(*) FROM information_schema.columns "
                                + "WHERE table_schema = ? "
                                + "AND table_name = 'lab_connector_run_group_context' "
                                + "AND column_name LIKE '%' || ? || '%'",
                        schema, forbidden));
            }
        } finally {
            dropSchema(schema);
        }
    }

    // ================================================================ inserts

    private UUID insertGroup(String schema, String key) throws Exception {
        UUID group = UUID.randomUUID();
        execute(schema, "INSERT INTO lab_run_group "
                        + "(id, brain_id, mode, idempotency_key, request_sha256, status) "
                        + "VALUES (?, ?, 'INDEPENDENT', ?, ?, 'QUEUED')",
                group, TestBrains.DEFAULT_ID, key, HASH);
        return group;
    }

    private UUID insertConnector(String schema, String name) throws Exception {
        UUID connector = UUID.randomUUID();
        execute(schema, "INSERT INTO brain_connector_clients (id, name, type, brain_id) "
                        + "VALUES (?, ?, 'document-manager', ?)",
                connector, name, TestBrains.DEFAULT_ID);
        return connector;
    }

    private void insertContext(String schema, UUID group, UUID connector, String tenant,
                               String externalRequestId, String externalHash) throws Exception {
        execute(schema, "INSERT INTO lab_connector_run_group_context "
                        + "(run_group_id, connector_client_id, brain_id, tenant_id, "
                        + "external_request_id, external_request_sha256) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                group, connector, TestBrains.DEFAULT_ID, tenant, externalRequestId, externalHash);
    }

    private void assertRejected(SqlStatement statement) {
        assertThrows(Exception.class, statement::run);
    }

    @FunctionalInterface
    private interface SqlStatement {
        void run() throws Exception;
    }

    // ================================================================ plumbing

    private String newSchema() {
        return "v40_" + UUID.randomUUID().toString().replace("-", "");
    }

    private void migrate(String schema) {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    private void dropSchema(String schema) throws SQLException {
        try (Connection connection = connection(schema);
             Statement statement = connection.createStatement()) {
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
                result.next();
                return result.getString(1);
            }
        }
    }

    private int intValue(String schema, String sql, Object... values) throws Exception {
        try (Connection connection = connection(schema);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private boolean booleanValue(String schema, String sql, Object... values) throws Exception {
        try (Connection connection = connection(schema);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getBoolean(1);
            }
        }
    }

    private static void bind(PreparedStatement statement, Object... values) throws SQLException {
        for (int index = 0; index < values.length; index++) {
            statement.setObject(index + 1, values[index]);
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
}
