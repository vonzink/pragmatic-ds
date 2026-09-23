package com.pragmaticds.rag.service.connect;

import com.pragmaticds.rag.repository.BrainConnectorClientRepository;
import com.pragmaticds.rag.repository.BrainConnectorEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the AUTH_FAILURE audit event survives the rollback of the request
 * transaction. authenticate() records the event and then throws (a RuntimeException
 * that rolls back the caller's transaction); the REQUIRES_NEW event transaction must
 * commit independently so rejected probes are still audited.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class ConnectorAuthEventPersistenceTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired
    BrainConnectorClientRepository clients;

    @Autowired
    BrainConnectorEventRepository events;

    @Autowired
    PlatformTransactionManager txManager;

    @Test
    void authFailureEventSurvivesRequestRollback() {
        TestTransaction.end(); // opt out of the @DataJpaTest rollback-only transaction
        ConnectorAuthService service = new ConnectorAuthService(clients, events, txManager);
        TransactionTemplate outer = new TransactionTemplate(txManager);

        // Simulate the request transaction: authenticate() rejects and throws, then the
        // surrounding transaction rolls back (as Spring does on the RuntimeException).
        outer.execute(status -> {
            assertThrows(ResponseStatusException.class,
                    () -> service.authenticate("Bearer rb_conn_missing", "peer.local"));
            status.setRollbackOnly();
            return null;
        });

        assertTrue(
                events.findAll().stream().anyMatch(e -> "AUTH_FAILURE".equals(e.getEventType())),
                "AUTH_FAILURE event must persist even though the request transaction rolled back");
    }
}
