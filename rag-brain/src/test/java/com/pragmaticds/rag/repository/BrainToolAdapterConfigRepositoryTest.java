package com.pragmaticds.rag.repository;

import com.pragmaticds.rag.TestBrains;
import com.pragmaticds.rag.domain.BrainToolAdapterConfig;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class BrainToolAdapterConfigRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired
    BrainToolAdapterConfigRepository repo;

    @Test
    void rejectsEnabledAdapterWithoutAllowedHostsAtDatabaseBoundary() {
        BrainToolAdapterConfig invalid = new BrainToolAdapterConfig(
                TestBrains.DEFAULT_ID,
                "unsafeAdapter",
                true,
                "GET",
                "https://api.example.com/search",
                "NONE",
                null,
                null,
                Map.of(),
                Map.of(),
                5000,
                List.of(),
                "test");

        assertThrows(DataIntegrityViolationException.class, () -> repo.saveAndFlush(invalid));
    }

    @Test
    void storesAndFindsEnabledAdapterByBrainAndToolName() {
        BrainToolAdapterConfig saved = repo.saveAndFlush(new BrainToolAdapterConfig(
                TestBrains.DEFAULT_ID,
                "searchLoans",
                true,
                "POST",
                "https://dashboard.example.com/api/search",
                "BEARER_TOKEN",
                "dashboard_api",
                null,
                Map.of("X-App", "rag-brain"),
                Map.of("query", "{query}"),
                5000,
                List.of("api.example.com"),
                "test"));

        assertTrue(repo.findByBrainIdAndToolName(TestBrains.DEFAULT_ID, "searchLoans").isPresent());
        BrainToolAdapterConfig enabled = repo
                .findByBrainIdAndToolNameAndEnabledTrue(TestBrains.DEFAULT_ID, "searchLoans")
                .orElseThrow();
        assertEquals(saved.getId(), enabled.getId());
        assertEquals("dashboard_api", enabled.getSecretRef());
        assertEquals(Map.of("query", "{query}"), enabled.getRequestBodyTemplate());
        assertEquals(List.of("api.example.com"), enabled.getAllowedHosts());
    }

    @Test
    void v20DisablesExistingEnabledAdaptersUntilAllowedHostsAreSet() throws Exception {
        String schema = "adapter_migration_" + UUID.randomUUID().toString().replace("-", "");
        try {
            Flyway.configure()
                    .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .schemas(schema)
                    .defaultSchema(schema)
                    .locations("classpath:db/migration")
                    .target("19")
                    .load()
                    .migrate();

            try (var connection = DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
                connection.setSchema(schema);
                try (var insert = connection.prepareStatement("""
                        INSERT INTO brain_tool_adapters (
                            brain_id, tool_name, enabled, http_method, url_template, auth_mode,
                            static_headers, request_body_template, timeout_ms, created_by, updated_by
                        )
                        VALUES (?, 'legacyEnabled', true, 'GET', 'https://dashboard.example.com/api/search',
                                'NONE', '{}'::jsonb, '{}'::jsonb, 5000, 'test', 'test')
                        """)) {
                    insert.setObject(1, TestBrains.DEFAULT_ID);
                    insert.executeUpdate();
                }
            }

            Flyway.configure()
                    .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .schemas(schema)
                    .defaultSchema(schema)
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();

            try (var connection = DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
                connection.setSchema(schema);
                try (var select = connection.prepareStatement(
                        "SELECT enabled, allowed_hosts FROM brain_tool_adapters WHERE tool_name = 'legacyEnabled'");
                     var rows = select.executeQuery()) {
                    assertTrue(rows.next());
                    assertFalse(rows.getBoolean("enabled"));
                    assertEquals("[]", rows.getString("allowed_hosts"));
                }
            }
        } finally {
            try (var connection = DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                 var statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }
}
