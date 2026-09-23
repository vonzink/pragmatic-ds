package com.pragmaticds.docengine.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * The wire contract for a repeating field, on BOTH read surfaces.
 *
 * <p>Spec 5a fixes the data model; this class is what stops the fix from stopping at the database.
 * Three properties are asserted and none is cosmetic:
 *
 * <ul>
 *   <li><b>Every occurrence names itself.</b> {@code ExportFieldView} is identified by
 *       {@code fieldName} alone — no id, no ordinal — so three occurrences under one name would
 *       recreate this spec's own failure class in the consumer: a system building a name-keyed map
 *       keeps one of three rents and silently discards the rest.
 *   <li><b>Occurrence order is defined.</b> The read path sorted by field name only and the export
 *       sorted in Java by field name only, so "property A" was whichever row the planner happened
 *       to hand back first. Both now break the tie on the group key.
 *   <li><b>Where a NULL key sorts is decided, not incidental.</b> Both paths put an unkeyed
 *       occurrence FIRST within its field name, which is the order
 *       {@link AbstractExtractionIT#currentOccurrences} already reads the table in.
 * </ul>
 */
class GroupedFieldApiIT extends AbstractExtractionIT {

    /**
     * Every occurrence the Schedule E fixture produces: 8 ungrouped + 5 Part I money lines x 3
     * property columns + 1 address x 3 rows + 7 Part II fields x 4 PRINTED row letters (A-D) +
     * 5 Part III fields x 2 printed letters (A-B) + Part IV's 3 (two fields on the one drawn
     * row, plus remicExcessInclusion's single null-keyed MISSING — its column caption
     * interleaves with the row beneath it and cannot be named). A LABELED row group emits one
     * occurrence per declared printed letter — like a COLUMN group, the key set comes from the
     * form, so blank rows persist MISSING per occurrence (design D5) and the count is
     * rows-declared, not rows-found: 8 + 15 + 3 + 28 + 10 + 3 = 67, matching
     * ScheduleEExtractionIT's census of the same fixture.
     */
    private static final int SCHEDULE_E_OCCURRENCES = 67;

    private UUID scheduleEPackage() {
        UUID packageId = insertPackage("grouped-api-it");
        insertFixturePages(packageId, "schedule_e");
        runPipelineToExtraction(packageId);
        return packageId;
    }

    @Test
    void the_fields_endpoint_returns_one_entry_per_occurrence_keyed_and_ordered() throws Exception {
        UUID documentId = onlyDocumentOf(scheduleEPackage());

        String body =
                mockMvc.perform(get("/v1/documents/{id}/fields", documentId))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.documentTypeCode").value("SCHEDULE_E"))
                        .andExpect(jsonPath("$.schemaVersion").value("1.0.1"))
                        .andExpect(jsonPath("$.fields.length()").value(SCHEDULE_E_OCCURRENCES))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        // Ordered by field name THEN group key: the three rentsReceived entries are adjacent and
        // in printed-column order, which is the whole point of a key that is the letter on the
        // form rather than a synthetic ordinal. This assertion is not decoration and it does not
        // pass on its own: with the group-key tiebreak removed and everything else left in place,
        // this fixture returns ["C", "B", "A"] — the undefined order the spec was written to end.
        assertThat(groupKeysOf(fieldsOf(body), "rentsReceived"))
                .as("Part I's three property columns, in the order the form prints them")
                .containsExactly("A", "B", "C");
        // Part II's entity table PRINTS its row letters (A-D in the left margin of both
        // sub-tables), so a LABELED row group keys by the printed letter — design D2's rule
        // ("group_key is the value printed on the form") applied to rows, and the join the
        // form itself provides between the entity band and the money band. One occurrence per
        // DECLARED letter, like a COLUMN group; blank rows persist MISSING per occurrence.
        // Zero-padded counted ordinals remain the contract for UNLABELED row groups only
        // (pinned in RowGroupExtractionTest).
        assertThat(groupKeysOf(fieldsOf(body), "partnershipName"))
                .as("Part II's entity rows, keyed by the form's printed row letter")
                .containsExactly("A", "B", "C", "D");
        // An ungrouped field on the SAME document reports a null key — the migration's central
        // claim, observed on a document that carries both kinds.
        assertThat(groupKeysOf(fieldsOf(body), "taxYear"))
                .as("an ungrouped field reports a key that is present and NULL, never absent")
                .containsExactly((String) null);
        assertThat(fieldsOf(body).get(0).has("groupKey"))
                .as("the property is present-and-null, not omitted — no NON_NULL inclusion here")
                .isTrue();
    }

    @Test
    void the_export_carries_the_group_dimension_so_a_name_keyed_consumer_cannot_lose_two_of_three()
            throws Exception {
        UUID packageId = scheduleEPackage();

        String body =
                mockMvc.perform(get("/v1/packages/{id}/export", packageId))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.documents.length()").value(1))
                        .andExpect(jsonPath("$.documents[0].documentTypeCode").value("SCHEDULE_E"))
                        .andExpect(
                                jsonPath("$.documents[0].fields.length()")
                                        .value(SCHEDULE_E_OCCURRENCES))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        List<JsonNode> fields = exportFieldsOf(body);
        assertThat(groupKeysOf(fields, "rentsReceived"))
                .as("three rents, three keys — a name-keyed consumer can no longer keep one")
                .containsExactly("A", "B", "C");
        // Each occurrence exports its OWN value and its OWN box, so a consumer can point a
        // reviewer at the right column without another round trip.
        assertThat(textsOf(fields, "rentsReceived", "value"))
                .as("the value that belongs to each column, in that column's entry")
                .containsExactly("44,400", "29,700", null);
        assertThat(occurrence(fields, "rentsReceived", "B").get("boundingBox").isNull())
                .as("column B cites its own box")
                .isFalse();
        assertThat(occurrence(fields, "rentsReceived", "C").get("extractionMethod").asText())
                .as("the empty third column is a MISSING occurrence, never an absent one")
                .isEqualTo("NONE");
        assertThat(occurrence(fields, "rentsReceived", "C").get("validationStatus").asText())
                .isEqualTo("MANUAL_REVIEW_REQUIRED");
        // The line 21 loss exports NEGATIVE. This is the assertion that proves the sign survives
        // all the way to a consumer: a rental loss booked as income would inflate qualifying
        // income by twice the loss.
        assertThat(occurrence(fields, "incomeOrLoss", "B").get("normalizedValue").decimalValue())
                .as("income OR (LOSS): the parentheses on the form are the number's whole meaning")
                .isEqualByComparingTo("-18470");
        assertThat(groupKeysOf(fields, "taxYear")).containsExactly((String) null);
    }

    /**
     * Where a NULL key sorts is a DECISION, and this is the test that pins it.
     *
     * <p>A field name is either grouped or it is not, so no fixture produces a name carrying both
     * a keyed and an unkeyed occurrence — which is exactly why the null position would otherwise
     * be decided by whatever Postgres does by default (ASC NULLS LAST) on one path and by
     * whatever {@code Comparator} was reached for on the other, with nothing observing the
     * disagreement. This test manufactures the mixed name deliberately so the rule is total and
     * asserted: <b>an unkeyed occurrence sorts FIRST within its field name</b>, matching the
     * order {@link AbstractExtractionIT#currentOccurrences} reads the table in.
     *
     * <p>It cannot pass by accident, and that was checked rather than assumed: flipping both
     * paths to NULLS LAST — the default a derived {@code OrderBy…GroupKeyAsc} would have
     * inherited — returns {@code [A, B, null]} here, and removing the tiebreak altogether returns
     * {@code [B, null, A]}.
     */
    @Test
    void an_unkeyed_occurrence_sorts_FIRST_within_its_field_name_on_both_read_paths()
            throws Exception {
        UUID packageId = scheduleEPackage();
        UUID documentId = onlyDocumentOf(packageId);

        // The unique index is over (org, document, field_name, coalesce(group_key, '')), so a
        // NULL key coexists with 'A' and 'B' under one name. Column C is the fixture's empty
        // column, and stripping its key is the smallest edit that produces the mixed name.
        assertThat(
                        jdbc.update(
                                "UPDATE extracted_field SET group_key = NULL"
                                        + " WHERE logical_document_id = ? AND is_current"
                                        + " AND field_name = 'rentsReceived' AND group_key = 'C'",
                                documentId))
                .as("exactly one occurrence loses its key")
                .isEqualTo(1);

        String fieldsBody =
                mockMvc.perform(get("/v1/documents/{id}/fields", documentId))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(groupKeysOf(fieldsOf(fieldsBody), "rentsReceived"))
                .as("/v1/documents/{id}/fields puts the unkeyed occurrence first")
                .containsExactly(null, "A", "B");

        String exportBody =
                mockMvc.perform(get("/v1/packages/{id}/export", packageId))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(groupKeysOf(exportFieldsOf(exportBody), "rentsReceived"))
                .as("/v1/packages/{id}/export agrees with it, rather than sorting nulls the other way")
                .containsExactly(null, "A", "B");
    }

    /**
     * The KIND of grouping, on both read paths — design 5b D10.
     *
     * <p>{@code groupKey} alone cannot answer "is this field grouped?". A ROW-grouped field whose
     * table region could not be read at all persists ONE occurrence with a NULL key
     * ({@code DefaultFieldExtractionEngine}), and on the wire that is byte-identical to an
     * ungrouped missing field — no sibling occurrence exists to reveal the groupedness. The
     * fixture carries a live instance: {@code remicExcessInclusion}. Without {@code groupKind} a
     * consumer honoring "never render a null-keyed grouped field as a document-level field" is
     * doing it by hard-coded schema knowledge or by luck.
     *
     * <p>The kind is DERIVED from the schema that produced the row (its {@code schema_id}), not
     * persisted: the row already cites its producing schema, and {@code extraction_schema} rows are
     * immutable, so the derivation is both historically faithful and incapable of drifting from
     * the declaration it reports.
     */
    @Test
    void every_occurrence_names_the_KIND_of_group_it_belongs_to_on_both_read_paths()
            throws Exception {
        UUID packageId = scheduleEPackage();
        UUID documentId = onlyDocumentOf(packageId);

        List<JsonNode> fields =
                fieldsOf(
                        mockMvc.perform(get("/v1/documents/{id}/fields", documentId))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsString());

        assertThat(fields.get(0).has("groupKind"))
                .as("present-and-never-omitted, exactly like groupKey")
                .isTrue();
        assertThat(textsOf(fields, "rentsReceived", "groupKind"))
                .as("Part I's printed property columns are a COLUMN group")
                .containsExactly("COLUMN", "COLUMN", "COLUMN");
        assertThat(textsOf(fields, "partnershipName", "groupKind"))
                .as("Part II's lettered entity rows are a ROW group")
                .containsExactly("ROW", "ROW", "ROW", "ROW");
        assertThat(textsOf(fields, "taxYear", "groupKind"))
                .as("an ungrouped field says NONE — a value, never a null to be guessed at")
                .containsExactly("NONE");
        // T9's warning, now answerable from the wire alone.
        assertThat(textsOf(fields, "remicExcessInclusion", "groupKey"))
                .as("the region was never located, so the one occurrence carries no row key")
                .containsExactly((String) null);
        assertThat(textsOf(fields, "remicExcessInclusion", "groupKind"))
                .as("...and says ROW anyway: it is a statement about the TABLE, not a plain field")
                .containsExactly("ROW");

        List<JsonNode> exported =
                exportFieldsOf(
                        mockMvc.perform(get("/v1/packages/{id}/export", packageId))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsString());
        assertThat(exported.get(0).has("groupKind"))
                .as("the export carries the same dimension — the two read paths cannot disagree")
                .isTrue();
        assertThat(textsOf(exported, "rentsReceived", "groupKind"))
                .containsExactly("COLUMN", "COLUMN", "COLUMN");
        assertThat(textsOf(exported, "remicExcessInclusion", "groupKind")).containsExactly("ROW");
        assertThat(textsOf(exported, "taxYear", "groupKind")).containsExactly("NONE");
    }

    /**
     * The decision strip is the THIRD read surface a grouped field reaches, and it had the same
     * gap: {@code HistoryEntry} carried {@code fieldName} with no key, so three corrections to
     * three rental properties rendered as three "rentsReceived" rows distinguishable only by the
     * subject id a reviewer never sees. Not a correctness bug — the subject id is right and the
     * audit trail is complete — but the strip is read by a human, and a label that cannot name
     * which property was corrected is a label that cannot be checked.
     */
    @Test
    void the_decision_strip_names_the_OCCURRENCE_that_was_corrected() throws Exception {
        UUID documentId = onlyDocumentOf(scheduleEPackage());
        UUID columnB = (UUID) currentOccurrences(documentId).get("rentsReceived#B").get("id");

        mockMvc.perform(
                        patch("/v1/fields/{id}", columnB)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"action\":\"CORRECT\",\"value\":\"29,750\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/v1/documents/{id}/history", documentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].action").value("CORRECT"))
                .andExpect(jsonPath("$.entries[0].fieldName").value("rentsReceived"))
                .andExpect(jsonPath("$.entries[0].groupKey").value("B"));
    }

    // ── reading the two payloads ────────────────────────────────────────────

    private static List<JsonNode> fieldsOf(String body) throws Exception {
        List<JsonNode> fields = new ArrayList<>();
        JSON.readTree(body).get("fields").forEach(fields::add);
        return fields;
    }

    private static List<JsonNode> exportFieldsOf(String body) throws Exception {
        List<JsonNode> fields = new ArrayList<>();
        JSON.readTree(body).get("documents").get(0).get("fields").forEach(fields::add);
        return fields;
    }

    /** The keys of one field name, IN RESPONSE ORDER — a sorted() here would erase the claim. */
    private static List<String> groupKeysOf(List<JsonNode> fields, String fieldName) {
        return textsOf(fields, fieldName, "groupKey");
    }

    private static List<String> textsOf(List<JsonNode> fields, String fieldName, String property) {
        List<String> values = new ArrayList<>();
        for (JsonNode field : fields) {
            if (fieldName.equals(field.get("fieldName").asText())) {
                JsonNode value = field.get(property);
                values.add(value == null || value.isNull() ? null : value.asText());
            }
        }
        return values;
    }

    private static JsonNode occurrence(List<JsonNode> fields, String fieldName, String groupKey) {
        return fields.stream()
                .filter(field -> fieldName.equals(field.get("fieldName").asText()))
                .filter(field -> groupKey.equals(field.path("groupKey").asText(null)))
                .findFirst()
                .orElseThrow(
                        () ->
                                new AssertionError(
                                        "no occurrence " + fieldName + "#" + groupKey));
    }
}
