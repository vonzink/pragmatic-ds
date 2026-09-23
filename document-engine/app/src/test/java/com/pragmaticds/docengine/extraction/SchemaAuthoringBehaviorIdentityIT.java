package com.pragmaticds.docengine.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pragmaticds.docengine.extraction.schema.ExtractionSchemaLoader.SchemaFingerprint;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * The property an authoring API is most likely to break silently: a tenant-authored schema must
 * participate in BEHAVIOR IDENTITY and the parse-once reuse fingerprint.
 *
 * <p>The hazard is specific. {@code ReuseFingerprintService} stamps a completed parse with a hash
 * over, among other things, the org's winning extraction schemas. If a run that extracted under a
 * tenant schema were stamped with a fingerprint that did not encode that schema, a LATER parse of
 * the same bytes could be served the cached result of a parse produced by DIFFERENT behavior — a
 * wrong answer, delivered fast, with nothing to notice it by.
 *
 * <p>What this test establishes is that the property holds for FREE, and exactly why. The
 * fingerprint view is built from the loader's post-shadowing winning rows, and a tenant row is just
 * a row: it wins the type, its version and its definition go into the view verbatim, and the global
 * definition drops out. No fingerprint code knows authoring exists, and none needs to. The two
 * things that could still break it are the two things asserted here — the loader's CACHE must be
 * dropped when a row is written, and the definition must survive the composer's STRICT re-read.
 */
class SchemaAuthoringBehaviorIdentityIT extends AbstractExtractionIT {

    /**
     * The composer's parse settings, restated. {@code ReuseFingerprintService.STRICT_MAPPER} is
     * private to {@code :app}'s reuse package and {@code SchemaAuthoringService.STRICT} is private
     * to {@code :extraction}; this is the third copy, and it exists so a divergence between the
     * first two shows up as a FAILING TEST rather than as an org whose reuse quietly stopped
     * working. Keep these three in step.
     */
    private static final ObjectMapper COMPOSER_STRICTNESS =
            new ObjectMapper(
                            JsonFactory.builder()
                                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                                    .build())
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    @AfterEach
    void removeAuthoredSchemas() {
        restoreTenant();
        jdbc.update(
                "DELETE FROM extracted_field WHERE schema_id IN"
                        + " (SELECT id FROM extraction_schema WHERE org_id IS NOT NULL)");
        jdbc.update("DELETE FROM extraction_schema WHERE org_id IS NOT NULL");
        schemaLoader.invalidateAll();
    }

    /**
     * Authoring changes the org's behavior view — immediately, in the same process, with no
     * invalidation call by the test.
     *
     * <p>The "with no invalidation call" clause is the assertion. Every other extraction IT drops
     * the cache in {@code @BeforeEach} because it writes schema rows behind the loader's back; this
     * one deliberately does not, because the service registers the invalidation itself, after the
     * transaction completes. Remove that and this test fails while everything else stays green.
     */
    @Test
    void an_authored_schema_replaces_the_global_one_in_the_fingerprint_view() throws Exception {
        SchemaFingerprint globalPaystub = paystubEntryOf(schemaLoader.fingerprintViewForCurrentOrg());
        assertThat(globalPaystub).as("the shipped PAYSTUB schema is in the view to begin with").isNotNull();

        authorPaystub("9.0.0", globalPaystub.definition());

        SchemaFingerprint tenantPaystub = paystubEntryOf(schemaLoader.fingerprintViewForCurrentOrg());
        assertThat(tenantPaystub).isNotNull();
        assertThat(tenantPaystub.version()).isEqualTo("9.0.0");
        assertThat(tenantPaystub).isNotEqualTo(globalPaystub);
    }

    /**
     * Shadowing means ONE entry per type in the view, not two. A view carrying both the tenant and
     * the global definition would still change the hash, so a naive test would pass — and the hash
     * would then be describing behavior (the global schema) that the run does not execute.
     */
    @Test
    void the_shadowed_global_schema_does_not_also_appear_in_the_view() throws Exception {
        String shippedDefinition =
                paystubEntryOf(schemaLoader.fingerprintViewForCurrentOrg()).definition();

        authorPaystub("9.0.0", shippedDefinition);

        List<SchemaFingerprint> view = schemaLoader.fingerprintViewForCurrentOrg();
        assertThat(view.stream().filter(entry -> entry.documentTypeCode().equals("PAYSTUB")))
                .hasSize(1);
    }

    /**
     * The stored definition survives the composer's strict re-read — the check that keeps a tenant
     * schema from disabling reuse for its whole org.
     *
     * <p>The second half of the test is the same statement from the other side: the exact bytes the
     * composer would choke on are the exact bytes the door refuses. Without that equivalence,
     * authoring a duplicate-key definition would be accepted (jsonb keeps the last key without
     * complaint), and the only symptom would be that every parse for that org stopped being
     * stamped — no error, no log line at a level anyone watches, just no reuse, forever.
     */
    @Test
    void an_authored_definition_survives_the_composers_strict_re_read() throws Exception {
        String shippedDefinition =
                paystubEntryOf(schemaLoader.fingerprintViewForCurrentOrg()).definition();
        authorPaystub("9.0.0", shippedDefinition);

        String stored =
                paystubEntryOf(schemaLoader.fingerprintViewForCurrentOrg()).definition();
        assertThat(COMPOSER_STRICTNESS.readTree(stored)).isNotNull();

        String duplicateKey = "{\"fields\": [{\"name\": \"a\"}], \"fields\": [{\"name\": \"b\"}]}";
        assertThatThrownBy(() -> COMPOSER_STRICTNESS.readTree(duplicateKey))
                .as("the composer would refuse these bytes")
                .isInstanceOf(Exception.class);
        mockMvc.perform(
                        post("/v1/extraction-schemas")
                                .header("X-Dev-Role", "ADMIN")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"documentTypeCode\":\"PAYSTUB\",\"version\":\"9.1.0\","
                                                + "\"definition\":" + duplicateKey + "}"))
                .andExpect(status().isBadRequest());
    }

    private void authorPaystub(String version, String definition) throws Exception {
        String body =
                JSON.createObjectNode()
                        .put("documentTypeCode", "PAYSTUB")
                        .put("version", version)
                        .set("definition", JSON.readTree(definition))
                        .toString();
        mockMvc.perform(
                        post("/v1/extraction-schemas")
                                .header("X-Dev-Role", "ADMIN")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isCreated());
        restoreTenant();
    }

    /**
     * Re-binds the org after a MockMvc call. The dev auth filter binds {@code TenantContext} on the
     * way in and clears it on the way out, and MockMvc runs that chain INLINE on the test thread, so
     * every request leaves the test itself tenantless — the tenancy contract working fail-closed,
     * not a test defect.
     */
    private void restoreTenant() {
        com.pragmaticds.docengine.platform.tenancy.TenantContext.set(ORG_DEV);
    }

    private static SchemaFingerprint paystubEntryOf(List<SchemaFingerprint> view) {
        return view.stream()
                .filter(entry -> entry.documentTypeCode().equals("PAYSTUB"))
                .findFirst()
                .orElse(null);
    }
}
