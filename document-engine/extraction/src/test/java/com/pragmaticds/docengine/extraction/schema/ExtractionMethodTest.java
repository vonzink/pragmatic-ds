package com.pragmaticds.docengine.extraction.schema;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The wire enum mirrors {@code extracted_field_method_check} exactly. {@code fromWire} is the
 * only entry point schema JSON uses, so the tests pin the wire spelling. Assertions go through
 * {@code valueOf} rather than direct constant references so a test COMPILES before its constant
 * exists and fails at runtime — the watched RED.
 */
class ExtractionMethodTest {

    @Test
    void checkbox_state_parses_from_wire() {
        assertThat(ExtractionMethod.fromWire("CHECKBOX_STATE"))
                .isEqualTo(ExtractionMethod.valueOf("CHECKBOX_STATE"));
    }

    @Test
    void signature_presence_parses_from_wire_case_insensitively() {
        assertThat(ExtractionMethod.fromWire("signature_presence"))
                .isEqualTo(ExtractionMethod.valueOf("SIGNATURE_PRESENCE"));
    }

    // ── Spec 4: the box-grid rung joins the wire enum (V12 §1) ───────────────

    @Test
    void label_below_parses_from_wire() {
        assertThat(ExtractionMethod.fromWire("LABEL_BELOW"))
                .isEqualTo(ExtractionMethod.valueOf("LABEL_BELOW"));
    }

    @Test
    void label_below_parses_from_wire_case_insensitively() {
        assertThat(ExtractionMethod.fromWire("label_below"))
                .isEqualTo(ExtractionMethod.valueOf("LABEL_BELOW"));
    }

    // ── Spec 5a: the row-group rung joins the wire enum (V13 §2) ─────────────

    @Test
    void row_cell_parses_from_wire() {
        assertThat(ExtractionMethod.fromWire("ROW_CELL"))
                .isEqualTo(ExtractionMethod.valueOf("ROW_CELL"));
    }

    @Test
    void row_cell_parses_from_wire_case_insensitively() {
        assertThat(ExtractionMethod.fromWire("row_cell"))
                .isEqualTo(ExtractionMethod.valueOf("ROW_CELL"));
    }

    // ── V40 §1: the tile rung joins the wire enum ────────────────────────────

    @Test
    void label_above_parses_from_wire() {
        assertThat(ExtractionMethod.fromWire("LABEL_ABOVE"))
                .isEqualTo(ExtractionMethod.valueOf("LABEL_ABOVE"));
    }

    @Test
    void label_above_parses_from_wire_case_insensitively() {
        assertThat(ExtractionMethod.fromWire("label_above"))
                .isEqualTo(ExtractionMethod.valueOf("LABEL_ABOVE"));
    }

    // ── Bank statement field rules (2026-09-23): a value computed from other captured fields ──
    @Test
    void derived_parses_from_wire() {
        assertThat(ExtractionMethod.fromWire("derived")).isEqualTo(ExtractionMethod.valueOf("DERIVED"));
    }
}
