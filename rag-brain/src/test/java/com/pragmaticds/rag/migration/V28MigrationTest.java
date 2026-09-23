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
class V28MigrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void brainsHasNullableDailyCostBudgetColumn() {
        Boolean nullable = jdbc.queryForObject(
                "SELECT is_nullable = 'YES' FROM information_schema.columns "
                        + "WHERE table_name = 'brains' AND column_name = 'daily_cost_budget_usd'",
                Boolean.class);
        assertEquals(Boolean.TRUE, nullable, "brains.daily_cost_budget_usd must be nullable");

        String type = jdbc.queryForObject(
                "SELECT data_type FROM information_schema.columns "
                        + "WHERE table_name = 'brains' AND column_name = 'daily_cost_budget_usd'",
                String.class);
        assertEquals("numeric", type);
    }

    @Test
    void createsBrainDailyUsageTable() {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_name = 'brain_daily_usage'",
                Integer.class);
        assertEquals(1, count, "expected table brain_daily_usage");
    }

    @Test
    void brainDailyUsageHasCompositePrimaryKeyOnBrainAndDate() {
        Integer pkCols = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.key_column_usage k "
                        + "JOIN information_schema.table_constraints c "
                        + "  ON k.constraint_name = c.constraint_name "
                        + "WHERE c.table_name = 'brain_daily_usage' "
                        + "  AND c.constraint_type = 'PRIMARY KEY'",
                Integer.class);
        assertEquals(2, pkCols, "brain_daily_usage PK must be (brain_id, usage_date)");
    }

    @Test
    void brainDailyUsageDefaultsCountersToZero() {
        String defaults = jdbc.queryForObject(
                "SELECT string_agg(column_default, ',') FROM information_schema.columns "
                        + "WHERE table_name = 'brain_daily_usage' "
                        + "  AND column_name IN ('request_count', 'prompt_tokens', 'completion_tokens', 'cost_estimate_usd')",
                String.class);
        assertTrue(defaults != null && defaults.contains("0"),
                "brain_daily_usage counters must default to 0, was: " + defaults);
    }
}
