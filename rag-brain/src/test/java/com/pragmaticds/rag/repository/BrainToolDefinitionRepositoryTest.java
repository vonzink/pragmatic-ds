package com.pragmaticds.rag.repository;

import com.pragmaticds.rag.TestBrains;
import com.pragmaticds.rag.domain.BrainToolDefinition;
import com.pragmaticds.rag.service.dashboard.DashboardToolMode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class BrainToolDefinitionRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired
    BrainToolDefinitionRepository tools;

    @Test
    void persistsJsonManifestFieldsAndFindsActiveToolByBrainAndName() {
        BrainToolDefinition tool = new BrainToolDefinition(
                TestBrains.DEFAULT_ID,
                "searchVisibleFiles",
                "Search visible loans.",
                DashboardToolMode.READ,
                false,
                List.of("dashboard.loans.read"),
                Map.of("type", "object", "properties", Map.of("query", Map.of("type", "string"))),
                "test");
        tools.saveAndFlush(tool);

        BrainToolDefinition found = tools
                .findByBrainIdAndNameAndActiveTrue(TestBrains.DEFAULT_ID, "searchVisibleFiles")
                .orElseThrow();

        assertEquals(List.of("dashboard.loans.read"), found.getRequiredPermissions());
        assertEquals("object", found.getInputSchema().get("type"));
        assertEquals(DashboardToolMode.READ, found.getMode());
        assertTrue(found.isActive());
    }

    @Test
    void activeQueryExcludesInactiveRows() {
        BrainToolDefinition tool = new BrainToolDefinition(
                TestBrains.DEFAULT_ID,
                "archiveVisibleFile",
                "Create task.",
                DashboardToolMode.WRITE,
                true,
                List.of("dashboard.tasks.write"),
                Map.of("type", "object"),
                "test");
        tool.setActive(false);
        tools.saveAndFlush(tool);

        assertTrue(tools.findByBrainIdAndActiveTrueOrderByCreatedAtDescIdDesc(TestBrains.DEFAULT_ID)
                .stream()
                .noneMatch(found -> found.getName().equals("archiveVisibleFile")));
    }
}
