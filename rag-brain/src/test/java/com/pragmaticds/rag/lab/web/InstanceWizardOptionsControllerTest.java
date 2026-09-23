package com.pragmaticds.rag.lab.web;

import com.pragmaticds.rag.config.AdminApiKeyFilter;
import com.pragmaticds.rag.config.RagProperties;
import com.pragmaticds.rag.config.RequestCorrelationFilter;
import com.pragmaticds.rag.lab.analyze.InstanceOutputSchemaRegistry;
import com.pragmaticds.rag.lab.analyze.InstanceToolRegistry;
import com.pragmaticds.rag.lab.engine.EngineEnvelopeParser;
import com.pragmaticds.rag.lab.eval.InstanceScenarioSetRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The authoring options a client needs, and the ones it must never be given.
 *
 * <p>This route exists because a manifest names four things no client can derive: two parser
 * version constants compared by equality, an output schema digest taken over a resource's bytes,
 * and an allowlisted scenario set. A client that hardcoded any of them would work until the day the
 * underlying file changed and then fail with a digest mismatch pointing at nothing.
 *
 * <p>So the tests say two things. The values served are the ones the validator actually compares
 * against — asserted against the constants themselves rather than against copies, because a test
 * carrying its own copy of a constant is a test that passes while the route serves the wrong
 * string. And the response carries identifiers and digests only: no schema text, no scenario
 * contents, and nothing brain-scoped, because this describes the build rather than anyone's data.
 */
class InstanceWizardOptionsControllerTest {

    private static final String ADMIN_KEY = "test-admin-key";
    private static final String SCHEMA_DIGEST = "a".repeat(64);
    private static final String SET_DIGEST = "b".repeat(64);

    private InstanceOutputSchemaRegistry schemas;
    private InstanceScenarioSetRegistry scenarios;
    private InstanceToolRegistry tools;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        schemas = mock(InstanceOutputSchemaRegistry.class);
        scenarios = mock(InstanceScenarioSetRegistry.class);
        tools = mock(InstanceToolRegistry.class);

        when(schemas.list()).thenReturn(List.of(
                new InstanceOutputSchemaRegistry.SchemaDescriptor(
                        "analyzer-envelope-v2", SCHEMA_DIGEST)));
        when(scenarios.list()).thenReturn(List.of(
                new InstanceScenarioSetRegistry.ScenarioSetDescriptor(
                        "income-smoke", 1, SET_DIGEST, 5)));
        when(tools.registered()).thenReturn(List.of());

        mvc = MockMvcBuilders
                .standaloneSetup(new InstanceWizardOptionsController(schemas, scenarios, tools))
                .addFilters(new RequestCorrelationFilter("X-Auth-Request-Email"),
                        new AdminApiKeyFilter(properties()))
                .setControllerAdvice(new InstanceWizardOptionsExceptionHandler())
                .build();
    }

    @Test
    void theRouteIsAdminGatedLikeEveryOtherAdminRoute() throws Exception {
        mvc.perform(get("/api/ai/admin/instances/wizard-options"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void servesTheParserVersionsTheValidatorActuallyComparesAgainst() throws Exception {
        // Asserted against the constants rather than against string copies. A test that carried
        // its own "1.0.0" would keep passing if this route started serving something else, which
        // is exactly the failure the route exists to prevent.
        mvc.perform(get("/api/ai/admin/instances/wizard-options")
                        .header("X-Admin-Api-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.envelopeVersion")
                        .value(EngineEnvelopeParser.SUPPORTED_ENVELOPE_VERSION))
                .andExpect(jsonPath("$.canonicalizationVersion")
                        .value(EngineEnvelopeParser.SUPPORTED_CANONICALIZATION_VERSION));
    }

    @Test
    void servesEachAllowlistedSchemaWithTheDigestAReleaseMustPin() throws Exception {
        mvc.perform(get("/api/ai/admin/instances/wizard-options")
                        .header("X-Admin-Api-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outputSchemas[0].schemaId").value("analyzer-envelope-v2"))
                .andExpect(jsonPath("$.outputSchemas[0].sha256").value(SCHEMA_DIGEST));
    }

    @Test
    void servesScenarioSetsWithTheirVersionAndSize() throws Exception {
        // The count is the one thing about a set worth seeing before choosing it: a two-case set
        // and a two-hundred-case set are very different promotion gates.
        mvc.perform(get("/api/ai/admin/instances/wizard-options")
                        .header("X-Admin-Api-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scenarioSets[0].scenarioSetId").value("income-smoke"))
                .andExpect(jsonPath("$.scenarioSets[0].version").value(1))
                .andExpect(jsonPath("$.scenarioSets[0].sha256").value(SET_DIGEST))
                .andExpect(jsonPath("$.scenarioSets[0].scenarioCount").value(5));
    }

    @Test
    void servesEveryFieldOfAToolsIdentityRatherThanItsNameAlone() throws Exception {
        when(tools.registered()).thenReturn(List.of(new InstanceToolRegistry.ToolDescriptor(
                "income-total", "1", "c".repeat(64), "d".repeat(64))));

        // All four fields form the identity a contract must match exactly, so a wizard offering a
        // tool by name would author a release that fails with TOOL_NOT_REGISTERED.
        mvc.perform(get("/api/ai/admin/instances/wizard-options")
                        .header("X-Admin-Api-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tools[0].name").value("income-total"))
                .andExpect(jsonPath("$.tools[0].version").value("1"))
                .andExpect(jsonPath("$.tools[0].inputSchemaSha256").value("c".repeat(64)))
                .andExpect(jsonPath("$.tools[0].outputSchemaSha256").value("d".repeat(64)));
    }

    @Test
    void anEmptyToolRegistryIsAnAnswerRatherThanAFailure() throws Exception {
        // No instance ships a pre-computable tool today, so this is the normal case.
        mvc.perform(get("/api/ai/admin/instances/wizard-options")
                        .header("X-Admin-Api-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tools").isEmpty());
    }

    @Test
    void carriesNoSchemaTextNoScenarioContentsAndNothingBrainScoped() throws Exception {
        MvcResult result = mvc.perform(get("/api/ai/admin/instances/wizard-options")
                        .header("X-Admin-Api-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString();

        // The whole disclosure is ids, versions, digests and counts. A schema body would be the
        // model's own instructions; a scenario body names fixtures and assertion pointers.
        for (String forbidden : List.of("properties", "$schema", "scenarios",
                "packageFixture", "requiredPointers", "brain")) {
            assertFalse(body.contains(forbidden),
                    "wizard options must not carry " + forbidden);
        }
    }

    @Test
    void reportsABrokenAllowlistAsACodeRatherThanAShorterList() throws Exception {
        when(schemas.list()).thenThrow(new InstanceOutputSchemaRegistry.OutputSchemaException(
                InstanceOutputSchemaRegistry.OutputSchemaException.Code.OUTPUT_SCHEMA_UNREADABLE));

        // Omitting the entry would present a broken deployment as a shorter list of options, which
        // is the failure nobody notices until somebody asks where their schema went.
        mvc.perform(get("/api/ai/admin/instances/wizard-options")
                        .header("X-Admin-Api-Key", ADMIN_KEY))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("OUTPUT_SCHEMA_UNREADABLE"));
    }

    private static RagProperties properties() {
        return new RagProperties(new RagProperties.Routing("anthropic", "openai"),
                new RagProperties.Retrieval(8, 3, 0.35, 0.65, 0.35, true, 24, true, 0.0),
                new RagProperties.Chunking(1000, 1200, 150),
                new RagProperties.Storage("./data/documents"),
                new RagProperties.Admin(ADMIN_KEY), new RagProperties.Analyze(null),
                new RagProperties.RateLimit(10, 60, 120));
    }
}
