package com.pragmaticds.docengine.classification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pragmaticds.docengine.orchestration.ParserPort;
import com.pragmaticds.docengine.orchestration.ProcessingStatus;
import com.pragmaticds.docengine.security.DevAuthFilter;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * The unknown-document triage queue and the reviewer's label.
 *
 * <p>The combined fixture is the case the design exists for, and {@code SplittingStageIT} already
 * pins the loss it causes: the eight letter pages 12-19 classify UNKNOWN end to end, an untyped
 * page continues the open run rather than starting a document, and so they are ABSORBED by the
 * W-2 in front of them. That split is deliberate and stays; what these tests assert is that the
 * absorption is no longer SILENT — it surfaces as one triage item with the evidence to act on, and
 * a reviewer's answer lands append-only without touching a single machine row.
 */
class UnknownTriageIT extends AbstractClassificationIT {

    private static final UUID DEV_USER = DevAuthFilter.DEV_USER;

    @Autowired ParserPort parserPort;

    private void runStage(UUID packageId, ProcessingStatus stage) {
        assertThat(
                        parserPort
                                .run(
                                        new ParserPort.StageRequest(
                                                UUID.randomUUID(), packageId, stage, 1, "it-idem"))
                                .success())
                .as("stage %s succeeds", stage)
                .isTrue();
    }

    /** CLASSIFYING → SPLITTING over the 20-page combined package. */
    private UUID seedCombinedPackage(String name) {
        UUID packageId = insertPackage(name);
        insertFixturePages(packageId, "combined_package");
        runStage(packageId, ProcessingStatus.CLASSIFYING);
        runStage(packageId, ProcessingStatus.SPLITTING);
        return packageId;
    }

    private UUID w2DocumentOf(UUID packageId) {
        return jdbc.queryForObject(
                "SELECT id FROM logical_document WHERE package_id = ? AND document_type_code = 'W2'",
                UUID.class,
                packageId);
    }

    private List<UUID> absorbedPageIds(UUID packageId) {
        // Pages 12-19 of the package — the letter that classified UNKNOWN end to end.
        return jdbc.queryForList(
                "SELECT id FROM page WHERE package_id = ? AND package_page_index BETWEEN 12 AND 19"
                        + " ORDER BY package_page_index",
                UUID.class,
                packageId);
    }

    @Test
    void the_absorbed_letter_run_surfaces_as_one_triage_item() throws Exception {
        UUID packageId = seedCombinedPackage("triage-queue-it");

        mockMvc.perform(get("/v1/packages/{id}/triage", packageId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.packageId").value(packageId.toString()))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].reason").value("ABSORBED_UNKNOWN_RUN"))
                // The document that swallowed the run, not the run's own (absent) type — the
                // reviewer needs to know what these pages are currently being read as.
                .andExpect(jsonPath("$.items[0].documentTypeCode").value("W2"))
                .andExpect(jsonPath("$.items[0].startPackagePageIndex").value(12))
                .andExpect(jsonPath("$.items[0].endPackagePageIndex").value(19))
                .andExpect(jsonPath("$.items[0].pageIds.length()").value(8))
                .andExpect(jsonPath("$.items[0].label.decisionId").doesNotExist());
    }

    /**
     * The queue is a projection, not a state machine: it reports what the CURRENT rows say. Before
     * SPLITTING there is no absorption to report, and listing every page as reviewer work would
     * misread an in-flight package as a backlog.
     */
    @Test
    void a_package_that_has_not_been_split_has_an_empty_queue() throws Exception {
        UUID packageId = insertPackage("triage-unsplit-it");
        insertFixturePages(packageId, "combined_package");

        mockMvc.perform(get("/v1/packages/{id}/triage", packageId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void labelling_a_run_appends_a_decision_and_mutates_nothing() throws Exception {
        UUID packageId = seedCombinedPackage("triage-label-it");
        UUID documentId = w2DocumentOf(packageId);
        List<UUID> pageIds = absorbedPageIds(packageId);

        mockMvc.perform(
                        post("/v1/documents/{id}/classification", documentId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(labelBody("VOE", pageIds, "a lender letter, not the W-2")))
                .andExpect(status().isOk())
                // The document's type SURVIVES the label. A triage answer speaks for eight pages
                // of an eleven-page document; retyping the whole thing would be a different claim.
                .andExpect(jsonPath("$.documentTypeCode").value("W2"))
                .andExpect(jsonPath("$.decision.action").value("RECLASSIFY"))
                .andExpect(jsonPath("$.decision.previousValue").value("UNKNOWN"))
                .andExpect(jsonPath("$.decision.newValue").value("VOE"))
                .andExpect(jsonPath("$.decision.decidedBy").value(DEV_USER.toString()));

        assertThat(
                        jdbc.queryForObject(
                                "SELECT document_type_code FROM logical_document WHERE id = ?",
                                String.class,
                                documentId))
                .isEqualTo("W2");
        // The machine's verdict on every labelled page is untouched: still UNKNOWN, still current,
        // still ONE row per page. This is the invariant the whole design rests on.
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM classification_result r JOIN page p ON p.id ="
                                    + " r.subject_id WHERE r.subject_type = 'PAGE' AND r.is_current"
                                    + " AND r.document_type_code = 'UNKNOWN' AND p.package_id = ?"
                                    + " AND p.package_page_index BETWEEN 12 AND 19",
                                Integer.class,
                                packageId))
                .isEqualTo(8);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM review_decision WHERE subject_id = ? AND"
                                        + " subject_type = 'CLASSIFICATION' AND action ="
                                        + " 'RECLASSIFY'",
                                Integer.class,
                                documentId))
                .isEqualTo(1);
        // The label names its run inside the decision, which is what makes it auditable at all.
        assertThat(
                        jdbc.queryForObject(
                                "SELECT jsonb_array_length(new_value -> 'pageIds') FROM"
                                        + " review_decision WHERE subject_id = ? AND subject_type ="
                                        + " 'CLASSIFICATION'",
                                Integer.class,
                                documentId))
                .isEqualTo(8);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT metadata ->> 'scope' FROM audit_event WHERE action ="
                                        + " 'DOCUMENT_RECLASSIFIED' AND subject_id = ?",
                                String.class,
                                documentId))
                .isEqualTo("PAGE_RUN");
    }

    @Test
    void a_labelled_run_comes_back_answered_and_appears_in_the_history_strip() throws Exception {
        UUID packageId = seedCombinedPackage("triage-answered-it");
        UUID documentId = w2DocumentOf(packageId);

        mockMvc.perform(
                        post("/v1/documents/{id}/classification", documentId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(labelBody("VOE", absorbedPageIds(packageId), null)))
                .andExpect(status().isOk());

        // Still listed — the queue holds no state of its own, so hiding an answered run would
        // leave the answer unverifiable from the surface that asked for it.
        mockMvc.perform(get("/v1/packages/{id}/triage", packageId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].label.documentTypeCode").value("VOE"))
                .andExpect(jsonPath("$.items[0].label.decidedBy").value(DEV_USER.toString()));

        // And it rides the existing decision strip with no special case: the strip is keyed on
        // subject id, and the label's subject is the document.
        mockMvc.perform(get("/v1/documents/{id}/history", documentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].subjectType").value("CLASSIFICATION"))
                .andExpect(jsonPath("$.entries[0].action").value("RECLASSIFY"))
                .andExpect(jsonPath("$.entries[0].newValue").value("VOE"));
    }

    /** A label naming pages the document does not hold would sit in the audit trail lying. */
    @Test
    void labelling_a_page_the_document_does_not_hold_is_a_400() throws Exception {
        UUID packageId = seedCombinedPackage("triage-foreign-page-it");
        UUID documentId = w2DocumentOf(packageId);
        UUID paystubPage =
                jdbc.queryForObject(
                        "SELECT id FROM page WHERE package_id = ? AND package_page_index = 0",
                        UUID.class,
                        packageId);

        mockMvc.perform(
                        post("/v1/documents/{id}/classification", documentId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(labelBody("VOE", List.of(paystubPage), null)))
                .andExpect(status().isBadRequest());
    }

    /**
     * Triage is a WORKLIST. A read-only consumer gets the classification evidence endpoint; the
     * queue that says "a human should act on these pages" is REVIEWER work.
     */
    @Test
    void a_readonly_caller_may_not_open_the_triage_queue() throws Exception {
        UUID packageId = seedCombinedPackage("triage-rbac-it");

        mockMvc.perform(get("/v1/packages/{id}/triage", packageId).header("X-Dev-Role", "READONLY"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/v1/packages/{id}/triage", packageId).header("X-Dev-Role", "REVIEWER"))
                .andExpect(status().isOk());
    }

    /**
     * The declarative half of the gate: {@code SecurityConfig.matrix} carries a
     * {@code GET /v1/packages/*&#47;triage} matcher at REVIEWER, ahead of the broad
     * {@code GET /v1/**} rule.
     *
     * <p>An absent id is the probe, per {@code RawContentAdminBoundaryIT}: 404 proves the REVIEWER
     * passed the matcher and reached the controller, which then reported absence. It cannot prove
     * the ORDERING the way that test does — {@code UnknownTriageService.requireReviewer()} runs
     * before the package load, so a READONLY caller is refused by the service whether the matcher
     * precedes {@code /v1/**} or not. What this pins is the half that a matrix edit could break in
     * silence: narrowing the matcher to exclude REVIEWER would turn this 404 into a 403.
     */
    @Test
    void a_reviewer_passes_the_matcher_and_reaches_the_controller() throws Exception {
        mockMvc.perform(
                        get("/v1/packages/{id}/triage", UUID.randomUUID())
                                .header("X-Dev-Role", "REVIEWER"))
                .andExpect(status().isNotFound());
    }

    /** The house rule: another org's id is ABSENT, never forbidden — a 403 would confirm it. */
    @Test
    void another_orgs_package_triage_is_404() throws Exception {
        UUID foreignPackage = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO document_package (id, org_id, name) VALUES (?, ?, ?)",
                foreignPackage,
                ORG_OTHER,
                "triage-foreign-org-it");

        mockMvc.perform(get("/v1/packages/{id}/triage", foreignPackage))
                .andExpect(status().isNotFound());
    }

    private static String labelBody(String typeCode, List<UUID> pageIds, String reason) {
        String ids =
                pageIds.stream()
                        .map(id -> "\"" + id + "\"")
                        .reduce((a, b) -> a + "," + b)
                        .orElse("");
        return "{\"documentTypeCode\":\""
                + typeCode
                + "\",\"pageIds\":["
                + ids
                + "]"
                + (reason == null ? "" : ",\"reason\":\"" + reason + "\"")
                + "}";
    }
}
