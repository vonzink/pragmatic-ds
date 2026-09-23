package com.pragmaticds.docengine.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * The gold endpoint's output, assembled into a truth file exactly as {@code tools/gold.py export}
 * does, must load through the real harness loader with the expected keys and must-capture flags.
 * This is what keeps the endpoint and the harness from drifting apart.
 */
class GoldTruthRoundTripIT extends AbstractExtractionIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void the_export_round_trips_through_the_harness_loader() throws Exception {
        UUID packageId = insertPackage("gold-roundtrip");
        insertFixturePages(packageId, "w2_form");
        runPipelineToExtraction(packageId);
        UUID documentId = onlyDocumentOf(packageId);
        Map<String, Map<String, Object>> fields = currentFieldsByName(documentId);
        UUID employeeName = (UUID) fields.get("employeeName").get("id");
        UUID employeeSsn = (UUID) fields.get("employeeSsn").get("id");

        mockMvc.perform(
                        patch("/v1/fields/{id}", employeeName)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"action\":\"CONFIRM\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(
                        patch("/v1/fields/{id}", employeeSsn)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"action\":\"REJECT\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/v1/documents/{id}/review", documentId)).andExpect(status().isOk());
        JsonNode gold =
                JSON.readTree(
                        mockMvc.perform(
                                        get("/v1/documents/{id}/gold", documentId)
                                                .header("X-Dev-Role", "ADMIN"))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsString());

        // Assemble truth exactly as tools/gold.py export does for a single-document case.
        ObjectNode truth = JSON.createObjectNode();
        truth.put("fixture", "w2-001");
        truth.put("source", "source.pdf");
        truth.set("pages", gold.get("pages"));
        ArrayNode expected = truth.putArray("expectedFields");
        for (JsonNode field : gold.get("fields")) {
            ObjectNode entry = expected.addObject();
            entry.put("field", field.get("field").asText());
            entry.put("groupKey", field.get("groupKey").asText());
            entry.set("displayedText", field.get("displayedText"));
            entry.put("pageIndex", field.get("pageIndex").asInt());
            if ("REJECTED".equals(field.get("decision").asText())) {
                entry.put("method", "NONE");
            }
        }
        JsonNode caseNode =
                JSON.readTree(
                        "{\"id\":\"w2-001\",\"layout\":\"NONE\",\"synthetic\":false,"
                                + "\"note\":\"it\",\"documents\":[{\"type\":\"W2\"}]}");

        ExtractionEvalCorpus.EvalCase loaded = ExtractionEvalCorpus.parseCase(caseNode, truth);

        Map<String, ExtractionEvalCorpus.ExpectedField> expectedFields =
                loaded.documents().get(0).fields();
        assertThat(expectedFields).containsOnlyKeys("employeeName#", "employeeSsn#");
        assertThat(expectedFields.get("employeeName#").mustCapture()).isTrue();
        assertThat(expectedFields.get("employeeSsn#").mustCapture()).isFalse();
        assertThat(loaded.synthetic()).isFalse();
        assertThat(loaded.truthPages()).hasSize(1);
        assertThat(loaded.truthPages().get(0).get("words").size()).isGreaterThan(10);
    }
}
