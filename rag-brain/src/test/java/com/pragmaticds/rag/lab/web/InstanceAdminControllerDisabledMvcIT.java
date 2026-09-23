package com.pragmaticds.rag.lab.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The deployment-off context must have no generalized mapping while retaining the real filter. */
@SpringBootTest(properties = {
        "ragbrain.instances.enabled=false",
        "ragbrain.lab.enabled=false",
        "ragbrain.rag.admin.api-key=instance-mvc-integration-key"
})
@AutoConfigureMockMvc
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class InstanceAdminControllerDisabledMvcIT {
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired MockMvc mvc;

    @Test
    void generalizedRouteIsUnmappedWhenTheIndependentFlagIsFalse() throws Exception {
        mvc.perform(get("/api/ai/admin/instances?brain=" + UUID.randomUUID())
                        .header("X-Admin-Api-Key", "instance-mvc-integration-key"))
                .andExpect(status().isNotFound());
    }
}
