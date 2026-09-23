package com.pragmaticds.rag.repository;

import com.pragmaticds.rag.TestBrains;
import com.pragmaticds.rag.domain.BrainConnectorClient;
import com.pragmaticds.rag.domain.BrainConnectorEvent;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class BrainConnectorRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired
    BrainConnectorClientRepository clients;

    @Autowired
    BrainConnectorEventRepository events;

    @Test
    void connectorClientPersistsScopesAndEvents() {
        BrainConnectorClient client = new BrainConnectorClient(
                UUID.randomUUID(), "agent", "MCP_AGENT", "hash-1");
        client.setBrainId(TestBrains.DEFAULT_ID);
        client.setScopes(List.of("brains:list", "ask:public"));
        client.setEnabled(true);
        clients.saveAndFlush(client);

        BrainConnectorClient found = clients.findByTokenHash("hash-1").orElseThrow();
        assertEquals(List.of("brains:list", "ask:public"), found.getScopes());

        events.saveAndFlush(new BrainConnectorEvent(UUID.randomUUID(), found.getId(),
                TestBrains.DEFAULT_ID, "ASK", "ask:public", "example.com", "200"));

        assertEquals(1, events.findAll().size());
    }
}
