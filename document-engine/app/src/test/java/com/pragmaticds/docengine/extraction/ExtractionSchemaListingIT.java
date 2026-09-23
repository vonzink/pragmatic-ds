package com.pragmaticds.docengine.extraction;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * {@code GET /v1/extraction-schemas} and {@code GET /v1/extraction-schemas/{documentTypeCode}} —
 * reading back what authoring wrote.
 *
 * <p>Authoring shipped without a reader, which left an org able to write a schema and unable to see
 * it. The tests below pin the two answers that makes possible: WHICH schema decides each type for
 * this org, and WHAT this org authored for one of them.
 */
class ExtractionSchemaListingIT extends AbstractExtractionIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TENANT_VERSION = "9.0.0";

    @AfterEach
    void removeAuthoredSchemas() {
        // Same reasoning as SchemaAuthoringApiIT: the dev filter clears the tenant on the way out
        // of every request, and an org schema left behind would shadow the global for every other
        // extraction IT sharing this container.
        restoreTenant();
        jdbc.update(
                "DELETE FROM extracted_field WHERE schema_id IN"
                        + " (SELECT id FROM extraction_schema WHERE org_id IS NOT NULL)");
        jdbc.update("DELETE FROM extraction_schema WHERE org_id IS NOT NULL");
        schemaLoader.invalidateAll();
    }

    // ── the effective listing ───────────────────────────────────────────────

    @Test
    void the_listing_reports_a_global_type_as_GLOBAL_before_this_org_authors_one() throws Exception {
        mockMvc.perform(get("/v1/extraction-schemas").header("X-Dev-Role", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemas[?(@.documentTypeCode=='PAYSTUB')].scope").value("GLOBAL"));
        restoreTenant();
    }

    /**
     * The listing's central claim: it reports what DECIDES extraction, not merely what exists. After
     * authoring, PAYSTUB must flip to ORG and carry the tenant version — the same answer the loader
     * gives the extraction engine, because it is asked rather than re-derived.
     */
    @Test
    void authoring_flips_the_listing_to_ORG_and_reports_the_deciding_version() throws Exception {
        authorGlobalPaystubAs(TENANT_VERSION).andExpect(status().isCreated());
        restoreTenant();

        mockMvc.perform(get("/v1/extraction-schemas").header("X-Dev-Role", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemas[?(@.documentTypeCode=='PAYSTUB')].scope").value("ORG"))
                .andExpect(
                        jsonPath("$.schemas[?(@.documentTypeCode=='PAYSTUB')].version")
                                .value(TENANT_VERSION))
                // The field count is what makes wholesale shadowing visible: this definition is the
                // global one re-posted, so the count must match rather than merge to something
                // larger.
                .andExpect(
                        jsonPath("$.schemas[?(@.documentTypeCode=='PAYSTUB')].fieldCount").value(18));
        restoreTenant();
    }

    @Test
    void a_type_this_org_never_authored_stays_GLOBAL_after_authoring_another() throws Exception {
        authorGlobalPaystubAs(TENANT_VERSION).andExpect(status().isCreated());
        restoreTenant();

        mockMvc.perform(get("/v1/extraction-schemas").header("X-Dev-Role", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemas[?(@.documentTypeCode=='W2')].scope").value("GLOBAL"));
        restoreTenant();
    }

    // ── the authored history ────────────────────────────────────────────────

    /**
     * The gap this endpoint closes: an author can get their own definition back, as JSON rather
     * than as a double-encoded string, and edit and re-post it.
     */
    @Test
    void an_authored_definition_comes_back_as_json_not_as_a_string() throws Exception {
        authorGlobalPaystubAs(TENANT_VERSION).andExpect(status().isCreated());
        restoreTenant();

        mockMvc.perform(get("/v1/extraction-schemas/PAYSTUB").header("X-Dev-Role", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentTypeCode").value("PAYSTUB"))
                .andExpect(jsonPath("$.versions.length()").value(1))
                .andExpect(jsonPath("$.versions[0].version").value(TENANT_VERSION))
                .andExpect(jsonPath("$.versions[0].active").value(true))
                // An object, not a string: $.versions[0].definition.fields is only addressable if
                // the definition was serialised as JSON.
                .andExpect(jsonPath("$.versions[0].definition.fields.length()").value(18));
        restoreTenant();
    }

    /**
     * Retired versions stay in the history, newest first.
     *
     * <p>They are the answer to "why does this field cite a version that no longer applies":
     * {@code extracted_field.schema_id} points at retired rows, and the authoring rule requires a
     * new version to exceed the highest EVER used rather than the highest active.
     */
    @Test
    void the_history_keeps_retired_versions_newest_first() throws Exception {
        authorGlobalPaystubAs("9.0.0").andExpect(status().isCreated());
        restoreTenant();
        authorGlobalPaystubAs("10.0.0").andExpect(status().isCreated());
        restoreTenant();

        mockMvc.perform(get("/v1/extraction-schemas/PAYSTUB").header("X-Dev-Role", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versions.length()").value(2))
                // 10.0.0 before 9.0.0 — numeric ordering. Lexically "10.0.0" sorts BELOW "9.0.0",
                // which is exactly the mistake SchemaVersions exists to prevent in one place.
                .andExpect(jsonPath("$.versions[0].version").value("10.0.0"))
                .andExpect(jsonPath("$.versions[0].active").value(true))
                .andExpect(jsonPath("$.versions[1].version").value("9.0.0"))
                .andExpect(jsonPath("$.versions[1].active").value(false));
        restoreTenant();
    }

    /**
     * A type this org has not authored is 404 even though it exists globally.
     *
     * <p>Deliberate: the endpoint answers "what have I authored", and returning the built-in's
     * internals would publish the platform's regexes to every tenant as a side effect of adding a
     * reader.
     */
    @Test
    void a_type_this_org_never_authored_is_404_even_though_it_exists_globally() throws Exception {
        mockMvc.perform(get("/v1/extraction-schemas/PAYSTUB").header("X-Dev-Role", "ADMIN"))
                .andExpect(status().isNotFound());
        restoreTenant();
    }

    // ── RBAC, and the matcher ordering that IS the gate ─────────────────────

    /**
     * The ordering proof, in the shape {@code RawContentAdminBoundaryIT} uses.
     *
     * <p>Nothing but the matrix enforces a role on these paths — unlike triage, where a service-side
     * check also refuses. So if the matcher were placed AFTER the broad {@code GET /v1/**} rule,
     * every role below would read the org's authored regexes. A nonexistent type separates the two
     * outcomes: 403 means the role gate refused, 404 means it let the caller through and the
     * controller reported absence.
     */
    @Test
    void the_reads_are_admin_only_and_their_matchers_precede_the_broad_read_rule() throws Exception {
        for (String role : java.util.List.of("READONLY", "PROCESSOR", "REVIEWER")) {
            mockMvc.perform(get("/v1/extraction-schemas").header("X-Dev-Role", role))
                    .andExpect(status().isForbidden());
            restoreTenant();
            mockMvc.perform(get("/v1/extraction-schemas/NO_SUCH_TYPE").header("X-Dev-Role", role))
                    .andExpect(status().isForbidden());
            restoreTenant();
        }

        // ADMIN passes the role gate and reaches the controller, which reports absence. This 404 is
        // what proves the 403s above were authorization and not a missing route.
        mockMvc.perform(get("/v1/extraction-schemas/NO_SUCH_TYPE").header("X-Dev-Role", "ADMIN"))
                .andExpect(status().isNotFound());
        restoreTenant();
        mockMvc.perform(get("/v1/extraction-schemas").header("X-Dev-Role", "ADMIN"))
                .andExpect(status().isOk());
        restoreTenant();
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private void restoreTenant() {
        com.pragmaticds.docengine.platform.tenancy.TenantContext.set(ORG_DEV);
    }

    private org.springframework.test.web.servlet.ResultActions authorGlobalPaystubAs(String version)
            throws Exception {
        String definition =
                jdbc.queryForObject(
                        "SELECT definition::text FROM extraction_schema"
                                + " WHERE org_id IS NULL AND document_type_code = 'PAYSTUB'"
                                + " AND is_active",
                        String.class);
        String body =
                JSON.createObjectNode()
                        .put("documentTypeCode", "PAYSTUB")
                        .put("version", version)
                        .set("definition", JSON.readTree(definition))
                        .toString();
        org.springframework.test.web.servlet.ResultActions result =
                mockMvc.perform(
                        post("/v1/extraction-schemas")
                                .header("X-Dev-Role", "ADMIN")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body));
        restoreTenant();
        return result;
    }
}
