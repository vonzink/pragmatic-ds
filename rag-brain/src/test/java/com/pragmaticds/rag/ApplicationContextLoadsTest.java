package com.pragmaticds.rag;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Boots the full Spring application context against a real Postgres, with no AI
 * provider keys configured (the admin/dashboard surface must boot without them).
 * This is the smoke test that catches bean-wiring regressions the sliced unit
 * tests miss — e.g. a service with two constructors and no {@code @Autowired},
 * which compiles and passes every unit test but fails at real startup.
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class ApplicationContextLoadsTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Test
    void contextLoads() {
        // Success = every bean wired and every ApplicationRunner (seeder, pack check)
        // ran without error. An empty body is the assertion.
    }
}
