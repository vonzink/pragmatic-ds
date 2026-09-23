package com.pragmaticds.docengine.classification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pragmaticds.docengine.orchestration.ParserPort;
import com.pragmaticds.docengine.orchestration.ProcessingStatus;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

/**
 * GET /v1/packages/{id}/classification: the evidence read surface the corpus scorer consumes.
 * Until this endpoint, classification evidence had NO read path at all — the only
 * /classification route is the RECLASSIFY POST (DocumentReviewController). One entry per
 * classified page; {@code evidence} is the stored classification_result jsonb verbatim (anchor
 * ids, weights, span ids, boxes, offsets, per-pack scores — never matched text, Phase 4 rule 3).
 * Org-scoped + tombstone-guarded 404 like every read; READONLY may call it.
 */
class PackageClassificationApiIT extends AbstractClassificationIT {

    @Autowired ParserPort parserPort;

    private void classify(UUID packageId) {
        parserPort.run(
                new ParserPort.StageRequest(
                        UUID.randomUUID(), packageId, ProcessingStatus.CLASSIFYING, 1, "it-idem"));
    }

    @Test
    void the_current_classification_rows_come_back_with_evidence() throws Exception {
        UUID packageId = insertPackage("classification-read-it");
        List<UUID> pageIds = insertFixturePages(packageId, "native_paystub");
        classify(packageId);

        mockMvc.perform(get("/v1/packages/{id}/classification", packageId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.packageId").value(packageId.toString()))
                .andExpect(jsonPath("$.pages.length()").value(pageIds.size()))
                .andExpect(jsonPath("$.pages[0].pageId").value(pageIds.get(0).toString()))
                .andExpect(jsonPath("$.pages[0].packagePageIndex").value(0))
                .andExpect(jsonPath("$.pages[0].documentTypeCode").value("PAYSTUB"))
                .andExpect(jsonPath("$.pages[0].confidence").isNumber())
                // paystub@1.2.0 since V36: the pack that DECIDED rides in the response, so a
                // supersession is visible to a reviewer rather than silent.
                .andExpect(jsonPath("$.pages[0].rulePackVersion").value("1.2.0"))
                // Evidence rides verbatim: anchors carry ids/weights/span ids — never text.
                .andExpect(jsonPath("$.pages[0].evidence.anchors").isArray())
                .andExpect(jsonPath("$.pages[0].evidence.anchors[0].anchorId").isString())
                .andExpect(jsonPath("$.pages[0].evidence.anchors[0].spanIds").isArray())
                .andExpect(jsonPath("$.pages[0].evidence.scores").isArray());
    }

    @Test
    void a_readonly_principal_may_read_classification_evidence() throws Exception {
        UUID packageId = insertPackage("classification-readonly-it");
        insertFixturePages(packageId, "native_paystub");
        classify(packageId);

        mockMvc.perform(
                        get("/v1/packages/{id}/classification", packageId)
                                .header("X-Dev-Role", "READONLY"))
                .andExpect(status().isOk());
    }

    @Test
    void another_orgs_package_is_indistinguishable_from_a_nonexistent_one() throws Exception {
        UUID foreignPackage = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO document_package (id, org_id, name) VALUES (?, ?, ?)",
                foreignPackage,
                ORG_OTHER,
                "foreign package with evidence");

        MvcResult result =
                mockMvc.perform(get("/v1/packages/{id}/classification", foreignPackage))
                        .andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                        .andReturn();
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("foreign package with evidence");
    }

    @Test
    void a_soft_deleted_package_is_not_found() throws Exception {
        UUID packageId = insertPackage("classification-tombstone-it");
        jdbc.update("UPDATE document_package SET deleted_at = now() WHERE id = ?", packageId);

        mockMvc.perform(get("/v1/packages/{id}/classification", packageId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
