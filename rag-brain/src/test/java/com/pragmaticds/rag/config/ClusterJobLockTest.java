package com.pragmaticds.rag.config;

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

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class ClusterJobLockTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired
    DataSource dataSource;

    private static final long KEY = 918_273_645L;

    private ClusterJobLock lock() {
        return new ClusterJobLock(new JdbcTemplate(dataSource));
    }

    @Test
    void runsJobAndReturnsTrueWhenUncontended() {
        AtomicInteger runs = new AtomicInteger();

        boolean ran = lock().runIfLeader(KEY, "test-job", runs::incrementAndGet);

        assertTrue(ran, "an uncontended instance is the leader and runs the job");
        assertEquals(1, runs.get());
    }

    @Test
    void skipsJobAndReturnsFalseWhenAnotherHolderOwnsTheLock() throws Exception {
        AtomicInteger runs = new AtomicInteger();

        // Hold the advisory lock on a separate, dedicated session for the whole call.
        try (Connection held = dataSource.getConnection(); Statement st = held.createStatement()) {
            st.execute("SELECT pg_advisory_lock(" + KEY + ")");

            boolean ran = lock().runIfLeader(KEY, "test-job", runs::incrementAndGet);

            assertFalse(ran, "another holder owns the lock, so this instance must skip");
            assertEquals(0, runs.get(), "the job body must not run when the lock is held elsewhere");

            st.execute("SELECT pg_advisory_unlock(" + KEY + ")");
        }
    }

    @Test
    void releasesLockAfterTheJobCompletes() throws Exception {
        lock().runIfLeader(KEY, "test-job", () -> { /* no-op */ });

        // A fresh session must be able to grab the lock — proving it was released on
        // the SAME connection that took it (not leaked onto the returned pool connection).
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT pg_try_advisory_lock(" + KEY + ")")) {
                assertTrue(rs.next() && rs.getBoolean(1), "lock must be free after the job completes");
            }
            st.execute("SELECT pg_advisory_unlock(" + KEY + ")");
        }
    }
}
