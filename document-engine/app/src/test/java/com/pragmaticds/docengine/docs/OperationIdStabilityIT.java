package com.pragmaticds.docengine.docs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pragmaticds.docengine.AbstractPostgresIT;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code operationId} is a PUBLIC name: a client generator turns it into a method name, so changing
 * one renames a method in every generated client. Nothing about writing a controller makes that
 * visible, and the failure lands in someone else's build.
 *
 * <h2>The failure this exists to catch, which actually happened</h2>
 *
 * <p>springdoc derives {@code operationId} from the METHOD name. Two methods sharing a name do not
 * collide loudly — springdoc numbers them {@code name_1}, {@code name_2}, … in scan order. So a
 * method called {@code get} does not merely take the next number, it INSERTS into that sequence and
 * RENUMBERS every later member.
 *
 * <p>Adding {@code PackageTriageController.get} on this branch moved six already-shipped endpoints
 * by one — export, documents, classification, jobs, fields and fields.md — in a change that touched
 * none of them. It was caught by regenerating {@code docs/api/openapi.json} and reading the diff by
 * hand, which is not a control. This test is the control.
 *
 * <p>{@code EngineResultApiIT} pins ONE id against exactly this hazard, and none of the six was that
 * one. A single pinned id cannot cover a sequence.
 */
@AutoConfigureMockMvc
class OperationIdStabilityIT extends AbstractPostgresIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path COMMITTED = Path.of("../docs/api/openapi.json");

    /** springdoc's collision shape: a method name with an ordinal welded on. */
    private static final Pattern AUTO_SUFFIXED = Pattern.compile("[A-Za-z]+_\\d+");

    /**
     * Every auto-suffixed id that already exists, frozen.
     *
     * <p>Not an approval. Each is a controller method sharing a name with another, and each is a
     * hostage to scan order — the reason the six moved. They are listed rather than fixed because
     * renaming a shipped {@code operationId} is itself the breaking change this test prevents; they
     * can only be cleaned up deliberately, alongside the consumers that generated against them.
     *
     * <p>The set is a CEILING. A new entry means a new controller method took a name another
     * already had, which is the condition that renumbers its neighbours — so the fix is to name the
     * method distinctly, never to widen this list.
     */
    private static final Set<String> GRANDFATHERED_AUTO_IDS =
            new LinkedHashSet<>(
                    java.util.List.of(
                            "get_1", // GET /v1/packages/{id}/usage
                            "get_2", // GET /v1/packages/{id}/export
                            "get_3", // GET /v1/packages/{id}/documents
                            "get_4", // GET /v1/packages/{id}/classification
                            "get_5", // GET /v1/jobs/{id}
                            "get_6", // GET /v1/documents/{id}/fields
                            "get_7", // GET /v1/documents/{id}/fields.md
                            "signedUrl_1", // GET /v1/files/{id}/signed-url
                            "signedUrl_2")); // GET /v1/documents/{id}/pdf/signed-url

    @Autowired private MockMvc mockMvc;

    /**
     * No endpoint in the committed contract may change its {@code operationId}.
     *
     * <p>Compared per {@code (path, method)} rather than as a set of ids, because a set comparison
     * cannot tell a rename from an addition plus a removal — and the renumbering bug looks exactly
     * like that.
     *
     * <p>An endpoint that has been REMOVED from the live document is not this test's business:
     * deleting an endpoint is a deliberate, visible act, whereas renaming one is neither.
     */
    @Test
    void no_shipped_endpoint_changes_its_operation_id() throws Exception {
        Map<String, String> committed = operationIds(committedContract());
        Map<String, String> live = operationIds(liveContract());

        Map<String, String> changed = new TreeMap<>();
        committed.forEach(
                (endpoint, id) -> {
                    String now = live.get(endpoint);
                    if (now != null && !now.equals(id)) {
                        changed.put(endpoint, id + " -> " + now);
                    }
                });

        assertThat(changed)
                .as(
                        "operationId is a public name — a client generator turns it into a method"
                            + " name. If this fails after adding a controller, the new method"
                            + " probably shares its NAME with an existing one (springdoc numbers"
                            + " those name_1, name_2, ... in scan order, so a new one INSERTS and"
                            + " renumbers the rest). Rename the new method to something distinct;"
                            + " do NOT regenerate docs/api/openapi.json to make this green.")
                .isEmpty();
    }

    /**
     * No NEW auto-suffixed id may appear.
     *
     * <p>The test above catches the damage; this one catches the cause, and catches it on the change
     * that introduces it rather than on the next one. A method whose id needs an ordinal has taken a
     * name that was already taken, and it is only harmless until someone adds another.
     */
    @Test
    void no_new_controller_method_joins_an_auto_numbered_sequence() throws Exception {
        Set<String> unexpected = new LinkedHashSet<>();
        for (String id : operationIds(liveContract()).values()) {
            if (AUTO_SUFFIXED.matcher(id).matches() && !GRANDFATHERED_AUTO_IDS.contains(id)) {
                unexpected.add(id);
            }
        }

        assertThat(unexpected)
                .as(
                        "a new operationId ending in _N means a controller method reused a name"
                            + " another already had. springdoc resolves that by numbering them in"
                            + " scan order, which makes every later member's public name depend on"
                            + " where a class happens to be scanned. Give the method a distinct"
                            + " name; do not add it to GRANDFATHERED_AUTO_IDS.")
                .isEmpty();
    }

    /**
     * Every endpoint and schema the engine serves must be in the committed contract, and every schema
     * must read there exactly as it is served.
     *
     * <p>The two tests above only compare what BOTH documents contain, so an endpoint that never
     * reached the committed file passes them silently. That happened twice in two days: {@code GET
     * /v1/documents/{id}/body.md} (#55) and {@code POST /v1/documents/{id}/ai-extract} (#68) were
     * live, deployed and absent from {@code docs/api/openapi.json} — the file consumers and client
     * generators read — until someone regenerated it by hand. Neither was a rename, so neither
     * tripped anything.
     *
     * <p>Removal is still not this test's business, for the reason given above. A schema's BODY is:
     * a new member on an existing response (#73's {@code absorbedUntypedPages}) is exactly the drift
     * a generated client would silently miss.
     */
    @Test
    void every_served_endpoint_and_schema_is_in_the_committed_contract() throws Exception {
        JsonNode committed = committedContract();
        JsonNode live = liveContract();

        Set<String> missingEndpoints = new java.util.TreeSet<>(operationKeys(live));
        missingEndpoints.removeAll(operationKeys(committed));

        JsonNode committedSchemas = committed.path("components").path("schemas");
        JsonNode liveSchemas = live.path("components").path("schemas");
        Set<String> missingSchemas = new java.util.TreeSet<>();
        Set<String> changedSchemas = new java.util.TreeSet<>();
        liveSchemas
                .fieldNames()
                .forEachRemaining(
                        name -> {
                            if (!committedSchemas.has(name)) {
                                missingSchemas.add(name);
                            } else if (!committedSchemas.path(name).equals(liveSchemas.path(name))) {
                                changedSchemas.add(name);
                            }
                        });

        String regenerate =
                " Regenerate docs/api/openapi.json from the RUNNING engine on :9090 (docker compose"
                        + " up --build api, then GET /v3/api-docs), NOT through MockMvc — MockMvc"
                        + " drops the port from servers[0].url (CommittedOpenApiContractTest). Keep"
                        + " the committed formatting: two-space indent, non-ASCII unescaped, no"
                        + " trailing newline. Then document the change in"
                        + " docs/consumer/income-extraction-contract.md.";
        // Soft, so one run names every kind of drift at once rather than one per regeneration.
        org.assertj.core.api.SoftAssertions.assertSoftly(
                softly -> {
                    softly.assertThat(missingEndpoints)
                            .as("served but absent from the committed contract." + regenerate)
                            .isEmpty();
                    softly.assertThat(missingSchemas)
                            .as("schemas served but absent from the committed contract." + regenerate)
                            .isEmpty();
                    softly.assertThat(changedSchemas)
                            .as(
                                    "schemas whose served shape differs from the committed contract."
                                            + regenerate)
                            .isEmpty();
                });
    }

    /** {@code "GET /v1/…"} for every operation, whether or not it declares an id. */
    private static Set<String> operationKeys(JsonNode contract) {
        Set<String> keys = new LinkedHashSet<>();
        JsonNode paths = contract.path("paths");
        paths.fieldNames()
                .forEachRemaining(
                        path ->
                                paths.path(path)
                                        .fieldNames()
                                        .forEachRemaining(
                                                method ->
                                                        keys.add(
                                                                method.toUpperCase(
                                                                                java.util.Locale
                                                                                        .ROOT)
                                                                        + " "
                                                                        + path)));
        return keys;
    }

    private JsonNode liveContract() throws Exception {
        return JSON.readTree(
                mockMvc.perform(get("/v3/api-docs"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsByteArray());
    }

    /**
     * The committed file, read from the repo rather than the classpath.
     *
     * <p>Same source {@code CommittedOpenApiContractTest} reads, and the same reason: this asserts
     * on the artifact consumers actually receive, not on a copy of it.
     */
    private JsonNode committedContract() {
        try {
            Path path = Files.exists(COMMITTED) ? COMMITTED : Path.of("docs/api/openapi.json");
            return JSON.readTree(Files.readAllBytes(path));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** {@code "GET /v1/…" -> operationId}, for every operation that declares one. */
    private static Map<String, String> operationIds(JsonNode contract) {
        Map<String, String> ids = new LinkedHashMap<>();
        JsonNode paths = contract.path("paths");
        paths.fieldNames()
                .forEachRemaining(
                        path ->
                                paths.path(path)
                                        .fieldNames()
                                        .forEachRemaining(
                                                method -> {
                                                    JsonNode id =
                                                            paths.path(path)
                                                                    .path(method)
                                                                    .path("operationId");
                                                    if (id.isTextual()) {
                                                        ids.put(
                                                                method.toUpperCase(
                                                                                java.util.Locale
                                                                                        .ROOT)
                                                                        + " "
                                                                        + path,
                                                                id.asText());
                                                    }
                                                }));
        return ids;
    }
}
