package com.pragmaticds.rag.migration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class V26MigrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void brainsHasLearningEnabledColumnDefaultingFalse() {
        Boolean notNull = jdbc.queryForObject(
                "SELECT is_nullable = 'NO' FROM information_schema.columns "
                        + "WHERE table_name = 'brains' AND column_name = 'learning_enabled'",
                Boolean.class);
        assertEquals(Boolean.TRUE, notNull, "brains.learning_enabled must be NOT NULL");

        String def = jdbc.queryForObject(
                "SELECT column_default FROM information_schema.columns "
                        + "WHERE table_name = 'brains' AND column_name = 'learning_enabled'",
                String.class);
        assertTrue(def != null && def.toLowerCase().contains("false"),
                "learning_enabled default must be false, was: " + def);
    }

    @Test
    void createsThreeLearningTables() {
        for (String table : new String[]{
                "rag_answer_feedback", "brain_source_weights", "brain_source_weight_events"}) {
            Integer count = jdbc.queryForObject(
                    "SELECT count(*) FROM information_schema.tables WHERE table_name = ?",
                    Integer.class, table);
            assertEquals(1, count, "expected table " + table);
        }
    }

    @Test
    void sourceWeightsHasCompositePrimaryKey() {
        Integer pkCols = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.key_column_usage k "
                        + "JOIN information_schema.table_constraints c "
                        + "  ON k.constraint_name = c.constraint_name "
                        + "WHERE c.table_name = 'brain_source_weights' "
                        + "  AND c.constraint_type = 'PRIMARY KEY'",
                Integer.class);
        assertEquals(2, pkCols, "brain_source_weights PK must be (brain_id, document_id)");
    }

    @Test
    void feedbackHasUniqueTraceSession() {
        Integer uniques = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.table_constraints "
                        + "WHERE table_name = 'rag_answer_feedback' AND constraint_type = 'UNIQUE'",
                Integer.class);
        assertTrue(uniques >= 1, "rag_answer_feedback must have a UNIQUE(trace_id, session_id)");
    }
}
