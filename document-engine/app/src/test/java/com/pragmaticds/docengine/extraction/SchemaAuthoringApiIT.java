package com.pragmaticds.docengine.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * {@code POST /v1/extraction-schemas} — authoring a tenant extraction schema at runtime, which
 * before this endpoint required a hand-written Flyway migration.
 *
 * <p>The central assertion is not that a row appears. It is that a row authored through the API is
 * INDISTINGUISHABLE from a row a migration would have seeded: the loader's shadowing rule picks it
 * up, extraction runs under it, and the read model reports its version as the one that decided —
 * with no code anywhere that knows the row came from an HTTP request. Several tests therefore drive
 * a full pipeline rather than inspecting the table.
 *
 * <p>Every test cleans up its own org rows in {@link #removeAuthoredSchemas()}. This is not
 * politeness: the ITs share one Postgres container, an org PAYSTUB schema SHADOWS the global one
 * wholesale for that org, and a leftover row would silently rewrite what every other extraction IT
 * in the suite extracts.
 */
class SchemaAuthoringApiIT extends AbstractExtractionIT {

    /**
     * Comfortably above the highest seeded global version, so a test can author "the same schema,
     * one version up" without needing to know which migration last touched the type.
     */
    private static final String TENANT_VERSION = "9.0.0";

    @AfterEach
    void removeAuthoredSchemas() {
        // The dev auth filter binds the org for the duration of a request and CLEARS it on the way
        // out — on this very thread, since MockMvc runs the filter chain inline. Re-bind before
        // touching jdbc, whose datasource stamps current_org from exactly this ThreadLocal.
        restoreTenant();
        // Fields first: extracted_field.schema_id is a FK onto the row about to go, which is
        // exactly the property that makes retirement (not deletion) the production rule.
        jdbc.update(
                "DELETE FROM extracted_field WHERE schema_id IN"
                        + " (SELECT id FROM extraction_schema WHERE org_id IS NOT NULL)");
        jdbc.update("DELETE FROM extraction_schema WHERE org_id IS NOT NULL");
        schemaLoader.invalidateAll();
    }

    // ── the happy path, measured through extraction ─────────────────────────

    /**
     * The whole point, end to end: a definition posted over HTTP decides a real extraction run.
     *
     * <p>The definition posted is the SHIPPED global PAYSTUB definition, read back out of the table
     * and re-submitted under a tenant version. Reusing it rather than hand-writing a toy schema is
     * deliberate — it means the assertion below ("the same ten fields come out") isolates exactly
     * one variable: WHICH ROW the loader selected. A hand-written schema would have conflated
     * "authoring works" with "my toy schema happens to match the fixture".
     */
    @Test
    void an_authored_tenant_schema_decides_a_real_extraction_run() throws Exception {
        authorGlobalPaystubAs(TENANT_VERSION).andExpect(status().isCreated())
                .andExpect(jsonPath("$.documentTypeCode").value("PAYSTUB"))
                .andExpect(jsonPath("$.version").value(TENANT_VERSION))
                .andExpect(jsonPath("$.active").value(true))
                // Nothing of this org's was retired: it had no PAYSTUB schema of its own, and the
                // GLOBAL rows are shadowed, never retired.
                .andExpect(jsonPath("$.retiredVersions.length()").value(0));

        UUID packageId = insertPackage("authored-schema-it");
        insertFixturePages(packageId, "paystub_complete");
        insertFixtureLayout(packageId, "paystub_complete");
        runPipelineToExtraction(packageId);

        mockMvc.perform(get("/v1/documents/{id}/fields", onlyDocumentOf(packageId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentTypeCode").value("PAYSTUB"))
                // The version the read model reports is the version that DECIDED — the tenant one.
                .andExpect(jsonPath("$.schemaVersion").value(TENANT_VERSION))
                // paystub@1.5.0's eighteen declared fields — the same set the global row yields.
                .andExpect(jsonPath("$.fields.length()").value(18));
    }

    /**
     * Shadowing, not mutation. The global built-in is what every OTHER org still extracts under, so
     * an authoring write that touched it would be a cross-tenant write with a 201 on it — and RLS
     * would not catch a same-org UPDATE that happened to name a global row, because the failure
     * would be a query written without an org predicate, not a policy gap.
     */
    @Test
    void authoring_shadows_the_global_schema_and_leaves_every_global_row_untouched() throws Exception {
        List<Map<String, Object>> before = globalPaystubRows();

        authorGlobalPaystubAs(TENANT_VERSION).andExpect(status().isCreated());

        assertThat(globalPaystubRows()).isEqualTo(before);
        assertThat(before).as("the shipped PAYSTUB schema is still there to be shadowed").isNotEmpty();
    }

    /**
     * The supersession dance every seed migration since V10 performs — retire the org's active
     * version, insert the new one — now done by the service, in one transaction.
     */
    @Test
    void a_newer_version_retires_the_orgs_previous_one_and_never_deletes_it() throws Exception {
        authorGlobalPaystubAs("9.0.0").andExpect(status().isCreated());

        authorGlobalPaystubAs("9.1.0")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.retiredVersions[0]").value("9.0.0"));

        // Retired, not gone: extracted_field.schema_id must keep resolving for every value the
        // old version already produced (design D4, V12's own note).
        assertThat(ownedPaystubVersions())
                .containsExactlyInAnyOrderEntriesOf(Map.of("9.0.0", false, "9.1.0", true));
    }

    // ── versioning refusals ─────────────────────────────────────────────────

    /**
     * A lower version would be stored, marked active, and then never selected — the loader takes the
     * highest active version. A write that succeeds and silently does nothing is the failure this
     * 409 exists to prevent.
     */
    @Test
    void a_version_below_the_orgs_highest_is_a_conflict() throws Exception {
        authorGlobalPaystubAs("9.1.0").andExpect(status().isCreated());

        authorGlobalPaystubAs("9.0.0")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.params.highestExistingVersion").value("9.1.0"));
    }

    /**
     * Re-using a RETIRED version number is refused too, and the unique index would not have caught
     * it. A version is an identity {@code extracted_field.schema_id} cites; a second definition
     * under a used number rewrites the meaning of values already extracted under the first.
     */
    @Test
    void re_using_a_retired_version_number_is_a_conflict() throws Exception {
        authorGlobalPaystubAs("9.0.0").andExpect(status().isCreated());
        authorGlobalPaystubAs("9.1.0").andExpect(status().isCreated());

        authorGlobalPaystubAs("9.0.0").andExpect(status().isConflict());
    }

    @Test
    void a_version_that_is_not_three_numeric_segments_is_refused() throws Exception {
        // compareVersions falls back to STRING order on a non-numeric segment, so "highest version
        // wins" would stop meaning what it says.
        author("PAYSTUB", "9.0.0-rc1", globalPaystubDefinition())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.params.field").value("version"));
    }

    // ── definition refusals ─────────────────────────────────────────────────

    /**
     * The reuse hazard, and the reason this endpoint parses strictly rather than the way jsonb
     * does. {@code ReuseFingerprintService} re-reads every winning definition with duplicate-key
     * detection ON and withholds the fingerprint when that read throws — which disables parse-once
     * reuse for the whole org, silently, with no failing request to point at. Postgres jsonb would
     * have accepted these bytes without a word.
     */
    @Test
    void a_definition_with_a_duplicate_key_is_refused() throws Exception {
        String duplicated =
                """
                {"fields": [{"name": "a"}], "fields": [{"name": "b"}]}
                """;

        author("PAYSTUB", TENANT_VERSION, duplicated)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.params.field").value("definition"));
    }

    @Test
    void a_definition_the_loader_cannot_parse_is_refused() throws Exception {
        // A field with no extractors: the loader's own rule, enforced at the door instead of at
        // extraction time, by the loader's own parser.
        String noExtractors =
                """
                {"fields": [{"name": "grossPay", "dataType": "MONEY", "required": true,
                             "sensitive": false, "extractors": []}]}
                """;

        author("PAYSTUB", TENANT_VERSION, noExtractors)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.params.field").value("definition"));
    }

    /**
     * The loader does NOT compile patterns, so without this check a malformed regex would be
     * accepted, stored, activated, and then die once per document inside the extraction engine.
     */
    @Test
    void a_definition_whose_regex_does_not_compile_is_refused() throws Exception {
        String badRegex =
                """
                {"fields": [{"name": "grossPay", "dataType": "MONEY", "required": true,
                             "sensitive": false,
                             "extractors": [{"method": "ANCHOR_LABEL", "strength": 0.9,
                                             "label": {"kind": "literal", "pattern": "Gross Pay"},
                                             "value": {"pattern": "([0-9]+", "occurrence": 0,
                                                       "scope": "LINE"}}]}]}
                """;

        author("PAYSTUB", TENANT_VERSION, badRegex)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.params.field").value("definition.pattern"))
                // The field NAME is vocabulary the author chose; the pattern is not returned.
                .andExpect(jsonPath("$.params.fieldName").value("grossPay"));
    }

    /** The next-line join is a pattern like any other: compiled at the door, not per document. */
    @Test
    void a_definition_whose_join_next_line_does_not_compile_is_refused() throws Exception {
        author("PAYSTUB", TENANT_VERSION, joinNextLineDefinition("("))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.params.field").value("definition.pattern"))
                .andExpect(jsonPath("$.params.fieldName").value("employerName"));
    }

    @Test
    void a_definition_whose_join_next_line_compiles_is_accepted() throws Exception {
        author("PAYSTUB", TENANT_VERSION, joinNextLineDefinition("^(?:OR|AND) ([A-Z][A-Z .-]+)$"))
                .andExpect(status().isCreated());
    }

    private static String joinNextLineDefinition(String joinNextLine) {
        return """
                {"fields": [{"name": "employerName", "dataType": "STRING", "required": true,
                             "sensitive": false,
                             "extractors": [{"method": "ANCHOR_LABEL", "strength": 0.9,
                                             "label": {"kind": "literal", "pattern": "Employer"},
                                             "value": {"pattern": "[A-Z][A-Z ]+", "occurrence": 0,
                                                       "scope": "LINE", "joinNextLine": %s}}]}]}
                """.formatted(quoteJson(joinNextLine));
    }

    private static String quoteJson(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    @Test
    void a_definition_naming_an_unknown_normalizer_is_refused() throws Exception {
        String unknownNormalizer =
                """
                {"fields": [{"name": "grossPay", "dataType": "MONEY", "required": true,
                             "sensitive": false, "normalizer": "guilders",
                             "extractors": [{"method": "ANCHOR_LABEL", "strength": 0.9,
                                             "label": {"kind": "literal", "pattern": "Gross Pay"},
                                             "value": {"pattern": "[0-9.]+", "occurrence": 0,
                                                       "scope": "LINE"}}]}]}
                """;

        author("PAYSTUB", TENANT_VERSION, unknownNormalizer)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.params.field").value("normalizer"))
                .andExpect(jsonPath("$.params.normalizer").value("guilders"));
    }

    /**
     * A schema for a type nothing is ever classified as is not wrong, it is INERT — the author gets
     * a 201 and waits for fields that never arrive. A typo dies at the door instead.
     */
    @Test
    void a_definition_for_an_unknown_document_type_is_refused() throws Exception {
        author("PAYSTUBB", TENANT_VERSION, globalPaystubDefinition())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.params.field").value("documentTypeCode"));
    }

    // ── RBAC ────────────────────────────────────────────────────────────────

    /**
     * Authoring is ADMIN work, granted by {@code SecurityConfig.matrix}'s final {@code /v1/**} rule
     * and by nothing else — there is no annotation on the controller to keep in sync.
     *
     * <p>What this measures is the property that catch-all coverage cannot be trusted to keep: a
     * broader rule inserted ABOVE that line would silently widen this endpoint with no symptom. The
     * assertion works because 403 and 400 are different answers — a non-ADMIN role must be refused
     * BEFORE the controller ever sees the (deliberately invalid) body.
     */
    @Test
    void authoring_is_admin_only() throws Exception {
        for (String role : List.of("READONLY", "PROCESSOR", "REVIEWER")) {
            mockMvc.perform(
                            post("/v1/extraction-schemas")
                                    .header("X-Dev-Role", role)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{}"))
                    .andExpect(status().isForbidden());
        }
        // ADMIN passes the role gate and reaches the validator, which rejects the empty envelope.
        // That 400 is what proves the 403s above were authorization and not a missing route.
        mockMvc.perform(
                        post("/v1/extraction-schemas")
                                .header("X-Dev-Role", "ADMIN")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isBadRequest());
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private ResultActions author(String documentTypeCode, String version, String definition)
            throws Exception {
        String body =
                JSON.createObjectNode()
                        .put("documentTypeCode", documentTypeCode)
                        .put("version", version)
                        .set("definition", JSON.readTree(definition))
                        .toString();
        ResultActions result =
                mockMvc.perform(
                        post("/v1/extraction-schemas")
                                .header("X-Dev-Role", "ADMIN")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body));
        restoreTenant();
        return result;
    }

    /**
     * Re-binds the org after a MockMvc call. The dev auth filter binds {@code TenantContext} on the
     * way in and clears it on the way out, and MockMvc runs that chain INLINE on the test thread —
     * so every request leaves the test itself tenantless, and the next jdbc or loader call fails
     * fail-closed. That is the tenancy contract working, not a test defect; the test just has to
     * put back what the request took.
     */
    private void restoreTenant() {
        com.pragmaticds.docengine.platform.tenancy.TenantContext.set(ORG_DEV);
    }

    /** Re-submits the shipped PAYSTUB definition verbatim under a tenant version. */
    private ResultActions authorGlobalPaystubAs(String version) throws Exception {
        return author("PAYSTUB", version, globalPaystubDefinition());
    }

    private String globalPaystubDefinition() {
        return jdbc.queryForObject(
                "SELECT definition::text FROM extraction_schema"
                        + " WHERE org_id IS NULL AND document_type_code = 'PAYSTUB' AND is_active",
                String.class);
    }

    private List<Map<String, Object>> globalPaystubRows() {
        return jdbc.queryForList(
                "SELECT version, is_active, definition::text FROM extraction_schema"
                        + " WHERE org_id IS NULL AND document_type_code = 'PAYSTUB'"
                        + " ORDER BY version");
    }

    /** This org's own PAYSTUB versions, active flag included — retired rows kept. */
    private Map<String, Boolean> ownedPaystubVersions() {
        return jdbc.queryForList(
                        "SELECT version, is_active FROM extraction_schema"
                                + " WHERE org_id = ? AND document_type_code = 'PAYSTUB'",
                        ORG_DEV)
                .stream()
                .collect(
                        java.util.stream.Collectors.toMap(
                                row -> (String) row.get("version"),
                                row -> (Boolean) row.get("is_active")));
    }
}
