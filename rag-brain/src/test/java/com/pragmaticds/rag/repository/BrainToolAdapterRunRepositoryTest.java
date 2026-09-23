package com.pragmaticds.rag.repository;

import com.pragmaticds.rag.TestBrains;
import com.pragmaticds.rag.domain.BrainToolAdapterRun;
import com.pragmaticds.rag.service.dashboard.DashboardToolMode;
import com.pragmaticds.rag.service.dashboard.DashboardToolStatus;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class BrainToolAdapterRunRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired
    BrainToolAdapterRunRepository repo;

    @Test
    void storesSanitizedAdapterRunMetadata() {
        BrainToolAdapterRun saved = repo.saveAndFlush(new BrainToolAdapterRun(
                TestBrains.DEFAULT_ID, "searchLoans", DashboardToolMode.READ.name(),
                "api.example.com", "POST", DashboardToolStatus.SUCCEEDED.name(),
                200, 42, "s1", "user-1", "tenant-1", null));

        BrainToolAdapterRun found = repo.findTop25ByBrainIdOrderByCreatedAtDesc(TestBrains.DEFAULT_ID).getFirst();

        assertEquals(saved.getId(), found.getId());
        assertEquals("api.example.com", found.getTargetHost());
        assertEquals("tenant-1", found.getTenantId());
    }

    @Test
    void rejectsNegativeDurationAtDatabaseBoundary() {
        assertThrows(DataIntegrityViolationException.class, () -> repo.saveAndFlush(run(
                DashboardToolMode.READ.name(),
                DashboardToolStatus.SUCCEEDED.name(),
                "POST",
                -1)));
    }

    @Test
    void rejectsInvalidModeAtDatabaseBoundary() {
        assertThrows(DataIntegrityViolationException.class, () -> repo.saveAndFlush(run(
                "ADMIN",
                DashboardToolStatus.SUCCEEDED.name(),
                "POST",
                42)));
    }

    @Test
    void rejectsInvalidStatusAtDatabaseBoundary() {
        assertThrows(DataIntegrityViolationException.class, () -> repo.saveAndFlush(run(
                DashboardToolMode.READ.name(),
                "PENDING",
                "POST",
                42)));
    }

    @Test
    void acceptsAllDashboardToolStatusesAtDatabaseBoundary() {
        BrainToolAdapterRun saved = repo.saveAndFlush(run(
                DashboardToolMode.READ.name(),
                DashboardToolStatus.STUBBED.name(),
                "POST",
                42));

        assertEquals(DashboardToolStatus.STUBBED.name(), saved.getStatus());
    }

    @Test
    void rejectsInvalidHttpMethodAtDatabaseBoundary() {
        assertThrows(DataIntegrityViolationException.class, () -> repo.saveAndFlush(run(
                DashboardToolMode.READ.name(),
                DashboardToolStatus.SUCCEEDED.name(),
                "TRACE",
                42)));
    }

    @Test
    void rejectsInvalidHttpStatusCodeAtDatabaseBoundary() {
        assertThrows(DataIntegrityViolationException.class, () -> repo.saveAndFlush(new BrainToolAdapterRun(
                TestBrains.DEFAULT_ID, "searchLoans", DashboardToolMode.READ.name(),
                "api.example.com", "POST", DashboardToolStatus.FAILED.name(),
                99, 42, "s1", "user-1", "tenant-1", null)));
    }

    private BrainToolAdapterRun run(String mode, String status, String httpMethod, long durationMs) {
        return new BrainToolAdapterRun(
                TestBrains.DEFAULT_ID, "searchLoans", mode,
                "api.example.com", httpMethod, status,
                200, durationMs, "s1", "user-1", "tenant-1", null);
    }
}
