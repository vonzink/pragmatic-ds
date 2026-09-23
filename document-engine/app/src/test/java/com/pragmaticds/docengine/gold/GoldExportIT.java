package com.pragmaticds.docengine.gold;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pragmaticds.docengine.extraction.AbstractExtractionIT;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * The gold export: a reviewed document's decisions, UNMASKED, in the harness's truth shape.
 * Seeds the synthetic W-2 (its {@code employeeSsn} is a sensitive field), makes one decision of
 * each kind, leaves one field untouched, and reads the export back as ADMIN.
 */
class GoldExportIT extends AbstractExtractionIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    private record Seeded(UUID packageId, UUID documentId, Map<String, Map<String, Object>> fields) {}

    private Seeded seedW2(String name) {
        UUID packageId = insertPackage(name);
        insertFixturePages(packageId, "w2_form");
        runPipelineToExtraction(packageId);
        UUID documentId = onlyDocumentOf(packageId);
        return new Seeded(packageId, documentId, currentFieldsByName(documentId));
    }

    private void decide(UUID fieldId, String body) throws Exception {
        mockMvc.perform(
                        patch("/v1/fields/{id}", fieldId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isOk());
    }

    private static UUID idOf(Map<String, Object> row) {
        return (UUID) row.get("id");
    }

    @Test
    void an_unreviewed_document_is_refused_with_409() throws Exception {
        Seeded seeded = seedW2("gold-unreviewed");

        mockMvc.perform(
                        get("/v1/documents/{id}/gold", seeded.documentId())
                                .header("X-Dev-Role", "ADMIN"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.params.reason").value("DOCUMENT_NOT_REVIEWED"));
    }

    @Test
    void a_reviewed_document_exports_its_decisions_unmasked_in_truth_shape() throws Exception {
        Seeded seeded = seedW2("gold-export");
        Map<String, Map<String, Object>> fields = seeded.fields();
        UUID confirmed = idOf(fields.get("employeeName"));
        UUID corrected = idOf(fields.get("employeeSsn"));
        List<String> others = new ArrayList<>(fields.keySet());
        others.removeAll(List.of("employeeName", "employeeSsn"));
        String rejectedName = others.get(0);
        String untouchedName = others.get(1);

        decide(confirmed, "{\"action\":\"CONFIRM\"}");
        decide(corrected, "{\"action\":\"CORRECT\",\"value\":\"987-65-4321\",\"pageIndex\":0}");
        decide(idOf(fields.get(rejectedName)), "{\"action\":\"REJECT\"}");
        mockMvc.perform(post("/v1/documents/{id}/review", seeded.documentId()))
                .andExpect(status().isOk());

        // The ordinary read surface masks the SSN — which is exactly why the exporter cannot use it.
        mockMvc.perform(get("/v1/documents/{id}/fields", seeded.documentId()))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.fields[?(@.fieldName=='employeeSsn')].displayedText")
                                .value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("987-65-4321"))));

        String body =
                mockMvc.perform(
                                get("/v1/documents/{id}/gold", seeded.documentId())
                                        .header("X-Dev-Role", "ADMIN"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.documentTypeCode").value("W2"))
                        .andExpect(jsonPath("$.packageId").value(seeded.packageId().toString()))
                        .andExpect(jsonPath("$.pages[0].pageIndex").value(0))
                        .andExpect(jsonPath("$.pages[0].packagePageIndex").value(0))
                        .andExpect(jsonPath("$.pages[0].expectedType").value("W2"))
                        .andExpect(jsonPath("$.pages[0].words[0].text").isString())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        JsonNode gold = JSON.readTree(body);

        Map<String, JsonNode> byName = new HashMap<>();
        for (JsonNode field : gold.get("fields")) {
            byName.put(field.get("field").asText(), field);
        }

        assertThat(byName.get("employeeName").get("decision").asText()).isEqualTo("CONFIRMED");
        assertThat(byName.get("employeeName").get("displayedText").asText())
                .isEqualTo(fields.get("employeeName").get("displayed_text"));
        // The whole point: the sensitive value is RAW here, not •••-••-4321.
        assertThat(byName.get("employeeSsn").get("decision").asText()).isEqualTo("CORRECTED");
        assertThat(byName.get("employeeSsn").get("displayedText").asText()).isEqualTo("987-65-4321");
        assertThat(byName.get("employeeSsn").get("pageIndex").asInt()).isEqualTo(0);
        assertThat(byName.get(rejectedName).get("decision").asText()).isEqualTo("REJECTED");
        assertThat(byName.get(rejectedName).get("displayedText").isNull()).isTrue();
        assertThat(byName).doesNotContainKey(untouchedName);
        for (JsonNode field : gold.get("fields")) {
            assertThat(field.get("groupKey").asText()).isEqualTo("");
        }
    }

    @Test
    void a_confirmed_field_that_was_corrected_earlier_exports_the_correction() throws Exception {
        Seeded seeded = seedW2("gold-confirm-after-correct");
        UUID field = idOf(seeded.fields().get("employeeName"));

        decide(field, "{\"action\":\"CORRECT\",\"value\":\"Jordan Q. Fixture-Corrected\",\"pageIndex\":0}");
        decide(field, "{\"action\":\"CONFIRM\"}");
        mockMvc.perform(post("/v1/documents/{id}/review", seeded.documentId()))
                .andExpect(status().isOk());

        mockMvc.perform(
                        get("/v1/documents/{id}/gold", seeded.documentId())
                                .header("X-Dev-Role", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fields[0].field").value("employeeName"))
                .andExpect(jsonPath("$.fields[0].decision").value("CONFIRMED"))
                .andExpect(jsonPath("$.fields[0].displayedText").value("Jordan Q. Fixture-Corrected"));
    }
}
