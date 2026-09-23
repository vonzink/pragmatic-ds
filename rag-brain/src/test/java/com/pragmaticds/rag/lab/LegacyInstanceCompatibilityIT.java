package com.pragmaticds.rag.lab;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import com.pragmaticds.rag.lab.engine.DocumentEngineClient;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The other direction of the flag matrix: with every instance flag ON, the legacy surface must
 * be exactly what it was with them off.
 *
 * <p>{@code InstanceControlFeatureFlagIT} proves the everything-off deployment is untouched by
 * the plane's existence; this file proves the everything-on deployment leaves the pre-existing
 * product alone — the raw analyze and extract routes still answer under their own auth, the
 * Income Lab prototype stays absent while its own flag is off (no instance flag resurrects it),
 * the legacy admin surface answers, and {@code analyzer_scope} — retained compatibility
 * metadata — is still a real column. The rollout plan turns these flags on in production; this
 * is the regression that turning them on must not cause.
 */
@SpringBootTest(properties = {
        "ragbrain.instances.enabled=true",
        "ragbrain.instances.promotion-enabled=true",
        "ragbrain.instances.connector-enabled=true",
        "ragbrain.instances.execution.enabled=true",
        // These contexts mock the parse boundary, so no engine is ever called — but the
        // startup validator requires a declared auth mode before execution or the
        // connector may run, because a base URL alone never proved this deployment may
        // talk to the engine. Synthetic: nothing here authenticates to anything.
        "ragbrain.lab.engine.api-key=synthetic-engine-key-not-a-real-key",
        "ragbrain.lab.enabled=false",
        // The validator's full prerequisite set, because every child flag is on.
        "ragbrain.instances.retention.terminal-run-days=30",
        "ragbrain.lab.payload-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "ragbrain.instances.models[0].provider=synthetic",
        "ragbrain.instances.models[0].model=synthetic-analyzer",
        "ragbrain.instances.models[0].context-token-ceiling=100000",
        "ragbrain.instances.models[0].output-token-ceiling=8000",
        "ragbrain.instances.models[0].tokenizer=CONSERVATIVE_RANGE",
        "ragbrain.instances.models[0].input-usd-per-million=1.00",
        "ragbrain.instances.models[0].output-usd-per-million=5.00",
        "ragbrain.instances.execution.poll-interval=PT1H",
        "ragbrain.rag.admin.api-key=legacy-compat-it-key"
})
@AutoConfigureMockMvc
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class LegacyInstanceCompatibilityIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ApplicationContext context;

    @Test
    void theLegacyAdminSurfaceStillAnswers() throws Exception {
        mvc.perform(get("/api/ai/admin/brains")
                        .header("X-Admin-Api-Key", "legacy-compat-it-key"))
                .andExpect(status().isOk());
    }

    @Test
    void rawAnalyzeAndExtractRemainMappedUnderTheirOwnAuth() throws Exception {
        // Whatever their own filters answer, the one forbidden status is 404: the routes must
        // exist exactly as before — the instance flags neither removed nor rerouted them.
        int analyze = mvc.perform(post("/api/ai/generic/analyze/income")
                        .contentType(MediaType.MULTIPART_FORM_DATA))
                .andReturn().getResponse().getStatus();
        org.junit.jupiter.api.Assertions.assertNotEquals(404, analyze,
                "analyze vanished under the instance flags");
        int extract = mvc.perform(post("/api/ai/generic/extract/w2")
                        .contentType(MediaType.MULTIPART_FORM_DATA))
                .andReturn().getResponse().getStatus();
        org.junit.jupiter.api.Assertions.assertNotEquals(404, extract,
                "extract vanished under the instance flags");
    }

    @Test
    void theDispatcherHasAnEngineClientEvenThoughThePrototypeIsOff() {
        // This context is the shape that used to be silently broken: execution and the connector
        // on, the prototype off. ParsedDataResolver reaches the engine through an ObjectProvider
        // and answers PARSE_ENGINE_UNAVAILABLE when it finds nothing, so a dispatcher without a
        // client passed every gate and then failed every dispatched run at re-verification.
        assertEquals(1, context.getBeanNamesForType(DocumentEngineClient.class).length);
    }

    @Test
    void theIncomeLabPrototypeStaysAbsentWhileItsOwnFlagIsOff() throws Exception {
        // Turning the instance plane on must not resurrect the prototype's routes: they share
        // tables and services, not a switch.
        mvc.perform(get("/api/ai/admin/lab/runs")
                        .header("X-Admin-Api-Key", "legacy-compat-it-key"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/ai/admin/lab/uploads")
                        .header("X-Admin-Api-Key", "legacy-compat-it-key"))
                .andExpect(status().isNotFound());
    }

    @Test
    void analyzerScopeSurvivesAsRealCompatibilityMetadata() {
        // Retained until a later phase removes it, per the program's standing constraint. The
        // column existing — not merely the code compiling — is what legacy scope-filtered
        // retrieval still runs on.
        assertEquals(1, (int) jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_name = 'brain_documents' "
                        + "AND column_name = 'analyzer_scope'", Integer.class));
    }

    @Test
    void legacyConnectorRoutesKeepTheirFailClosedBoundary() throws Exception {
        // The pre-instance connector surface: still intercepted, still 401 without a token,
        // exactly as it was before instances:run existed.
        mvc.perform(get("/api/connect/v1/brains/generic/manifest"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/mcp/tools/" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }
}
