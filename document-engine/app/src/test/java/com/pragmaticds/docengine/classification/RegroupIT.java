package com.pragmaticds.docengine.classification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pragmaticds.docengine.extraction.AbstractExtractionIT;
import com.pragmaticds.docengine.orchestration.SyncExecutorTestConfig;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/**
 * The regroup primitive ({@code POST /v1/packages/{id}/regroup}, Spec 2 Task 6 / design §4-8): a
 * reviewer edits {@code logical_document_page} membership in one validated transaction, the change
 * is captured as an append-only {@code review_decision}, and the package's EXTRACTING stage is
 * re-kicked so fields refresh.
 *
 * <p>The sync executor ({@link SyncExecutorTestConfig}) makes the {@code reExtract} afterCommit
 * dispatch run inline, so the whole re-kick completes before the request returns — same setup
 * {@code ReExtractIT} uses. {@code seedCompletedJob} (inherited) supplies the completed-run job the
 * re-kick replays, since {@code runPipelineToExtraction} creates none.
 */
@Import(SyncExecutorTestConfig.class)
@TestPropertySource(
        properties = {
            "docengine.processing.retry-backoff-ms=0",
            "spring.main.allow-bean-definition-overriding=true"
        })
class RegroupIT extends AbstractExtractionIT {

    @Test
    void split_moves_trailing_pages_into_a_new_document_and_re_extracts() throws Exception {
        UUID packageId = insertPackage("regroup-split-it");
        insertFixturePages(packageId, "combined_package");
        insertFixtureLayout(packageId, "combined_package");
        runPipelineToExtraction(packageId);
        // runPipelineToExtraction creates no processing_job; seed the completed-run job the
        // regroup's reExtract replays (else it would 404).
        seedCompletedJob(packageId);

        // The BANK_STATEMENT document (pages 2..7 in the combined fixture).
        UUID bankDoc = documentOfType(packageId, "BANK_STATEMENT");
        List<UUID> bankPages = orderedPageIds(bankDoc); // SELECT page_id ... ORDER BY ordinal
        List<UUID> tail = bankPages.subList(3, bankPages.size()); // split after 3 pages

        String body =
                """
                {"intent":"SPLIT",
                 "moves":[],
                 "newDocuments":[{"tempId":"n1","documentTypeCode":"BANK_STATEMENT","pageIds":%s}],
                 "deletedDocumentIds":[],
                 "reason":"two statements were merged"}
                """
                        .formatted(jsonUuidArray(tail));

        mockMvc.perform(
                        post("/v1/packages/{id}/regroup", packageId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isOk())
                // a new document now exists; the original keeps only its first 3 pages
                .andExpect(
                        jsonPath("$.documents[?(@.documentTypeCode=='BANK_STATEMENT')]").isArray());

        // Membership moved: the tail pages belong to a different document than bankDoc.
        for (UUID p : tail) {
            UUID owner =
                    jdbc.queryForObject(
                            "SELECT logical_document_id FROM logical_document_page WHERE page_id = ?",
                            UUID.class,
                            p);
            assertThat(owner).isNotEqualTo(bankDoc);
        }
        // The new (human-shaped) document has null confidence; the original, now reshaped, too.
        assertThat(
                        jdbc.queryForObject(
                                "SELECT classification_confidence FROM logical_document WHERE id = ?",
                                java.math.BigDecimal.class,
                                bankDoc))
                .isNull();
        // …and BOTH boundaries are now the human's. The machine cut this package at a type change;
        // a reviewer has since decided where the second statement begins, and the row must say so
        // — the precedence rule (human > rule > type change > ai) is only enforceable if the
        // strongest reason is what the row records. Every document in the package that a human
        // touched reads HUMAN; nothing here re-derives it from confidence being null.
        assertThat(
                        jdbc.queryForList(
                                """
                                SELECT boundary_provenance FROM logical_document
                                 WHERE package_id = ? AND classification_confidence IS NULL
                                """,
                                String.class,
                                packageId))
                .isNotEmpty()
                .allMatch("HUMAN"::equals);
        // The job re-entered and finished EXTRACTING (terminal status again).
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM processing_job WHERE package_id = ?",
                                String.class,
                                packageId))
                .isEqualTo("HUMAN_REVIEW_REQUIRED");
    }

    @Test
    void a_regroup_nulls_the_absorbed_untyped_page_count_of_every_document_it_reshapes()
            throws Exception {
        // Issue #60 (V46): the machine writes absorbed_untyped_pages on every split; a human
        // reshaping the document makes that count describe a document that no longer exists,
        // so the same rule the nulled confidence follows applies — end to end through the
        // regroup endpoint, not only LogicalDocument.humanRegroup() in isolation.
        UUID packageId = insertPackage("regroup-absorbed-it");
        insertFixturePages(packageId, "combined_package");
        insertFixtureLayout(packageId, "combined_package");
        runPipelineToExtraction(packageId);
        seedCompletedJob(packageId);

        UUID bankDoc = documentOfType(packageId, "BANK_STATEMENT");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT absorbed_untyped_pages FROM logical_document WHERE id = ?",
                                Integer.class,
                                bankDoc))
                .as("the machine counted (0 or more) before any human touched the document")
                .isNotNull();
        List<UUID> tail = orderedPageIds(bankDoc).subList(3, orderedPageIds(bankDoc).size());

        mockMvc.perform(
                        post("/v1/packages/{id}/regroup", packageId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"intent":"SPLIT",
                                         "moves":[],
                                         "newDocuments":[{"tempId":"n1","documentTypeCode":"BANK_STATEMENT","pageIds":%s}],
                                         "deletedDocumentIds":[],
                                         "reason":"two statements were merged"}
                                        """
                                                .formatted(jsonUuidArray(tail))))
                .andExpect(status().isOk());

        // The reshaped original AND the human-created document both read NULL — "not counted",
        // never a fabricated 0 — while documents the reviewer did not touch keep their count.
        assertThat(
                        jdbc.queryForList(
                                """
                                SELECT absorbed_untyped_pages FROM logical_document
                                 WHERE package_id = ? AND boundary_provenance = 'HUMAN'
                                """,
                                Integer.class,
                                packageId))
                .hasSize(2)
                .containsOnlyNulls();
        assertThat(
                        jdbc.queryForList(
                                """
                                SELECT absorbed_untyped_pages FROM logical_document
                                 WHERE package_id = ? AND boundary_provenance <> 'HUMAN'
                                """,
                                Integer.class,
                                packageId))
                .isNotEmpty()
                .doesNotContainNull();
    }

    @Test
    void move_a_page_between_two_documents() throws Exception {
        UUID packageId = insertPackage("regroup-move-it");
        insertFixturePages(packageId, "combined_package");
        insertFixtureLayout(packageId, "combined_package");
        runPipelineToExtraction(packageId);
        seedCompletedJob(packageId);

        UUID bankDoc = documentOfType(packageId, "BANK_STATEMENT"); // 6 pages: losing one is safe
        UUID w2Doc = documentOfType(packageId, "W2");
        UUID moved = orderedPageIds(bankDoc).getLast();

        mockMvc.perform(
                        post("/v1/packages/{id}/regroup", packageId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(moveBody("MOVE_PAGES", List.of(moved), w2Doc)))
                .andExpect(status().isOk());

        // The moved page's membership now points at B, not A.
        UUID owner =
                jdbc.queryForObject(
                        "SELECT logical_document_id FROM logical_document_page WHERE page_id = ?",
                        UUID.class,
                        moved);
        assertThat(owner).isEqualTo(w2Doc);
        assertThat(owner).isNotEqualTo(bankDoc);
        // A no longer owns it.
        assertThat(orderedPageIds(bankDoc)).doesNotContain(moved);
    }

    @Test
    void merge_two_documents_into_one() throws Exception {
        UUID packageId = insertPackage("regroup-merge-it");
        insertFixturePages(packageId, "combined_package");
        insertFixtureLayout(packageId, "combined_package");
        runPipelineToExtraction(packageId);
        seedCompletedJob(packageId);

        UUID bankDoc = documentOfType(packageId, "BANK_STATEMENT");
        UUID w2Doc = documentOfType(packageId, "W2");
        List<UUID> bankPages = orderedPageIds(bankDoc);
        List<UUID> w2Pages = orderedPageIds(w2Doc);

        // Move ALL of B's pages into A, then delete the now-empty B.
        mockMvc.perform(
                        post("/v1/packages/{id}/regroup", packageId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(mergeBody(w2Pages, bankDoc, w2Doc)))
                .andExpect(status().isOk());

        // B is gone.
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM logical_document WHERE id = ?",
                                Long.class,
                                w2Doc))
                .isEqualTo(0L);
        // A owns both sets.
        assertThat(orderedPageIds(bankDoc)).containsAll(bankPages).containsAll(w2Pages);
        // A is human-shaped: a fabricated machine confidence would be a lie.
        assertThat(
                        jdbc.queryForObject(
                                "SELECT classification_confidence FROM logical_document WHERE id = ?",
                                java.math.BigDecimal.class,
                                bankDoc))
                .isNull();
    }

    @Test
    void assign_an_unassigned_page_into_a_new_document() throws Exception {
        UUID packageId = insertPackage("regroup-assign-it");
        insertFixturePages(packageId, "combined_package");
        insertFixtureLayout(packageId, "combined_package");
        runPipelineToExtraction(packageId);
        seedCompletedJob(packageId);

        // A GENUINELY unassigned page: a duplicate (so it is in no logical_document) that a
        // NOT_DUPLICATE verdict makes assignable — the verdict clears the signal but does NOT
        // assign the page, so it is still unassigned when the regroup promotes it into a new doc.
        UUID unassigned =
                jdbc.queryForObject(
                        "SELECT p.id FROM page p WHERE p.package_id = ?"
                                + " AND p.duplicate_of_page_id IS NOT NULL"
                                + " AND NOT EXISTS (SELECT 1 FROM logical_document_page ldp"
                                + " WHERE ldp.page_id = p.id) LIMIT 1",
                        UUID.class,
                        packageId);
        mockMvc.perform(
                        post("/v1/pages/{id}/verdict", unassigned)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"verdict\":\"NOT_DUPLICATE\",\"reason\":\"distinct\"}"))
                .andExpect(status().isOk());

        List<UUID> documentsBefore = allDocumentIds(packageId);

        mockMvc.perform(
                        post("/v1/packages/{id}/regroup", packageId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(newDocumentBody("BANK_STATEMENT", List.of(unassigned))))
                .andExpect(status().isOk());

        // The page now belongs to a brand-new document that did not exist before.
        UUID newDoc =
                jdbc.queryForObject(
                        "SELECT logical_document_id FROM logical_document_page WHERE page_id = ?",
                        UUID.class,
                        unassigned);
        assertThat(documentsBefore).doesNotContain(newDoc);
        // Human-shaped from birth: null confidence.
        assertThat(
                        jdbc.queryForObject(
                                "SELECT classification_confidence FROM logical_document WHERE id = ?",
                                java.math.BigDecimal.class,
                                newDoc))
                .isNull();
    }

    @Test
    void an_invalid_delta_writes_nothing() throws Exception {
        UUID packageId = insertPackage("regroup-atomicity-it");
        insertFixturePages(packageId, "combined_package");
        insertFixtureLayout(packageId, "combined_package");
        runPipelineToExtraction(packageId);
        seedCompletedJob(packageId);

        UUID bankDoc = documentOfType(packageId, "BANK_STATEMENT");
        UUID w2Doc = documentOfType(packageId, "W2");
        UUID validPage = orderedPageIds(bankDoc).getLast();

        // A page owned by ANOTHER package (same org) — an unassignable, 404 target.
        UUID foreignPackage = insertPackage("regroup-atomicity-foreign");
        insertFixturePages(foreignPackage, "combined_package");
        UUID foreignPage =
                jdbc.queryForObject(
                        "SELECT id FROM page WHERE package_id = ? LIMIT 1",
                        UUID.class,
                        foreignPackage);

        long membershipsBefore = packageMembershipCount(packageId);

        // The delta pairs a VALID move with an invalid one. Atomicity: the whole thing is rejected,
        // so even the valid move must NOT apply.
        String body =
                """
                {"intent":"MOVE_PAGES",
                 "moves":[{"pageId":"%s","toDocumentId":"%s"},
                          {"pageId":"%s","toDocumentId":"%s"}],
                 "newDocuments":[],"deletedDocumentIds":[],"reason":"atomicity"}
                """
                        .formatted(validPage, w2Doc, foreignPage, w2Doc);

        mockMvc.perform(
                        post("/v1/packages/{id}/regroup", packageId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isNotFound());

        // Nothing partially applied: the valid page still belongs to A, and the count is unchanged.
        assertThat(
                        jdbc.queryForObject(
                                "SELECT logical_document_id FROM logical_document_page"
                                        + " WHERE page_id = ?",
                                UUID.class,
                                validPage))
                .isEqualTo(bankDoc);
        assertThat(packageMembershipCount(packageId)).isEqualTo(membershipsBefore);
    }

    @Test
    void assigning_a_duplicate_page_without_a_verdict_is_409() throws Exception {
        UUID packageId = insertPackage("regroup-dup-409-it");
        insertFixturePages(packageId, "combined_package");
        insertFixtureLayout(packageId, "combined_package");
        runPipelineToExtraction(packageId);
        seedCompletedJob(packageId);

        // A duplicate page that still carries its signal (no verdict override) is not assignable.
        UUID dupPage =
                jdbc.queryForObject(
                        "SELECT id FROM page WHERE package_id = ?"
                                + " AND duplicate_of_page_id IS NOT NULL LIMIT 1",
                        UUID.class,
                        packageId);

        mockMvc.perform(
                        post("/v1/packages/{id}/regroup", packageId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(newDocumentBody("BANK_STATEMENT", List.of(dupPage))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAGE_NOT_ASSIGNABLE"));
    }

    @Test
    void another_orgs_package_is_not_found() throws Exception {
        // Seed a package owned by ORG_OTHER; the request runs as the default ORG_DEV principal, so
        // the org-scoped load misses and the package's very existence is hidden behind a 404.
        UUID otherOrgPackage = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO document_package (id, org_id, name) VALUES (?, ?, ?)",
                otherOrgPackage,
                ORG_OTHER,
                "other-org-pkg");

        mockMvc.perform(
                        post("/v1/packages/{id}/regroup", otherOrgPackage)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(moveBody("MOVE_PAGES", List.of(), UUID.randomUUID())))
                .andExpect(status().isNotFound());
    }

    @Test
    void reviewer_can_regroup() throws Exception {
        // The positive half of the RBAC matcher: a REVIEWER (not ADMIN) may regroup. Before the
        // SecurityConfig matcher this fell into the ADMIN-only /v1/** catch-all and was 403.
        UUID packageId = insertPackage("regroup-reviewer-it");
        insertFixturePages(packageId, "combined_package");
        insertFixtureLayout(packageId, "combined_package");
        runPipelineToExtraction(packageId);
        seedCompletedJob(packageId);

        UUID bankDoc = documentOfType(packageId, "BANK_STATEMENT");
        UUID w2Doc = documentOfType(packageId, "W2");

        mockMvc.perform(
                        post("/v1/packages/{id}/regroup", packageId)
                                .header("X-Dev-Role", "REVIEWER")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        moveBody(
                                                "MOVE_PAGES",
                                                List.of(orderedPageIds(bankDoc).getLast()),
                                                w2Doc)))
                .andExpect(status().isOk());
    }

    @Test
    void readonly_cannot_regroup() throws Exception {
        // The negative half of the RBAC matcher: a READONLY principal is denied before the
        // controller ever runs. The X-Dev-Role header is the dev auth adapter's role override.
        UUID packageId = insertPackage("regroup-readonly-it");
        insertFixturePages(packageId, "combined_package");

        mockMvc.perform(
                        post("/v1/packages/{id}/regroup", packageId)
                                .header("X-Dev-Role", "READONLY")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(moveBody("MOVE_PAGES", List.of(), UUID.randomUUID())))
                .andExpect(status().isForbidden());
    }

    @Test
    void a_corrected_fields_history_survives_re_extraction() throws Exception {
        UUID packageId = insertPackage("regroup-correction-history-it");
        insertFixturePages(packageId, "combined_package");
        insertFixtureLayout(packageId, "combined_package");
        runPipelineToExtraction(packageId);
        seedCompletedJob(packageId);

        // A field on the PAYSTUB document. (Since V11, W2 and BANK_STATEMENT extract too; the
        // query below filters to PAYSTUB explicitly, so this test is unaffected by new schemas.)
        UUID fieldId =
                jdbc.queryForObject(
                        "SELECT ef.id FROM extracted_field ef JOIN logical_document ld"
                                + " ON ef.logical_document_id = ld.id"
                                + " WHERE ld.package_id = ? AND ld.document_type_code = 'PAYSTUB'"
                                + " AND ef.is_current ORDER BY ef.field_name LIMIT 1",
                        UUID.class,
                        packageId);
        assertThat(fieldId).isNotNull();

        // Correct it: a CORRECT review_decision is appended, keyed on THIS field id.
        mockMvc.perform(
                        patch("/v1/fields/{id}", fieldId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"action\":\"CORRECT\",\"value\":\"999.99\","
                                                + "\"reason\":\"typo\"}"))
                .andExpect(status().isOk());
        assertThat(correctionDecisionCount(fieldId)).isEqualTo(1L);

        // Regroup the package (any valid move). Re-extraction is package-wide delete-then-recreate,
        // so every field — including the corrected one — is dropped and rebuilt with a NEW id.
        UUID bankDoc = documentOfType(packageId, "BANK_STATEMENT");
        UUID w2Doc = documentOfType(packageId, "W2");
        mockMvc.perform(
                        post("/v1/packages/{id}/regroup", packageId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        moveBody(
                                                "MOVE_PAGES",
                                                List.of(orderedPageIds(bankDoc).getLast()),
                                                w2Doc)))
                .andExpect(status().isOk());

        // The old field id is gone (re-extraction recreated it under a new id)...
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM extracted_field WHERE id = ?",
                                Long.class,
                                fieldId))
                .isEqualTo(0L);
        // ...yet the correction's history row still exists — option (a): orphan-with-history.
        assertThat(correctionDecisionCount(fieldId)).isEqualTo(1L);
    }

    @Test
    void regroup_and_verdict_decisions_appear_in_the_documents_history() throws Exception {
        UUID packageId = insertPackage("regroup-history-it");
        insertFixturePages(packageId, "combined_package");
        insertFixtureLayout(packageId, "combined_package");
        runPipelineToExtraction(packageId);
        seedCompletedJob(packageId);

        UUID bankDoc = documentOfType(packageId, "BANK_STATEMENT"); // 6 pages: losing one is safe
        UUID w2Doc = documentOfType(packageId, "W2");
        UUID moved = orderedPageIds(bankDoc).getLast();

        // A package-wide regroup writes a REGROUP decision keyed on the PACKAGE id (subject_type
        // LOGICAL_DOCUMENT), not on w2Doc — so it can only surface in w2Doc's history via the
        // document's package id.
        mockMvc.perform(
                        post("/v1/packages/{id}/regroup", packageId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(moveBody("MOVE_PAGES", List.of(moved), w2Doc)))
                .andExpect(status().isOk());

        // Override a verdict on a page that now belongs to w2Doc: an OVERRIDE_VERDICT decision keyed
        // on the PAGE id — so it can only surface in w2Doc's history via the document's page ids.
        UUID memberPage = orderedPageIds(w2Doc).getFirst();
        mockMvc.perform(
                        post("/v1/pages/{id}/verdict", memberPage)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"verdict\":\"NOT_BLANK\",\"reason\":\"legible\"}"))
                .andExpect(status().isOk());

        // The document's history must include BOTH the package-keyed REGROUP and the page-keyed
        // OVERRIDE_VERDICT, though neither decision's subject_id is the document id.
        mockMvc.perform(get("/v1/documents/{id}/history", w2Doc))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[*].action", hasItem("REGROUP")))
                .andExpect(jsonPath("$.entries[*].action", hasItem("OVERRIDE_VERDICT")));
    }

    private UUID documentOfType(UUID packageId, String typeCode) {
        return jdbc.queryForObject(
                "SELECT id FROM logical_document WHERE package_id = ? AND document_type_code = ?"
                        + " ORDER BY ordinal LIMIT 1",
                UUID.class,
                packageId,
                typeCode);
    }

    private List<UUID> orderedPageIds(UUID documentId) {
        return jdbc.queryForList(
                "SELECT page_id FROM logical_document_page WHERE logical_document_id = ?"
                        + " ORDER BY ordinal",
                UUID.class,
                documentId);
    }

    private static String jsonUuidArray(List<UUID> ids) {
        return ids.stream()
                .map(id -> "\"" + id + "\"")
                .collect(Collectors.joining(",", "[", "]"));
    }

    private List<UUID> allDocumentIds(UUID packageId) {
        return jdbc.queryForList(
                "SELECT id FROM logical_document WHERE package_id = ? ORDER BY ordinal",
                UUID.class,
                packageId);
    }

    private long packageMembershipCount(UUID packageId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM logical_document_page ldp JOIN logical_document ld"
                        + " ON ldp.logical_document_id = ld.id WHERE ld.package_id = ?",
                Long.class,
                packageId);
    }

    private long correctionDecisionCount(UUID fieldId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM review_decision WHERE subject_type = 'EXTRACTED_FIELD'"
                        + " AND action = 'CORRECT' AND subject_id = ?",
                Long.class,
                fieldId);
    }

    /** A regroup body whose only edit is moving {@code pageIds} to one target document. */
    private static String moveBody(String intent, List<UUID> pageIds, UUID toDocument) {
        return """
                {"intent":"%s","moves":%s,"newDocuments":[],"deletedDocumentIds":[],"reason":"it"}
                """
                .formatted(intent, movesJson(pageIds, toDocument));
    }

    /** A merge body: move {@code pageIds} into {@code toDocument} and delete {@code deletedDocument}. */
    private static String mergeBody(List<UUID> pageIds, UUID toDocument, UUID deletedDocument) {
        return """
                {"intent":"MERGE","moves":%s,"newDocuments":[],
                 "deletedDocumentIds":["%s"],"reason":"merge"}
                """
                .formatted(movesJson(pageIds, toDocument), deletedDocument);
    }

    /** A body that creates one new document owning {@code pageIds}. */
    private static String newDocumentBody(String typeCode, List<UUID> pageIds) {
        return """
                {"intent":"NEW_DOCUMENT","moves":[],
                 "newDocuments":[{"tempId":"n1","documentTypeCode":"%s","pageIds":%s}],
                 "deletedDocumentIds":[],"reason":"new"}
                """
                .formatted(typeCode, jsonUuidArray(pageIds));
    }

    private static String movesJson(List<UUID> pageIds, UUID toDocument) {
        return pageIds.stream()
                .map(p -> "{\"pageId\":\"" + p + "\",\"toDocumentId\":\"" + toDocument + "\"}")
                .collect(Collectors.joining(",", "[", "]"));
    }
}
