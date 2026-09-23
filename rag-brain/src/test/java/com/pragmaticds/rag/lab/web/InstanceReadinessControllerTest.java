package com.pragmaticds.rag.lab.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pragmaticds.rag.lab.ops.InstanceReadinessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Iterator;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The readiness route's contract: the full report on a plain read, a {@code 503} gate when a
 * named capability is not ready, and a body whose key set is exactly the safe vocabulary — no
 * URL, credential, tenant list, or provider text has a field to hide in.
 */
class InstanceReadinessControllerTest {

    private InstanceReadinessService readiness;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        readiness = mock(InstanceReadinessService.class);
        mvc = MockMvcBuilders
                .standaloneSetup(new InstanceReadinessController(readiness))
                .build();
    }

    @Test
    void aPlainReadAnswersTheFullReport() throws Exception {
        when(readiness.report()).thenReturn(report(true));

        mvc.perform(get("/api/ai/admin/instances/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value("41"))
                .andExpect(jsonPath("$.executionReady").value(true))
                .andExpect(jsonPath("$.queueDepth").value(2));
    }

    @Test
    void aRequestedCapabilityThatIsNotReadyAnswers503WithTheReasonsInTheBody() throws Exception {
        when(readiness.report()).thenReturn(report(false));

        // The body still carries every signal: the operator sees WHICH prerequisite is missing
        // from the same response that refused.
        mvc.perform(get("/api/ai/admin/instances/readiness").param("require", "execution"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.executionReady").value(false))
                .andExpect(jsonPath("$.retentionDecided").value(false));
    }

    @Test
    void aReadyCapabilityAnswers200() throws Exception {
        when(readiness.report()).thenReturn(report(true));

        mvc.perform(get("/api/ai/admin/instances/readiness").param("require", "execution"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/ai/admin/instances/readiness").param("require", "promotion"))
                .andExpect(status().isOk());
    }

    @Test
    void anUnknownCapabilityIsABadRequest() throws Exception {
        when(readiness.report()).thenReturn(report(true));

        mvc.perform(get("/api/ai/admin/instances/readiness").param("require", "everything"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void theBodyCarriesExactlyTheSafeVocabularyAndNothingElse() throws Exception {
        when(readiness.report()).thenReturn(report(true));

        MvcResult answer = mvc.perform(get("/api/ai/admin/instances/readiness"))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = new ObjectMapper().readTree(answer.getResponse().getContentAsString());

        Set<String> safe = Set.of(
                "schemaVersion", "schemaCurrent", "instances", "releases", "livePointers",
                "engineConfigured", "engineClientPresent", "encryptionReady",
                "auditWritable", "auditWriteFailures",
                "retentionDecided", "retentionDays", "catalogReady", "offerableModels",
                "executionEnabled", "dispatcherPresent", "queueDepth", "staleLeases",
                "connectorEnabled", "promotionEnabled",
                "readReady", "executionReady", "connectorReady", "promotionReady");
        for (Iterator<String> names = body.fieldNames(); names.hasNext(); ) {
            String field = names.next();
            assertTrue(safe.contains(field), "unexpected readiness field: " + field);
        }
    }

    private static InstanceReadinessService.ReadinessReport report(boolean ready) {
        return new InstanceReadinessService.ReadinessReport(
                "41", true,
                1, 3, 1,
                true, true, ready,
                true, 0,
                ready, ready ? 30 : 0,
                true, 1,
                true, ready, 2, 0,
                false, true,
                true, ready, false, true);
    }
}
