package com.pragmaticds.docengine.extraction.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pragmaticds.docengine.classification.rules.AnchorKind;
import com.pragmaticds.docengine.extraction.domain.ExtractionSchema;
import com.pragmaticds.docengine.extraction.repo.ExtractionSchemaRepository;
import com.pragmaticds.docengine.platform.error.DomainException;
import com.pragmaticds.docengine.platform.error.ErrorCode;
import com.pragmaticds.docengine.platform.tenancy.TenantContext;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Schema loading and the shadowing rule: for each document_type_code, an org-scoped active schema
 * hides EVERY global schema of that type wholesale — never a merge; among the surviving scope's
 * schemas the highest version wins (numeric segment-wise, 1.10.0 beats 1.2.0). Unlike
 * classification, a type with NO schema at all is NOT an error — extraction simply skips those
 * documents (BANK_STATEMENT, W2, UNKNOWN ship without schemas).
 */
@ExtendWith(MockitoExtension.class)
class ExtractionSchemaLoaderTest {

    private static final UUID ORG = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Mock private ExtractionSchemaRepository schemas;

    private ExtractionSchemaLoader loader;

    @BeforeEach
    void setUp() {
        TenantContext.set(ORG);
        loader = new ExtractionSchemaLoader(schemas);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static ExtractionSchema schema(
            UUID orgId, String type, String version, String definition) {
        return new ExtractionSchema(orgId, type, version, definition, true);
    }

    /** Parses a definition directly through the authoring door, raw exceptions un-wrapped. */
    private SchemaDefinition load(String definition) {
        return loader.parseAuthored("BANK_STATEMENT", "1.0.0", definition);
    }

    /** A minimal valid definition whose single field name marks which row won. */
    private static String minimal(String fieldName) {
        return """
               {"fields": [{"name": "%s", "dataType": "STRING", "required": true,
                 "normalizer": null, "sensitive": false,
                 "extractors": [{"method": "REGEX", "strength": 0.5,
                   "value": {"pattern": "x", "occurrence": 0, "scope": "PAGE"}}]}]}
               """
                .formatted(fieldName);
    }

    private static String columnDefinition(String valueTail) {
        return """
               {"fields": [{"name": "incomeOrLoss", "dataType": "MONEY", "required": true,
                 "normalizer": "money", "sensitive": false,
                 "group": {"kind": "COLUMN",
                   "header": {"kind": "literal", "pattern": "Properties:"},
                   "keys": ["A", "B", "C"]},
                 "extractors": [{"method": "ANCHOR_LABEL", "strength": 0.9,
                   "label": {"kind": "literal", "pattern": "Subtract line 20 from line 3"},
                   "value": {"pattern": "x", "occurrence": 0, "scope": "LINE"%s}}]}]}
               """.formatted(valueTail);
    }

    @Test
    void parses_the_v7_paystub_seed_verbatim() {
        when(schemas.findActiveVisibleTo(ORG))
                .thenReturn(List.of(schema(null, "PAYSTUB", "1.0.0", V7PaystubSeed.DEFINITION)));

        SchemaDefinition parsed = loader.activeSchemaFor("PAYSTUB").orElseThrow();

        assertThat(parsed.documentTypeCode()).isEqualTo("PAYSTUB");
        assertThat(parsed.version()).isEqualTo("1.0.0");
        assertThat(parsed.fields())
                .extracting(FieldSpec::name)
                .containsExactly(
                        "borrowerName",
                        "employerName",
                        "payPeriodStart",
                        "payPeriodEnd",
                        "payDate",
                        "payFrequency",
                        "currentGrossPay",
                        "ytdGrossPay",
                        "netPay",
                        "federalWithholding");

        FieldSpec borrower = parsed.fields().get(0);
        assertThat(borrower.dataType()).isEqualTo(DataType.STRING);
        assertThat(borrower.required()).isTrue();
        assertThat(borrower.normalizer()).isEqualTo("personName");
        assertThat(borrower.sensitive()).isFalse();
        assertThat(borrower.extractors()).hasSize(1);
        ExtractorSpec borrowerRung = borrower.extractors().get(0);
        assertThat(borrowerRung.method()).isEqualTo(ExtractionMethod.ANCHOR_LABEL);
        assertThat(borrowerRung.strength()).isEqualTo(0.9);
        assertThat(borrowerRung.label()).isEqualTo(new LabelSpec(AnchorKind.LITERAL, "Employee:"));
        assertThat(borrowerRung.table()).isNull();
        assertThat(borrowerRung.value().occurrence()).isZero();
        assertThat(borrowerRung.value().scope()).isEqualTo(ValueScope.LINE_RIGHT);
        // The pattern text itself is not re-asserted here: it is read from the migration, so a
        // literal copy would only ever restate the input. What the pattern DOES is asserted
        // behaviourally in DefaultFieldExtractionEngineTest and PaystubFixtureEndToEndTest.
        assertThat(borrowerRung.value().pattern()).isNotBlank();

        FieldSpec employer = parsed.fields().get(1);
        assertThat(employer.normalizer()).isNull();
        // A labelled rung first (paystubs that say "Employer:"), then progressively weaker
        // unlabelled regexes — an unanchored page-wide name match is a genuinely weak signal.
        assertThat(employer.extractors().get(0).method()).isEqualTo(ExtractionMethod.ANCHOR_LABEL);
        assertThat(employer.extractors().get(0).label())
                .isEqualTo(new LabelSpec(AnchorKind.LITERAL, "Employer:"));
        assertThat(employer.extractors())
                .filteredOn(rung -> rung.method() == ExtractionMethod.REGEX)
                .allSatisfy(
                        rung -> {
                            assertThat(rung.label()).isNull();
                            assertThat(rung.value().scope()).isEqualTo(ValueScope.PAGE);
                        });
        assertThat(employer.extractors())
                .extracting(ExtractorSpec::strength)
                .as("ladder rungs weaken as they go: the first rung that fires is the strongest")
                .isSortedAccordingTo(Comparator.<Double>reverseOrder())
                .isNotEmpty();

        // The date pair reads the SAME "Pay Period" line — start is occurrence 0, end is 1.
        assertThat(parsed.fields().get(2).extractors().get(0).value().occurrence()).isZero();
        assertThat(parsed.fields().get(3).extractors().get(0).value().occurrence()).isEqualTo(1);
        assertThat(parsed.fields().get(2).extractors().get(0).value().pattern())
                .isEqualTo("\\d{2}/\\d{2}/\\d{4}");

        // The two gross fields ship a fallback ladder: TABLE_CLUSTER first, in spec order.
        FieldSpec currentGross = parsed.fields().get(6);
        assertThat(currentGross.extractors())
                .extracting(ExtractorSpec::method, ExtractorSpec::strength)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(ExtractionMethod.TABLE_CLUSTER, 1.0),
                        org.assertj.core.groups.Tuple.tuple(ExtractionMethod.ANCHOR_LABEL, 0.8),
                        org.assertj.core.groups.Tuple.tuple(ExtractionMethod.ANCHOR_LABEL, 0.7));
        assertThat(currentGross.extractors().get(0).table())
                .isEqualTo(
                        new TableSpec(
                                new LabelSpec(AnchorKind.LITERAL, "Gross"),
                                new LabelSpec(AnchorKind.LITERAL, "Current")));
        assertThat(currentGross.extractors().get(0).value().scope()).isEqualTo(ValueScope.LINE);
        assertThat(currentGross.extractors().get(1).label())
                .isEqualTo(new LabelSpec(AnchorKind.LITERAL, "Gross Pay"));

        FieldSpec ytdGross = parsed.fields().get(7);
        assertThat(ytdGross.extractors().get(0).method()).isEqualTo(ExtractionMethod.TABLE_CLUSTER);
        assertThat(ytdGross.extractors().get(0).table().columnHeader().pattern()).isEqualTo("YTD");
        assertThat(ytdGross.extractors().get(1).value().occurrence()).isEqualTo(1);
        assertThat(ytdGross.extractors().get(2).value().occurrence()).isEqualTo(1);
    }

    @Test
    void an_org_schema_shadows_every_global_schema_of_the_same_type_wholesale() {
        when(schemas.findActiveVisibleTo(ORG))
                .thenReturn(
                        List.of(
                                schema(null, "PAYSTUB", "2.0.0", minimal("fromGlobal")),
                                schema(ORG, "PAYSTUB", "1.0.0", minimal("fromOrg")),
                                schema(null, "PDS_CUSTOM", "1.0.0", minimal("otherType"))));

        // The org schema wins the whole type even against a HIGHER-versioned global.
        SchemaDefinition paystub = loader.activeSchemaFor("PAYSTUB").orElseThrow();
        assertThat(paystub.version()).isEqualTo("1.0.0");
        assertThat(paystub.fields()).extracting(FieldSpec::name).containsExactly("fromOrg");

        // A type the org never shadowed still resolves to its global schema.
        assertThat(loader.activeSchemaFor("PDS_CUSTOM")).isPresent();
    }

    @Test
    void the_highest_version_wins_within_one_scope() {
        when(schemas.findActiveVisibleTo(ORG))
                .thenReturn(
                        List.of(
                                schema(null, "PAYSTUB", "1.2.0", minimal("fromOld")),
                                schema(null, "PAYSTUB", "1.10.0", minimal("fromNew"))));

        // numeric segment compare: 1.10.0 > 1.2.0 (string compare would invert it)
        SchemaDefinition winner = loader.activeSchemaFor("PAYSTUB").orElseThrow();
        assertThat(winner.version()).isEqualTo("1.10.0");
        assertThat(winner.fields()).extracting(FieldSpec::name).containsExactly("fromNew");
    }

    @Test
    void a_type_with_no_schema_is_empty_not_an_error() {
        when(schemas.findActiveVisibleTo(ORG))
                .thenReturn(List.of(schema(null, "PAYSTUB", "1.0.0", minimal("paystub"))));

        // BANK_STATEMENT, W2, UNKNOWN ship no schema — extraction SKIPS those documents.
        assertThat(loader.activeSchemaFor("BANK_STATEMENT")).isEmpty();
    }

    @Test
    void no_schemas_at_all_is_still_not_an_error() {
        when(schemas.findActiveVisibleTo(ORG)).thenReturn(List.of());

        assertThat(loader.activeSchemaFor("PAYSTUB")).isEmpty();
    }

    @Test
    void schemas_cache_per_org_until_invalidated() {
        when(schemas.findActiveVisibleTo(ORG))
                .thenReturn(List.of(schema(null, "PAYSTUB", "1.0.0", minimal("paystub"))));

        loader.activeSchemaFor("PAYSTUB");
        loader.activeSchemaFor("PAYSTUB");
        verify(schemas, times(1)).findActiveVisibleTo(any());

        loader.invalidate(ORG);
        loader.activeSchemaFor("PAYSTUB");
        verify(schemas, times(2)).findActiveVisibleTo(any());

        loader.invalidateAll();
        loader.activeSchemaFor("PAYSTUB");
        verify(schemas, times(3)).findActiveVisibleTo(any());
    }

    @Test
    void an_unparseable_definition_is_INTERNAL_and_names_only_the_schema_id() {
        String broken = "{\"fields\": \"SENSITIVE-DEFINITION-MARKER\"";
        when(schemas.findActiveVisibleTo(ORG))
                .thenReturn(List.of(schema(null, "PAYSTUB", "1.0.0", broken)));

        assertThatThrownBy(() -> loader.activeSchemaFor("PAYSTUB"))
                .isInstanceOf(DomainException.class)
                .satisfies(
                        e -> {
                            DomainException domain = (DomainException) e;
                            assertThat(domain.code()).isEqualTo(ErrorCode.INTERNAL);
                            // The schema id is the only parameter — never definition content.
                            assertThat(domain.params().keySet()).containsExactly("schemaId");
                            assertThat(String.valueOf(domain.getMessage()))
                                    .doesNotContain("SENSITIVE-DEFINITION-MARKER");
                            assertThat(String.valueOf(domain.params()))
                                    .doesNotContain("SENSITIVE-DEFINITION-MARKER");
                        });
    }

    @Test
    void a_schema_with_no_fields_is_unparseable() {
        assertInternal("{\"fields\": []}");
    }

    @Test
    void a_field_with_no_extractors_is_unparseable() {
        assertInternal(
                """
                {"fields": [{"name": "f", "dataType": "STRING", "required": true,
                  "normalizer": null, "sensitive": false, "extractors": []}]}
                """);
    }

    @Test
    void an_anchor_label_extractor_without_a_label_is_unparseable() {
        assertInternal(
                """
                {"fields": [{"name": "f", "dataType": "STRING", "required": true,
                  "normalizer": null, "sensitive": false,
                  "extractors": [{"method": "ANCHOR_LABEL", "strength": 0.9,
                    "value": {"pattern": "x", "occurrence": 0, "scope": "LINE_RIGHT"}}]}]}
                """);
    }

    @Test
    void a_table_cluster_extractor_without_a_table_is_unparseable() {
        assertInternal(
                """
                {"fields": [{"name": "f", "dataType": "MONEY", "required": true,
                  "normalizer": "money", "sensitive": false,
                  "extractors": [{"method": "TABLE_CLUSTER", "strength": 1.0,
                    "value": {"pattern": "x", "occurrence": 0, "scope": "LINE"}}]}]}
                """);
    }

    @Test
    void an_extractor_without_a_value_is_unparseable() {
        assertInternal(
                """
                {"fields": [{"name": "f", "dataType": "STRING", "required": true,
                  "normalizer": null, "sensitive": false,
                  "extractors": [{"method": "ANCHOR_LABEL", "strength": 0.9,
                    "label": {"kind": "literal", "pattern": "Employee:"}}]}]}
                """);
    }

    // ── Spec 3: the two detector-rung shapes ─────────────────────────────────

    @Test
    void a_checkbox_state_extractor_parses_options_and_proximity_without_a_value_block() {
        when(schemas.findActiveVisibleTo(ORG))
                .thenReturn(
                        List.of(
                                schema(
                                        null,
                                        "TAX_RETURN",
                                        "1.0.0",
                                        """
                                        {"fields": [{"name": "filingStatus", "dataType": "ENUM",
                                          "required": true, "normalizer": null, "sensitive": false,
                                          "extractors": [{"method": "CHECKBOX_STATE",
                                            "strength": 0.9, "proximityPt": 18.0,
                                            "options": [
                                              {"label": {"kind": "literal", "pattern": "Single"},
                                               "value": "SINGLE"},
                                              {"label": {"kind": "literal",
                                                         "pattern": "Married filing jointly"},
                                               "value": "MARRIED_FILING_JOINTLY"}
                                            ]}]}]}
                                        """)));

        SchemaDefinition parsed = loader.activeSchemaFor("TAX_RETURN").orElseThrow();

        ExtractorSpec rung = parsed.fields().get(0).extractors().get(0);
        assertThat(rung.method()).isEqualTo(ExtractionMethod.CHECKBOX_STATE);
        assertThat(rung.strength()).isEqualTo(0.9);
        // The detector rungs carry NO value block: the value comes from a detection.
        assertThat(rung.value()).isNull();
        assertThat(rung.label()).isNull();
        assertThat(rung.table()).isNull();
        assertThat(rung.region()).isNull();
        assertThat(rung.proximityPt()).isEqualTo(18.0);
        assertThat(rung.options())
                .containsExactly(
                        new CheckboxOption(new LabelSpec(AnchorKind.LITERAL, "Single"), "SINGLE"),
                        new CheckboxOption(
                                new LabelSpec(AnchorKind.LITERAL, "Married filing jointly"),
                                "MARRIED_FILING_JOINTLY"));
    }

    @Test
    void a_signature_presence_extractor_parses_the_region_without_a_value_block() {
        when(schemas.findActiveVisibleTo(ORG))
                .thenReturn(
                        List.of(
                                schema(
                                        null,
                                        "PURCHASE_CONTRACT",
                                        "1.0.0",
                                        """
                                        {"fields": [{"name": "buyerSigned", "dataType": "ENUM",
                                          "required": true, "normalizer": null, "sensitive": false,
                                          "extractors": [{"method": "SIGNATURE_PRESENCE",
                                            "strength": 0.9,
                                            "region": {
                                              "label": {"kind": "literal",
                                                        "pattern": "Buyer's Signature"},
                                              "windowPt": {"left": 0.0, "right": 240.0,
                                                           "above": 40.0, "below": 8.0}}}]}]}
                                        """)));

        SchemaDefinition parsed = loader.activeSchemaFor("PURCHASE_CONTRACT").orElseThrow();

        ExtractorSpec rung = parsed.fields().get(0).extractors().get(0);
        assertThat(rung.method()).isEqualTo(ExtractionMethod.SIGNATURE_PRESENCE);
        assertThat(rung.value()).isNull();
        assertThat(rung.label()).isNull();
        assertThat(rung.table()).isNull();
        assertThat(rung.options()).isNull();
        assertThat(rung.proximityPt()).isNull();
        assertThat(rung.region())
                .isEqualTo(
                        new RegionSpec(
                                new LabelSpec(AnchorKind.LITERAL, "Buyer's Signature"),
                                new Window(0.0, 240.0, 40.0, 8.0)));
    }

    @Test
    void existing_method_rungs_carry_null_detector_fields() {
        when(schemas.findActiveVisibleTo(ORG))
                .thenReturn(List.of(schema(null, "PAYSTUB", "1.0.0", minimal("plain"))));

        ExtractorSpec rung =
                loader.activeSchemaFor("PAYSTUB").orElseThrow().fields().get(0).extractors().get(0);

        assertThat(rung.options()).isNull();
        assertThat(rung.proximityPt()).isNull();
        assertThat(rung.region()).isNull();
        // Spec 4: the cell bounds belong to LABEL_BELOW alone.
        assertThat(rung.maxDropPt()).isNull();
        assertThat(rung.cellOverlap()).isNull();
        // Spec 5a: the column header belongs to ROW_CELL alone.
        assertThat(rung.columnHeader()).isNull();
        // V40: the tile's reach belongs to LABEL_ABOVE alone.
        assertThat(rung.maxRisePt()).isNull();
    }

    @Test
    void a_checkbox_state_extractor_without_options_is_unparseable() {
        assertInternal(
                """
                {"fields": [{"name": "filingStatus", "dataType": "ENUM", "required": true,
                  "normalizer": null, "sensitive": false,
                  "extractors": [{"method": "CHECKBOX_STATE", "strength": 0.9,
                    "proximityPt": 18.0}]}]}
                """);
    }

    @Test
    void a_checkbox_state_extractor_with_empty_options_is_unparseable() {
        assertInternal(
                """
                {"fields": [{"name": "filingStatus", "dataType": "ENUM", "required": true,
                  "normalizer": null, "sensitive": false,
                  "extractors": [{"method": "CHECKBOX_STATE", "strength": 0.9,
                    "proximityPt": 18.0, "options": []}]}]}
                """);
    }

    @Test
    void a_checkbox_state_extractor_without_a_proximity_cap_is_unparseable() {
        assertInternal(
                """
                {"fields": [{"name": "filingStatus", "dataType": "ENUM", "required": true,
                  "normalizer": null, "sensitive": false,
                  "extractors": [{"method": "CHECKBOX_STATE", "strength": 0.9,
                    "options": [{"label": {"kind": "literal", "pattern": "Single"},
                                 "value": "SINGLE"}]}]}]}
                """);
    }

    @Test
    void a_signature_presence_extractor_without_a_region_is_unparseable() {
        assertInternal(
                """
                {"fields": [{"name": "buyerSigned", "dataType": "ENUM", "required": true,
                  "normalizer": null, "sensitive": false,
                  "extractors": [{"method": "SIGNATURE_PRESENCE", "strength": 0.9}]}]}
                """);
    }

    @Test
    void a_region_without_a_window_is_unparseable() {
        assertInternal(
                """
                {"fields": [{"name": "buyerSigned", "dataType": "ENUM", "required": true,
                  "normalizer": null, "sensitive": false,
                  "extractors": [{"method": "SIGNATURE_PRESENCE", "strength": 0.9,
                    "region": {"label": {"kind": "literal", "pattern": "Buyer's Signature"}}}]}]}
                """);
    }

    // ── Spec 4: the LABEL_BELOW rung's shape ─────────────────────────────────

    @Test
    void a_label_below_extractor_parses_its_label_value_and_cell_bounds() {
        when(schemas.findActiveVisibleTo(ORG))
                .thenReturn(
                        List.of(
                                schema(
                                        null,
                                        "W2",
                                        "1.1.0",
                                        """
                                        {"fields": [{"name": "wagesTipsOtherComp",
                                          "dataType": "MONEY", "required": true,
                                          "normalizer": "money", "sensitive": false,
                                          "extractors": [{"method": "LABEL_BELOW",
                                            "strength": 0.9,
                                            "label": {"kind": "literal",
                                                      "pattern": "Wages, tips, other compensation"},
                                            "maxDropPt": 18.0,
                                            "cellOverlap": 0.75,
                                            "value": {"pattern": "\\\\d+\\\\.\\\\d{2}",
                                                      "occurrence": 0}}]}]}
                                        """)));

        SchemaDefinition parsed = loader.activeSchemaFor("W2").orElseThrow();

        ExtractorSpec rung = parsed.fields().get(0).extractors().get(0);
        assertThat(rung.method()).isEqualTo(ExtractionMethod.LABEL_BELOW);
        assertThat(rung.strength()).isEqualTo(0.9);
        assertThat(rung.label())
                .isEqualTo(
                        new LabelSpec(AnchorKind.LITERAL, "Wages, tips, other compensation"));
        assertThat(rung.value().pattern()).isEqualTo("\\d+\\.\\d{2}");
        assertThat(rung.value().occurrence()).isZero();
        assertThat(rung.maxDropPt()).isEqualTo(18.0);
        assertThat(rung.cellOverlap()).isEqualTo(0.75);
        // The drop is LABEL_BELOW's; the rise is LABEL_ABOVE's (V40) and never travels here.
        assertThat(rung.maxRisePt()).isNull();
        // LABEL_BELOW is not a table rung and not a detector rung.
        assertThat(rung.table()).isNull();
        assertThat(rung.options()).isNull();
        assertThat(rung.proximityPt()).isNull();
        assertThat(rung.region()).isNull();
    }

    @Test
    void a_label_below_extractor_parses_the_adjacent_cells_it_joins() {
        when(schemas.findActiveVisibleTo(ORG))
                .thenReturn(
                        List.of(
                                schema(
                                        null,
                                        "W2",
                                        "1.3.0",
                                        """
                                        {"fields": [{"name": "employeeName",
                                          "dataType": "STRING", "required": true,
                                          "normalizer": "personName", "sensitive": false,
                                          "extractors": [{"method": "LABEL_BELOW",
                                            "strength": 0.9,
                                            "label": {"kind": "literal",
                                                      "pattern": "e Employee's first name and initial"},
                                            "joinCells": [{"kind": "literal", "pattern": "Last name"},
                                                          {"kind": "literal", "pattern": "Suff."}],
                                            "value": {"pattern": "[A-Z][a-z]+ [A-Z][a-z]+",
                                                      "occurrence": 0}}]}]}
                                        """)));

        ExtractorSpec rung =
                loader.activeSchemaFor("W2").orElseThrow().fields().get(0).extractors().get(0);

        assertThat(rung.joinCells())
                .containsExactly(
                        new LabelSpec(AnchorKind.LITERAL, "Last name"),
                        new LabelSpec(AnchorKind.LITERAL, "Suff."));
        assertThat(rung.maxDropPt()).isEqualTo(24.0);
        assertThat(rung.cellOverlap()).isEqualTo(0.5);
    }

    @Test
    void a_label_below_extractor_without_join_cells_joins_nothing() {
        when(schemas.findActiveVisibleTo(ORG))
                .thenReturn(
                        List.of(
                                schema(
                                        null,
                                        "W2",
                                        "1.1.0",
                                        """
                                        {"fields": [{"name": "employerEin", "dataType": "STRING",
                                          "required": true, "normalizer": null,
                                          "sensitive": false,
                                          "extractors": [{"method": "LABEL_BELOW",
                                            "strength": 0.9,
                                            "label": {"kind": "literal",
                                                      "pattern": "Employer identification number"},
                                            "value": {"pattern": "\\\\d{2}-\\\\d{7}",
                                                      "occurrence": 0}}]}]}
                                        """)));

        ExtractorSpec rung =
                loader.activeSchemaFor("W2").orElseThrow().fields().get(0).extractors().get(0);

        assertThat(rung.joinCells()).isNull();
    }

    @Test
    void a_join_cell_without_a_label_is_unparseable() {
        assertInternal(
                """
                {"fields": [{"name": "employeeName", "dataType": "STRING", "required": true,
                  "normalizer": "personName", "sensitive": false,
                  "extractors": [{"method": "LABEL_BELOW", "strength": 0.9,
                    "label": {"kind": "literal", "pattern": "Employee's first name and initial"},
                    "joinCells": [{"kind": "literal"}],
                    "value": {"pattern": "[A-Z][a-z]+ [A-Z][a-z]+", "occurrence": 0}}]}]}
                """);
    }

    @Test
    void join_cells_belong_to_the_box_grid_rung_alone() {
        // A join on ANCHOR_LABEL has no cell to join and would silently do nothing — an
        // authoring error, refused like every other misplaced parameter.
        assertInternal(
                """
                {"fields": [{"name": "employeeName", "dataType": "STRING", "required": true,
                  "normalizer": "personName", "sensitive": false,
                  "extractors": [{"method": "ANCHOR_LABEL", "strength": 0.9,
                    "label": {"kind": "literal", "pattern": "Employee:"},
                    "joinCells": [{"kind": "literal", "pattern": "Last name"}],
                    "value": {"pattern": "[A-Z][a-z]+ [A-Z][a-z]+", "occurrence": 0,
                              "scope": "LINE_RIGHT"}}]}]}
                """);
    }

    @Test
    void a_label_below_extractor_without_cell_bounds_takes_the_documented_defaults() {
        // The CONTRACT's defaults: maxDropPt 24.0, cellOverlap 0.5. 24 pt is the number that
        // separates a real W-2's own value row (about 6 pt below its caption's bottom edge)
        // from the NEXT row's value (about 30 pt below it) — the decoy the rung must not take.
        when(schemas.findActiveVisibleTo(ORG))
                .thenReturn(
                        List.of(
                                schema(
                                        null,
                                        "W2",
                                        "1.1.0",
                                        """
                                        {"fields": [{"name": "employerEin", "dataType": "STRING",
                                          "required": true, "normalizer": null,
                                          "sensitive": false,
                                          "extractors": [{"method": "LABEL_BELOW",
                                            "strength": 0.9,
                                            "label": {"kind": "literal",
                                                      "pattern": "Employer identification number"},
                                            "value": {"pattern": "\\\\d{2}-\\\\d{7}",
                                                      "occurrence": 0}}]}]}
                                        """)));

        ExtractorSpec rung =
                loader.activeSchemaFor("W2").orElseThrow().fields().get(0).extractors().get(0);

        assertThat(rung.maxDropPt()).isEqualTo(24.0);
        assertThat(rung.cellOverlap()).isEqualTo(0.5);
        // The CONTRACT's value block carries no "scope": LABEL_BELOW's scope IS the cell, so
        // the key is optional here and the parsed value is never consulted by the rung.
        assertThat(rung.value().scope()).isEqualTo(ValueScope.LINE);
    }

    @Test
    void a_label_below_extractor_without_a_label_is_unparseable() {
        assertInternal(
                """
                {"fields": [{"name": "wagesTipsOtherComp", "dataType": "MONEY", "required": true,
                  "normalizer": "money", "sensitive": false,
                  "extractors": [{"method": "LABEL_BELOW", "strength": 0.9,
                    "value": {"pattern": "x", "occurrence": 0}}]}]}
                """);
    }

    @Test
    void a_label_below_extractor_without_a_value_is_unparseable() {
        assertInternal(
                """
                {"fields": [{"name": "wagesTipsOtherComp", "dataType": "MONEY", "required": true,
                  "normalizer": "money", "sensitive": false,
                  "extractors": [{"method": "LABEL_BELOW", "strength": 0.9,
                    "label": {"kind": "literal", "pattern": "Wages, tips, other compensation"},
                    "maxDropPt": 24.0}]}]}
                """);
    }

    @Test
    void an_anchor_label_extractor_still_requires_its_scope() {
        // The other half of the scope decision: scope is optional for LABEL_BELOW ONLY. For a
        // method that actually searches a scope, an absent one is an authoring error, not a
        // default — silently defaulting it would pick a search direction nobody chose.
        assertInternal(
                """
                {"fields": [{"name": "f", "dataType": "STRING", "required": true,
                  "normalizer": null, "sensitive": false,
                  "extractors": [{"method": "ANCHOR_LABEL", "strength": 0.9,
                    "label": {"kind": "literal", "pattern": "Employee:"},
                    "value": {"pattern": "x", "occurrence": 0}}]}]}
                """);
    }

    // ── Spec 5a: the ROW_CELL rung's shape ───────────────────────────────────

    @Test
    void a_row_cell_extractor_parses_its_column_header_and_needs_no_scope() {
        when(schemas.findActiveVisibleTo(ORG))
                .thenReturn(
                        List.of(
                                schema(
                                        null,
                                        "SCHEDULE_E",
                                        "1.0.0",
                                        """
                                        {"fields": [{"name": "passthroughIncome",
                                          "dataType": "MONEY", "required": false,
                                          "normalizer": "money", "sensitive": false,
                                          "extractors": [{"method": "ROW_CELL",
                                            "strength": 0.9,
                                            "columnHeader": {"kind": "literal",
                                                             "pattern": "Nonpassive income"},
                                            "value": {"pattern": "\\\\d+\\\\.\\\\d{2}"}}]}]}
                                        """)));

        SchemaDefinition parsed = loader.activeSchemaFor("SCHEDULE_E").orElseThrow();

        ExtractorSpec rung = parsed.fields().get(0).extractors().get(0);
        assertThat(rung.method()).isEqualTo(ExtractionMethod.ROW_CELL);
        assertThat(rung.strength()).isEqualTo(0.9);
        assertThat(rung.columnHeader())
                .isEqualTo(new LabelSpec(AnchorKind.LITERAL, "Nonpassive income"));
        assertThat(rung.value().pattern()).isEqualTo("\\d+\\.\\d{2}");
        assertThat(rung.value().occurrence()).isZero();
        // ROW_CELL's scope IS the cell — derived from the column header and the row band — so
        // the value block carries no "scope" and the parsed one is never consulted.
        assertThat(rung.value().scope()).isEqualTo(ValueScope.LINE);
        // The default cell overlap is the same 0.5 LABEL_BELOW uses: both rungs mean the same
        // thing by "the same column", so they must not drift apart.
        assertThat(rung.cellOverlap()).isEqualTo(0.5);
        // ROW_CELL is neither a label rung, a table rung, nor a detector rung.
        assertThat(rung.label()).isNull();
        assertThat(rung.table()).isNull();
        assertThat(rung.options()).isNull();
        assertThat(rung.proximityPt()).isNull();
        assertThat(rung.region()).isNull();
        assertThat(rung.maxDropPt()).isNull();
    }

    @Test
    void a_row_cell_extractor_without_a_column_header_is_unparseable() {
        // Without a column header there is no cell to read: the rung would take the row's
        // FIRST money match, which on Schedule E Part II is the nonpassive LOSS printed to the
        // left of the income. A loss reported as income is a sign error in a qualifying-income
        // figure, so this must be an authoring error rather than a default.
        assertInternal(
                """
                {"fields": [{"name": "passthroughIncome", "dataType": "MONEY",
                  "required": false, "normalizer": "money", "sensitive": false,
                  "extractors": [{"method": "ROW_CELL", "strength": 0.9,
                    "value": {"pattern": "x"}}]}]}
                """);
    }

    @Test
    void a_row_cell_extractor_without_a_value_is_unparseable() {
        assertInternal(
                """
                {"fields": [{"name": "passthroughIncome", "dataType": "MONEY",
                  "required": false, "normalizer": "money", "sensitive": false,
                  "extractors": [{"method": "ROW_CELL", "strength": 0.9,
                    "columnHeader": {"kind": "literal", "pattern": "Nonpassive income"}}]}]}
                """);
    }

    // ── Spec 5a: the repeating-group block ───────────────────────────────────

    @Test
    void line_offset_defaults_to_zero_and_accepts_explicit_zero() {
        when(schemas.findActiveVisibleTo(ORG))
                .thenReturn(
                        List.of(
                                schema(null, "SCHEDULE_E", "1.0.0", columnDefinition("")),
                                schema(
                                        null,
                                        "EXPLICIT_ZERO",
                                        "1.0.0",
                                        columnDefinition(", \"lineOffset\": 0"))));

        int absentOffset =
                loader.activeSchemaFor("SCHEDULE_E")
                        .orElseThrow()
                        .fields()
                        .get(0)
                        .extractors()
                        .get(0)
                        .value()
                        .lineOffset();
        int explicitZeroOffset =
                loader.activeSchemaFor("EXPLICIT_ZERO")
                        .orElseThrow()
                        .fields()
                        .get(0)
                        .extractors()
                        .get(0)
                        .value()
                        .lineOffset();

        assertThat(absentOffset).isZero();
        assertThat(explicitZeroOffset).isZero();
    }

    @Test
    void valid_relative_line_offset_loads_for_column_anchor_line() {
        when(schemas.findActiveVisibleTo(ORG))
                .thenReturn(
                        List.of(
                                schema(
                                        null,
                                        "SCHEDULE_E",
                                        "1.0.0",
                                        columnDefinition(", \"lineOffset\": 2"))));

        int lineOffset =
                loader.activeSchemaFor("SCHEDULE_E")
                        .orElseThrow()
                        .fields()
                        .get(0)
                        .extractors()
                        .get(0)
                        .value()
                        .lineOffset();

        assertThat(lineOffset).isEqualTo(2);
    }

    @Test
    void malformed_line_offsets_are_unparseable() {
        for (String bad : List.of("-1", "11", "2.0", "\"2\"", "4294967296", "4294967297")) {
            assertInternal(columnDefinition(", \"lineOffset\": " + bad));
        }
    }

    @Test
    void relative_line_offset_rejects_invalid_extractor_shapes() {
        assertInternal(
                columnDefinition(", \"lineOffset\": 2")
                        .replace("\"method\": \"ANCHOR_LABEL\"", "\"method\": \"REGEX\"")
                        .replace("\"scope\": \"LINE\"", "\"scope\": \"PAGE\""));
        assertInternal(
                columnDefinition(", \"lineOffset\": 2")
                        .replace("\"scope\": \"LINE\"", "\"scope\": \"LINE_RIGHT\""));
        assertInternal(
                columnDefinition(", \"lineOffset\": 2")
                        .replace("\"occurrence\": 0", "\"occurrence\": 1"));
        assertInternal(
                """
                {"fields": [{"name": "incomeOrLoss", "dataType": "MONEY", "required": true,
                  "normalizer": "money", "sensitive": false,
                  "extractors": [{"method": "ANCHOR_LABEL", "strength": 0.9,
                    "label": {"kind": "literal", "pattern": "Subtract line 20 from line 3"},
                    "value": {"pattern": "x", "occurrence": 0, "scope": "LINE",
                              "lineOffset": 2}}]}]}
                """);
        assertInternal(
                """
                {"fields": [{"name": "incomeOrLoss", "dataType": "MONEY", "required": true,
                  "normalizer": "money", "sensitive": false,
                  "group": {"kind": "ROW",
                    "region": {
                      "start": {"kind": "literal", "pattern": "Income or Loss"},
                      "end": {"kind": "literal", "pattern": "Total"}},
                    "maxRows": 20},
                  "extractors": [{"method": "ANCHOR_LABEL", "strength": 0.9,
                    "label": {"kind": "literal", "pattern": "Subtract line 20 from line 3"},
                    "value": {"pattern": "x", "occurrence": 0, "scope": "LINE",
                              "lineOffset": 2}}]}]}
                """);
    }

    @Test
    void a_field_with_no_group_block_parses_exactly_as_it_did_before_spec_5a() {
        // THE regression pin for this task. Every schema shipped so far is single-valued, so if
        // an ungrouped field's parse moves by so much as a null, the spec has broken production
        // data. Record equality is DEEP, so this single assertion covers all seven components
        // and the whole extractor ladder at once — and it also pins that the six-argument
        // convenience constructor still means what it meant.
        when(schemas.findActiveVisibleTo(ORG))
                .thenReturn(List.of(schema(null, "PAYSTUB", "1.0.0", minimal("netPay"))));

        FieldSpec parsed = loader.activeSchemaFor("PAYSTUB").orElseThrow().fields().get(0);

        assertThat(parsed)
                .isEqualTo(
                        new FieldSpec(
                                "netPay",
                                DataType.STRING,
                                true,
                                null,
                                false,
                                List.of(
                                        new ExtractorSpec(
                                                ExtractionMethod.REGEX,
                                                0.5,
                                                null,
                                                null,
                                                new ValueSpec("x", 0, ValueScope.PAGE)))));
        assertThat(parsed.group())
                .as("no group block means NO group — never an empty or defaulted one")
                .isNull();
    }

    @Test
    void a_column_group_parses_its_header_and_its_printed_keys() {
        when(schemas.findActiveVisibleTo(ORG))
                .thenReturn(
                        List.of(
                                schema(
                                        null,
                                        "SCHEDULE_E",
                                        "1.0.0",
                                        """
                                        {"fields": [{"name": "rentsReceived",
                                          "dataType": "MONEY", "required": true,
                                          "normalizer": "money", "sensitive": false,
                                          "group": {"kind": "COLUMN",
                                            "header": {"kind": "literal",
                                                       "pattern": "Properties:"},
                                            "keys": ["A", "B", "C"]},
                                          "extractors": [{"method": "ANCHOR_LABEL",
                                            "strength": 0.9,
                                            "label": {"kind": "literal",
                                                      "pattern": "Rents received"},
                                            "value": {"pattern": "x", "occurrence": 0,
                                                      "scope": "LINE"}}]}]}
                                        """)));

        FieldSpec rents = loader.activeSchemaFor("SCHEDULE_E").orElseThrow().fields().get(0);

        assertThat(rents.group())
                .isEqualTo(
                        GroupSpec.column(
                                new LabelSpec(AnchorKind.LITERAL, "Properties:"),
                                List.of("A", "B", "C")));
        assertThat(rents.group().kind()).isEqualTo(GroupKind.COLUMN);
        // A column group carries no row geometry.
        assertThat(rents.group().region()).isNull();
        assertThat(rents.group().maxRows()).isNull();
        // The group is orthogonal to the ladder: the rung parses exactly as it would without
        // one, and it is the ENGINE that will run it once per key (T3).
        assertThat(rents.extractors()).hasSize(1);
        assertThat(rents.extractors().get(0).method()).isEqualTo(ExtractionMethod.ANCHOR_LABEL);
        assertThat(rents.extractors().get(0).label())
                .isEqualTo(new LabelSpec(AnchorKind.LITERAL, "Rents received"));
    }

    @Test
    void a_row_group_parses_its_bounded_region_and_its_maxRows() {
        // NOTE: the design's Part II example uses a ROW_CELL rung, which T4 adds. This task is
        // about the GROUP block, and the rung beside it is irrelevant to it — so this fixture
        // uses a REGEX rung, which exists today.
        when(schemas.findActiveVisibleTo(ORG))
                .thenReturn(
                        List.of(
                                schema(
                                        null,
                                        "SCHEDULE_E",
                                        "1.0.0",
                                        """
                                        {"fields": [{"name": "passthroughIncome",
                                          "dataType": "MONEY", "required": true,
                                          "normalizer": "money", "sensitive": false,
                                          "group": {"kind": "ROW",
                                            "region": {
                                              "start": {"kind": "literal",
                                                "pattern": "Income or Loss From Partnerships"},
                                              "end": {"kind": "literal",
                                                "pattern": "Total partnership and S corporation"}},
                                            "maxRows": 20},
                                          "extractors": [{"method": "REGEX", "strength": 0.9,
                                            "value": {"pattern": "x", "occurrence": 0,
                                                      "scope": "LINE"}}]}]}
                                        """)));

        FieldSpec passthrough =
                loader.activeSchemaFor("SCHEDULE_E").orElseThrow().fields().get(0);

        assertThat(passthrough.group())
                .isEqualTo(
                        GroupSpec.row(
                                new GroupRegionSpec(
                                        new LabelSpec(
                                                AnchorKind.LITERAL,
                                                "Income or Loss From Partnerships"),
                                        new LabelSpec(
                                                AnchorKind.LITERAL,
                                                "Total partnership and S corporation")),
                                20));
        assertThat(passthrough.group().kind()).isEqualTo(GroupKind.ROW);
        assertThat(passthrough.group().maxRows()).isEqualTo(20);
        // A row group carries no column geometry.
        assertThat(passthrough.group().header()).isNull();
        assertThat(passthrough.group().keys()).isNull();
    }

    @Test
    void the_group_kind_is_read_case_insensitively_like_every_other_wire_enum() {
        when(schemas.findActiveVisibleTo(ORG))
                .thenReturn(
                        List.of(
                                schema(
                                        null,
                                        "SCHEDULE_E",
                                        "1.0.0",
                                        """
                                        {"fields": [{"name": "rentsReceived",
                                          "dataType": "MONEY", "required": true,
                                          "normalizer": "money", "sensitive": false,
                                          "group": {"kind": "column",
                                            "header": {"kind": "literal",
                                                       "pattern": "Properties:"},
                                            "keys": ["A", "B", "C"]},
                                          "extractors": [{"method": "REGEX", "strength": 0.9,
                                            "value": {"pattern": "x", "occurrence": 0,
                                                      "scope": "LINE"}}]}]}
                                        """)));

        assertThat(
                        loader.activeSchemaFor("SCHEDULE_E")
                                .orElseThrow()
                                .fields()
                                .get(0)
                                .group()
                                .kind())
                .isEqualTo(GroupKind.COLUMN);
    }

    // ── the rejections: an ambiguous group is an authoring error, never a default ─

    @Test
    void a_row_group_without_maxRows_is_unparseable() {
        // The bound is REQUIRED and never defaulted. An end anchor that fails to match would
        // otherwise leave the region running to the bottom of the document, emitting hundreds
        // of persisted occurrences — and a cap nobody chose is not a cap.
        assertInternal(
                """
                {"fields": [{"name": "passthroughIncome", "dataType": "MONEY", "required": true,
                  "normalizer": "money", "sensitive": false,
                  "group": {"kind": "ROW",
                    "region": {"start": {"kind": "literal", "pattern": "Partnerships"},
                               "end": {"kind": "literal", "pattern": "Total"}}},
                  "extractors": [{"method": "REGEX", "strength": 0.9,
                    "value": {"pattern": "x", "occurrence": 0, "scope": "LINE"}}]}]}
                """);
    }

    @Test
    void a_row_group_with_a_nonpositive_maxRows_is_unparseable() {
        assertInternal(
                """
                {"fields": [{"name": "passthroughIncome", "dataType": "MONEY", "required": true,
                  "normalizer": "money", "sensitive": false,
                  "group": {"kind": "ROW",
                    "region": {"start": {"kind": "literal", "pattern": "Partnerships"},
                               "end": {"kind": "literal", "pattern": "Total"}},
                    "maxRows": 0},
                  "extractors": [{"method": "REGEX", "strength": 0.9,
                    "value": {"pattern": "x", "occurrence": 0, "scope": "LINE"}}]}]}
                """);
    }

    @Test
    void a_row_group_whose_maxRows_needs_three_digits_is_unparseable() {
        // The row key is the ordinal ZERO-PADDED TO TWO DIGITS, and group_key is text, so every
        // ordering that touches it sorts LEXICALLY. Two digits sort correctly to 99; the 100th
        // row would format as "100", which sorts between "01" and "02" and scrambles the whole
        // table's presentation order. Nothing would be wrong — every value and box stays correct
        // — but a consumer taking "the first entity" would take the wrong one, silently. A group
        // needing three digits is a schema-authoring error, and the loader says so at the door
        // rather than mis-sorting later.
        assertInternal(
                """
                {"fields": [{"name": "passthroughIncome", "dataType": "MONEY", "required": true,
                  "normalizer": "money", "sensitive": false,
                  "group": {"kind": "ROW",
                    "region": {"start": {"kind": "literal", "pattern": "Partnerships"},
                               "end": {"kind": "literal", "pattern": "Total"}},
                    "maxRows": 100},
                  "extractors": [{"method": "REGEX", "strength": 0.9,
                    "value": {"pattern": "x", "occurrence": 0, "scope": "LINE"}}]}]}
                """);
    }

    @Test
    void a_row_group_with_the_largest_two_digit_maxRows_still_parses() {
        // The boundary is inclusive on 99: rejecting it would be an off-by-one that outlawed a
        // legitimate schema, which is the opposite failure from the one above.
        when(schemas.findActiveVisibleTo(ORG))
                .thenReturn(
                        List.of(
                                schema(
                                        null,
                                        "PAYSTUB",
                                        "1.0.0",
                                        """
                                        {"fields": [{"name": "passthroughIncome",
                                          "dataType": "MONEY", "required": true,
                                          "normalizer": "money", "sensitive": false,
                                          "group": {"kind": "ROW",
                                            "region": {
                                              "start": {"kind": "literal",
                                                        "pattern": "Partnerships"},
                                              "end": {"kind": "literal", "pattern": "Total"}},
                                            "maxRows": 99},
                                          "extractors": [{"method": "REGEX", "strength": 0.9,
                                            "value": {"pattern": "x", "occurrence": 0,
                                                      "scope": "LINE"}}]}]}
                                        """)));

        assertThat(loader.activeSchemaFor("PAYSTUB").orElseThrow().fields().get(0).group().maxRows())
                .isEqualTo(99);
    }

    @Test
    void two_fields_over_one_region_are_one_group_and_must_agree_on_maxRows() {
        // A ROW group is identified by its REGION — the region IS the table, and a table has one
        // row numbering. Two caps over one table would hand a consumer three occurrences of the
        // name column and twenty of the income column over the IDENTICAL printed rows, and the
        // join the contract prescribes (group by groupKey, read across field names) would run
        // off the end of one of them. The engine cannot repair this; the one door rejects it.
        assertInternal(
                """
                {"fields": [
                  {"name": "partnershipName", "dataType": "STRING", "required": true,
                   "normalizer": null, "sensitive": false,
                   "group": {"kind": "ROW",
                     "region": {"start": {"kind": "literal", "pattern": "Partnerships"},
                                "end": {"kind": "literal", "pattern": "Total"}},
                     "maxRows": 3},
                   "extractors": [{"method": "ROW_CELL", "strength": 0.9,
                     "columnHeader": {"kind": "literal", "pattern": "(a) Name"},
                     "value": {"pattern": "x", "occurrence": 0, "scope": "LINE"}}]},
                  {"name": "partnershipNonpassiveIncome", "dataType": "MONEY", "required": true,
                   "normalizer": "money", "sensitive": false,
                   "group": {"kind": "ROW",
                     "region": {"start": {"kind": "literal", "pattern": "Partnerships"},
                                "end": {"kind": "literal", "pattern": "Total"}},
                     "maxRows": 20},
                   "extractors": [{"method": "ROW_CELL", "strength": 0.9,
                     "columnHeader": {"kind": "literal", "pattern": "(j) Nonpassive income"},
                     "value": {"pattern": "x", "occurrence": 0, "scope": "LINE"}}]}]}
                """);
    }

    @Test
    void two_fields_over_one_region_that_agree_on_maxRows_parse_as_one_group() {
        // The other half: this is the SHIPPED shape — schedule_e@1.0.0 declares five Part II
        // fields over one region — and it must keep parsing. Rejecting the agreeing case would
        // outlaw repeating groups entirely.
        when(schemas.findActiveVisibleTo(ORG))
                .thenReturn(
                        List.of(
                                schema(
                                        null,
                                        "SCHEDULE_E",
                                        "1.0.0",
                                        """
                                        {"fields": [
                                          {"name": "partnershipName", "dataType": "STRING",
                                           "required": true, "normalizer": null,
                                           "sensitive": false,
                                           "group": {"kind": "ROW",
                                             "region": {
                                               "start": {"kind": "literal",
                                                         "pattern": "Partnerships"},
                                               "end": {"kind": "literal", "pattern": "Total"}},
                                             "maxRows": 20},
                                           "extractors": [{"method": "ROW_CELL", "strength": 0.9,
                                             "columnHeader": {"kind": "literal",
                                                              "pattern": "(a) Name"},
                                             "value": {"pattern": "x", "occurrence": 0,
                                                       "scope": "LINE"}}]},
                                          {"name": "partnershipNonpassiveIncome",
                                           "dataType": "MONEY", "required": true,
                                           "normalizer": "money", "sensitive": false,
                                           "group": {"kind": "ROW",
                                             "region": {
                                               "start": {"kind": "literal",
                                                         "pattern": "Partnerships"},
                                               "end": {"kind": "literal", "pattern": "Total"}},
                                             "maxRows": 20},
                                           "extractors": [{"method": "ROW_CELL", "strength": 0.9,
                                             "columnHeader": {"kind": "literal",
                                                              "pattern": "(j) Nonpassive income"},
                                             "value": {"pattern": "x", "occurrence": 0,
                                                       "scope": "LINE"}}]}]}
                                        """)));

        List<FieldSpec> fields = loader.activeSchemaFor("SCHEDULE_E").orElseThrow().fields();

        assertThat(fields).hasSize(2);
        assertThat(fields.get(0).group().region())
                .as("one region, one group — the identity the engine resolves the origin per")
                .isEqualTo(fields.get(1).group().region());
        assertThat(fields.get(0).group().maxRows()).isEqualTo(20);
        assertThat(fields.get(1).group().maxRows()).isEqualTo(20);
    }

    // ── printed row labels: the form's own letters as the row key ───────────

    /** One labeled ROW field; {@code labelsJson} is the rowLabels array, verbatim. */
    private static String labeledRowDefinition(String labelsJson) {
        return labeledRowDefinition(labelsJson, 20);
    }

    private static String labeledRowDefinition(String labelsJson, int maxRows) {
        return """
               {"fields": [{"name": "partnershipName", "dataType": "STRING", "required": true,
                 "normalizer": null, "sensitive": false,
                 "group": {"kind": "ROW",
                   "region": {"start": {"kind": "literal", "pattern": "Partnerships"},
                              "end": {"kind": "literal", "pattern": "Total"}},
                   "maxRows": %d,
                   "rowLabels": %s},
                 "extractors": [{"method": "ROW_CELL", "strength": 0.9,
                   "columnHeader": {"kind": "literal", "pattern": "(a) Name"},
                   "value": {"pattern": "x", "occurrence": 0, "scope": "LINE"}}]}]}
               """
                .formatted(maxRows, labelsJson);
    }

    @Test
    void a_row_group_parses_its_printed_row_labels() {
        // Schedule E Parts II/III preprint their row letters in the left-margin gutter of BOTH
        // sub-tables — the entity band and the money band. The letters are the join key the
        // form itself provides (design D2), which is what lets name row A and money row A be
        // one entity even though they are printed on different lines.
        when(schemas.findActiveVisibleTo(ORG))
                .thenReturn(
                        List.of(
                                schema(
                                        null,
                                        "SCHEDULE_E",
                                        "1.0.0",
                                        labeledRowDefinition("[\"A\", \"B\", \"C\", \"D\"]"))));

        FieldSpec parsed = loader.activeSchemaFor("SCHEDULE_E").orElseThrow().fields().get(0);

        assertThat(parsed.group())
                .isEqualTo(
                        GroupSpec.labeledRow(
                                new GroupRegionSpec(
                                        new LabelSpec(AnchorKind.LITERAL, "Partnerships"),
                                        new LabelSpec(AnchorKind.LITERAL, "Total")),
                                20,
                                List.of("A", "B", "C", "D")));
        assertThat(parsed.group().rowLabels()).containsExactly("A", "B", "C", "D");
    }

    @Test
    void a_row_group_without_rowLabels_still_parses_with_null_labels() {
        // The opt-out IS the default: every unlabeled table (K-1s, Part IV) keeps counting
        // ordinals, and a defaulted-empty list would be a third state nobody declared.
        when(schemas.findActiveVisibleTo(ORG))
                .thenReturn(
                        List.of(
                                schema(
                                        null,
                                        "SCHEDULE_E",
                                        "1.0.0",
                                        """
                                        {"fields": [{"name": "remicName", "dataType": "STRING",
                                          "required": true, "normalizer": null,
                                          "sensitive": false,
                                          "group": {"kind": "ROW",
                                            "region": {
                                              "start": {"kind": "literal", "pattern": "REMIC"},
                                              "end": {"kind": "literal", "pattern": "Combine"}},
                                            "maxRows": 10},
                                          "extractors": [{"method": "ROW_CELL", "strength": 0.9,
                                            "columnHeader": {"kind": "literal",
                                                             "pattern": "(a) Name"},
                                            "value": {"pattern": "x", "occurrence": 0,
                                                      "scope": "LINE"}}]}]}
                                        """)));

        assertThat(
                        loader.activeSchemaFor("SCHEDULE_E")
                                .orElseThrow()
                                .fields()
                                .get(0)
                                .group()
                                .rowLabels())
                .isNull();
    }

    @Test
    void row_labels_must_be_single_ascending_capital_letters() {
        // Single characters 'A'..'Z': the key is text and every ordering that touches it sorts
        // lexically, so a lowercase 'a' would sort after 'Z' and a multi-character label would
        // interleave with single ones. Ascending declared order = lexical order = the order the
        // form prints the rows, for the same reason the ordinal scheme zero-pads.
        for (String bad :
                List.of(
                        "[\"AA\", \"B\"]",
                        "[\"a\", \"b\"]",
                        "[\"1\", \"2\"]",
                        "[\"\", \"B\"]",
                        "[\"A\", \"A\"]",
                        "[\"B\", \"A\"]",
                        "[]")) {
            assertInternal(labeledRowDefinition(bad));
        }
    }

    @Test
    void more_row_labels_than_maxRows_is_unparseable() {
        // maxRows is the cap on occurrences and each declared label IS an occurrence: declaring
        // more letters than the cap admits is a contradiction the engine cannot resolve.
        assertInternal(labeledRowDefinition("[\"A\", \"B\", \"C\", \"D\"]", 3));
    }

    @Test
    void row_labels_on_a_column_group_are_unparseable() {
        // A COLUMN group's keys already ARE its printed labels; a second label list would be a
        // second key space over one group.
        assertInternal(
                """
                {"fields": [{"name": "rentsReceived", "dataType": "MONEY", "required": true,
                  "normalizer": "money", "sensitive": false,
                  "group": {"kind": "COLUMN",
                    "header": {"kind": "literal", "pattern": "Properties:"},
                    "keys": ["A", "B", "C"],
                    "rowLabels": ["A", "B"]},
                  "extractors": [{"method": "REGEX", "strength": 0.9,
                    "value": {"pattern": "x", "occurrence": 0, "scope": "LINE"}}]}]}
                """);
    }

    @Test
    void two_fields_over_one_region_must_agree_on_row_labels() {
        // The same argument as maxRows, sharpened: the labels ARE the key space, and two fields
        // walking one table with different key spaces cannot join. The one door rejects it.
        assertInternal(
                """
                {"fields": [
                  {"name": "partnershipName", "dataType": "STRING", "required": true,
                   "normalizer": null, "sensitive": false,
                   "group": {"kind": "ROW",
                     "region": {"start": {"kind": "literal", "pattern": "Partnerships"},
                                "end": {"kind": "literal", "pattern": "Total"}},
                     "maxRows": 20,
                     "rowLabels": ["A", "B"]},
                   "extractors": [{"method": "ROW_CELL", "strength": 0.9,
                     "columnHeader": {"kind": "literal", "pattern": "(a) Name"},
                     "value": {"pattern": "x", "occurrence": 0, "scope": "LINE"}}]},
                  {"name": "partnershipNonpassiveIncome", "dataType": "MONEY", "required": true,
                   "normalizer": "money", "sensitive": false,
                   "group": {"kind": "ROW",
                     "region": {"start": {"kind": "literal", "pattern": "Partnerships"},
                                "end": {"kind": "literal", "pattern": "Total"}},
                     "maxRows": 20,
                     "rowLabels": ["A", "B", "C"]},
                   "extractors": [{"method": "ROW_CELL", "strength": 0.9,
                     "columnHeader": {"kind": "literal", "pattern": "(k) Nonpassive income"},
                     "value": {"pattern": "x", "occurrence": 0, "scope": "LINE"}}]}]}
                """);
    }

    @Test
    void a_labeled_field_and_an_unlabeled_field_over_one_region_are_unparseable() {
        // Half-labeled is the worst of both: the labeled field keys by letter, the unlabeled
        // one by counted ordinal, and "A" can never join "01".
        assertInternal(
                """
                {"fields": [
                  {"name": "partnershipName", "dataType": "STRING", "required": true,
                   "normalizer": null, "sensitive": false,
                   "group": {"kind": "ROW",
                     "region": {"start": {"kind": "literal", "pattern": "Partnerships"},
                                "end": {"kind": "literal", "pattern": "Total"}},
                     "maxRows": 20,
                     "rowLabels": ["A", "B"]},
                   "extractors": [{"method": "ROW_CELL", "strength": 0.9,
                     "columnHeader": {"kind": "literal", "pattern": "(a) Name"},
                     "value": {"pattern": "x", "occurrence": 0, "scope": "LINE"}}]},
                  {"name": "partnershipNonpassiveIncome", "dataType": "MONEY", "required": true,
                   "normalizer": "money", "sensitive": false,
                   "group": {"kind": "ROW",
                     "region": {"start": {"kind": "literal", "pattern": "Partnerships"},
                                "end": {"kind": "literal", "pattern": "Total"}},
                     "maxRows": 20},
                   "extractors": [{"method": "ROW_CELL", "strength": 0.9,
                     "columnHeader": {"kind": "literal", "pattern": "(k) Nonpassive income"},
                     "value": {"pattern": "x", "occurrence": 0, "scope": "LINE"}}]}]}
                """);
    }

    @Test
    void a_row_group_without_a_region_is_unparseable() {
        assertInternal(
                """
                {"fields": [{"name": "passthroughIncome", "dataType": "MONEY", "required": true,
                  "normalizer": "money", "sensitive": false,
                  "group": {"kind": "ROW", "maxRows": 20},
                  "extractors": [{"method": "REGEX", "strength": 0.9,
                    "value": {"pattern": "x", "occurrence": 0, "scope": "LINE"}}]}]}
                """);
    }

    @Test
    void a_row_group_whose_region_has_no_end_anchor_is_unparseable() {
        // Half a region is worse than none: the walk would have no closing boundary at all and
        // would lean entirely on maxRows, silently capturing whatever follows the table.
        assertInternal(
                """
                {"fields": [{"name": "passthroughIncome", "dataType": "MONEY", "required": true,
                  "normalizer": "money", "sensitive": false,
                  "group": {"kind": "ROW",
                    "region": {"start": {"kind": "literal", "pattern": "Partnerships"}},
                    "maxRows": 20},
                  "extractors": [{"method": "REGEX", "strength": 0.9,
                    "value": {"pattern": "x", "occurrence": 0, "scope": "LINE"}}]}]}
                """);
    }

    @Test
    void a_column_group_without_keys_is_unparseable() {
        // Keys are the group: without them there is nothing to run the ladder once per.
        assertInternal(
                """
                {"fields": [{"name": "rentsReceived", "dataType": "MONEY", "required": true,
                  "normalizer": "money", "sensitive": false,
                  "group": {"kind": "COLUMN",
                    "header": {"kind": "literal", "pattern": "Properties:"}},
                  "extractors": [{"method": "REGEX", "strength": 0.9,
                    "value": {"pattern": "x", "occurrence": 0, "scope": "LINE"}}]}]}
                """);
    }

    @Test
    void a_column_group_with_an_empty_keys_array_is_unparseable() {
        assertInternal(
                """
                {"fields": [{"name": "rentsReceived", "dataType": "MONEY", "required": true,
                  "normalizer": "money", "sensitive": false,
                  "group": {"kind": "COLUMN",
                    "header": {"kind": "literal", "pattern": "Properties:"},
                    "keys": []},
                  "extractors": [{"method": "REGEX", "strength": 0.9,
                    "value": {"pattern": "x", "occurrence": 0, "scope": "LINE"}}]}]}
                """);
    }

    @Test
    void a_column_group_with_duplicate_keys_is_unparseable() {
        // group_key rides in the extracted_field_one_current unique index, so a repeated key
        // would be a constraint violation at persist time — a 500 on a real document instead of
        // a schema that never loaded. Reject it at the door.
        assertInternal(
                """
                {"fields": [{"name": "rentsReceived", "dataType": "MONEY", "required": true,
                  "normalizer": "money", "sensitive": false,
                  "group": {"kind": "COLUMN",
                    "header": {"kind": "literal", "pattern": "Properties:"},
                    "keys": ["A", "B", "A"]},
                  "extractors": [{"method": "REGEX", "strength": 0.9,
                    "value": {"pattern": "x", "occurrence": 0, "scope": "LINE"}}]}]}
                """);
    }

    @Test
    void a_column_group_with_a_blank_key_is_unparseable() {
        // A blank key would persist as '' — which coalesce(group_key, '') makes indistinguishable
        // from an UNGROUPED row, quietly re-creating the one-value-per-name collision the whole
        // spec exists to escape.
        assertInternal(
                """
                {"fields": [{"name": "rentsReceived", "dataType": "MONEY", "required": true,
                  "normalizer": "money", "sensitive": false,
                  "group": {"kind": "COLUMN",
                    "header": {"kind": "literal", "pattern": "Properties:"},
                    "keys": ["A", "", "C"]},
                  "extractors": [{"method": "REGEX", "strength": 0.9,
                    "value": {"pattern": "x", "occurrence": 0, "scope": "LINE"}}]}]}
                """);
    }

    @Test
    void a_column_group_without_a_header_is_unparseable() {
        // Column x-ranges derive from the PRINTED header, never from fixed offsets — that is
        // what keeps a differently-scaled scan from shifting every key one column over.
        assertInternal(
                """
                {"fields": [{"name": "rentsReceived", "dataType": "MONEY", "required": true,
                  "normalizer": "money", "sensitive": false,
                  "group": {"kind": "COLUMN", "keys": ["A", "B", "C"]},
                  "extractors": [{"method": "REGEX", "strength": 0.9,
                    "value": {"pattern": "x", "occurrence": 0, "scope": "LINE"}}]}]}
                """);
    }

    @Test
    void a_group_of_an_unknown_kind_is_unparseable() {
        // Two geometries, and only two. A third spelling is an authoring error, not a hint.
        assertInternal(
                """
                {"fields": [{"name": "rentsReceived", "dataType": "MONEY", "required": true,
                  "normalizer": "money", "sensitive": false,
                  "group": {"kind": "DIAGONAL",
                    "header": {"kind": "literal", "pattern": "Properties:"},
                    "keys": ["A", "B", "C"]},
                  "extractors": [{"method": "REGEX", "strength": 0.9,
                    "value": {"pattern": "x", "occurrence": 0, "scope": "LINE"}}]}]}
                """);
    }

    @Test
    void a_group_with_no_kind_at_all_is_unparseable() {
        assertInternal(
                """
                {"fields": [{"name": "rentsReceived", "dataType": "MONEY", "required": true,
                  "normalizer": "money", "sensitive": false,
                  "group": {"header": {"kind": "literal", "pattern": "Properties:"},
                    "keys": ["A", "B", "C"]},
                  "extractors": [{"method": "REGEX", "strength": 0.9,
                    "value": {"pattern": "x", "occurrence": 0, "scope": "LINE"}}]}]}
                """);
    }

    // ── V40: the LABEL_ABOVE rung's shape — LABEL_BELOW's vertical mirror ───

    @Test
    void a_label_above_extractor_parses_its_label_value_and_tile_reach() {
        when(schemas.findActiveVisibleTo(ORG))
                .thenReturn(
                        List.of(
                                schema(
                                        null,
                                        "BANK_STATEMENT",
                                        "1.5.0",
                                        """
                                        {"fields": [{"name": "endingBalance",
                                          "dataType": "MONEY", "required": false,
                                          "normalizer": "money", "sensitive": false,
                                          "extractors": [{"method": "LABEL_ABOVE",
                                            "strength": 0.8,
                                            "label": {"kind": "literal",
                                                      "pattern": "Present balance"},
                                            "maxRisePt": 18.0,
                                            "cellOverlap": 0.75,
                                            "value": {"pattern": "\\\\d+\\\\.\\\\d{2}",
                                                      "occurrence": 0}}]}]}
                                        """)));

        SchemaDefinition parsed = loader.activeSchemaFor("BANK_STATEMENT").orElseThrow();

        ExtractorSpec rung = parsed.fields().get(0).extractors().get(0);
        assertThat(rung.method()).isEqualTo(ExtractionMethod.LABEL_ABOVE);
        assertThat(rung.strength()).isEqualTo(0.8);
        assertThat(rung.label()).isEqualTo(new LabelSpec(AnchorKind.LITERAL, "Present balance"));
        assertThat(rung.value().pattern()).isEqualTo("\\d+\\.\\d{2}");
        assertThat(rung.value().occurrence()).isZero();
        assertThat(rung.maxRisePt()).isEqualTo(18.0);
        assertThat(rung.cellOverlap()).isEqualTo(0.75);
        // The rung reads UP: it carries no drop, and it is neither a table, a row nor a
        // detector rung.
        assertThat(rung.maxDropPt()).isNull();
        assertThat(rung.table()).isNull();
        assertThat(rung.options()).isNull();
        assertThat(rung.proximityPt()).isNull();
        assertThat(rung.region()).isNull();
        assertThat(rung.columnHeader()).isNull();
    }

    @Test
    void a_label_above_extractor_without_tile_reach_takes_the_documented_defaults() {
        // The CONTRACT's defaults: maxRisePt 24.0 — maxDropPt's mirror, the same number on
        // purpose — and cellOverlap 0.5. On the real online print-out the amount's bottom
        // edge sits 3.7 pt above its caption's top; the neighbouring tile is kept out by
        // the cell window, not by this reach.
        when(schemas.findActiveVisibleTo(ORG))
                .thenReturn(
                        List.of(
                                schema(
                                        null,
                                        "BANK_STATEMENT",
                                        "1.5.0",
                                        """
                                        {"fields": [{"name": "endingBalance",
                                          "dataType": "MONEY", "required": false,
                                          "normalizer": "money", "sensitive": false,
                                          "extractors": [{"method": "LABEL_ABOVE",
                                            "strength": 0.8,
                                            "label": {"kind": "literal",
                                                      "pattern": "Present balance"},
                                            "value": {"pattern": "\\\\d+\\\\.\\\\d{2}",
                                                      "occurrence": 0}}]}]}
                                        """)));

        ExtractorSpec rung =
                loader.activeSchemaFor("BANK_STATEMENT")
                        .orElseThrow()
                        .fields()
                        .get(0)
                        .extractors()
                        .get(0);

        assertThat(rung.maxRisePt()).isEqualTo(24.0);
        assertThat(rung.cellOverlap()).isEqualTo(0.5);
        // No "scope" in the value block: LABEL_ABOVE's scope IS the tile, as LABEL_BELOW's
        // is the cell, and the stand-in is never consulted by the rung.
        assertThat(rung.value().scope()).isEqualTo(ValueScope.LINE);
    }

    @Test
    void a_label_above_extractor_without_a_label_is_unparseable() {
        assertInternal(
                """
                {"fields": [{"name": "endingBalance", "dataType": "MONEY", "required": false,
                  "normalizer": "money", "sensitive": false,
                  "extractors": [{"method": "LABEL_ABOVE", "strength": 0.8,
                    "value": {"pattern": "x", "occurrence": 0}}]}]}
                """);
    }

    private void assertInternal(String definition) {
        when(schemas.findActiveVisibleTo(ORG))
                .thenReturn(List.of(schema(null, "PAYSTUB", "1.0.0", definition)));

        assertThatThrownBy(() -> loader.activeSchemaFor("PAYSTUB"))
                .isInstanceOf(DomainException.class)
                .satisfies(
                        e ->
                                assertThat(((DomainException) e).code())
                                        .isEqualTo(ErrorCode.INTERNAL));
    }

    @Test
    void the_cache_is_keyed_by_the_current_tenant() {
        UUID otherOrg = UUID.fromString("00000000-0000-0000-0000-000000000002");
        when(schemas.findActiveVisibleTo(ORG))
                .thenReturn(List.of(schema(ORG, "PAYSTUB", "1.0.0", minimal("mine"))));
        when(schemas.findActiveVisibleTo(otherOrg)).thenReturn(List.of());

        assertThat(loader.activeSchemaFor("PAYSTUB")).isPresent();

        TenantContext.set(otherOrg);
        Optional<SchemaDefinition> other = loader.activeSchemaFor("PAYSTUB");
        assertThat(other).isEmpty();
    }

    // ── Task 3: schema-declared derivation ──────────────────────────────

    private static final String DERIVED_BANK = """
        {"fields": [
          {"name": "beginningBalance", "dataType": "MONEY", "required": true, "normalizer": "money", "sensitive": false,
           "extractors": [{"method": "ANCHOR_LABEL", "strength": 0.9,
             "label": {"kind": "literal", "pattern": "Beginning Balance"},
             "value": {"pattern": "\\\\d+\\\\.\\\\d{2}", "occurrence": 0, "scope": "LINE_RIGHT"}}]},
          {"name": "totalDeposits", "dataType": "MONEY", "required": true, "normalizer": "money", "sensitive": false,
           "extractors": [{"method": "ANCHOR_LABEL", "strength": 0.9,
             "label": {"kind": "literal", "pattern": "Total Deposits"},
             "value": {"pattern": "\\\\d+\\\\.\\\\d{2}", "occurrence": 0, "scope": "LINE_RIGHT"}}]},
          {"name": "endingBalance", "dataType": "MONEY", "required": true, "normalizer": "money", "sensitive": false,
           "extractors": [{"method": "ANCHOR_LABEL", "strength": 0.9,
             "label": {"kind": "literal", "pattern": "Ending Balance"},
             "value": {"pattern": "\\\\d+\\\\.\\\\d{2}", "occurrence": 0, "scope": "LINE_RIGHT"}}]},
          {"name": "totalWithdrawals", "dataType": "MONEY", "required": true, "normalizer": "money", "sensitive": false,
           "derivation": {"plus": ["beginningBalance", "totalDeposits"], "minus": ["endingBalance"]},
           "extractors": [{"method": "ANCHOR_LABEL", "strength": 0.9,
             "label": {"kind": "literal", "pattern": "Total Withdrawals"},
             "value": {"pattern": "\\\\d+\\\\.\\\\d{2}", "occurrence": 0, "scope": "LINE_RIGHT"}}]}
        ]}""";

    @Test
    void a_derivation_parses() {
        SchemaDefinition schema = load(DERIVED_BANK);
        FieldSpec withdrawals = schema.fields().get(3);
        assertThat(withdrawals.derivation()).isEqualTo(
                new DerivationSpec(List.of("beginningBalance", "totalDeposits"), List.of("endingBalance")));
        assertThat(schema.fields().get(0).derivation()).isNull();
    }

    @Test
    void a_derivation_must_reference_ungrouped_money_fields_of_the_schema() {
        assertThatThrownBy(() -> load(DERIVED_BANK.replace("\"endingBalance\"]", "\"noSuchField\"]")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("derivation references unknown field noSuchField");
        assertThatThrownBy(() -> load(DERIVED_BANK.replace("\"minus\": [\"endingBalance\"]", "\"minus\": [\"totalWithdrawals\"]")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("derivation references itself");
        assertThatThrownBy(() -> load(DERIVED_BANK.replace(
                        "{\"name\": \"totalDeposits\", \"dataType\": \"MONEY\"",
                        "{\"name\": \"totalDeposits\", \"dataType\": \"STRING\"")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("derivation input totalDeposits is not MONEY");
    }

    @Test
    void a_derivation_on_a_string_or_grouped_field_is_rejected() {
        assertThatThrownBy(() -> load(DERIVED_BANK.replace(
                        "{\"name\": \"totalWithdrawals\", \"dataType\": \"MONEY\"",
                        "{\"name\": \"totalWithdrawals\", \"dataType\": \"STRING\"")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("derivation requires a MONEY field");
    }

    @Test
    void a_non_array_derivation_input_is_rejected() {
        assertThatThrownBy(() -> load(DERIVED_BANK.replace(
                        "\"minus\": [\"endingBalance\"]", "\"minus\": \"endingBalance\"")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("derivation.minus must be an array of field names");
    }

    @Test
    void an_unknown_derivation_key_is_rejected() {
        assertThatThrownBy(() -> load(DERIVED_BANK.replace(
                        "\"minus\": [\"endingBalance\"]", "\"subtract\": [\"endingBalance\"]")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("derivation has unknown key subtract");
    }

    @Test
    void a_chained_derivation_is_rejected() {
        // endingBalance gains its own derivation, so totalWithdrawals' input is itself derived.
        assertThatThrownBy(() -> load(DERIVED_BANK.replace(
                        "{\"name\": \"endingBalance\", \"dataType\": \"MONEY\", \"required\": true, \"normalizer\": \"money\", \"sensitive\": false,",
                        "{\"name\": \"endingBalance\", \"dataType\": \"MONEY\", \"required\": true, \"normalizer\": \"money\", \"sensitive\": false,"
                                + " \"derivation\": {\"plus\": [\"beginningBalance\"], \"minus\": []},")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is itself derived");
    }

    // ── Task 4: next-line join on value rungs ───────────────────────────

    private static final String JOINED_HOLDER = """
        {"fields": [
          {"name": "accountHolderName", "dataType": "STRING", "required": true, "normalizer": "personName", "sensitive": false,
           "extractors": [{"method": "REGEX", "strength": 0.6,
             "value": {"pattern": "[A-Z]+ [A-Z]+", "occurrence": 0, "scope": "PAGE",
                       "joinNextLine": "^(?:OR|AND) ([A-Z]+ [A-Z]+)$"}}]}
        ]}""";

    @Test
    void join_next_line_parses_on_a_string_field() {
        ValueSpec value = load(JOINED_HOLDER).fields().get(0).extractors().get(0).value();
        assertThat(value.joinNextLine()).isEqualTo("^(?:OR|AND) ([A-Z]+ [A-Z]+)$");
    }

    @Test
    void join_next_line_is_rejected_on_a_money_field_or_a_label_below_rung() {
        assertThatThrownBy(() -> load(JOINED_HOLDER.replace("\"dataType\": \"STRING\"", "\"dataType\": \"MONEY\"")
                        .replace("\"normalizer\": \"personName\"", "\"normalizer\": \"money\"")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("joinNextLine requires a STRING field");
        assertThatThrownBy(() -> load(JOINED_HOLDER.replace("\"method\": \"REGEX\"", "\"method\": \"LABEL_BELOW\", \"label\": {\"kind\": \"literal\", \"pattern\": \"Name\"}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("joinNextLine is an ANCHOR_LABEL or REGEX parameter");
    }
}
