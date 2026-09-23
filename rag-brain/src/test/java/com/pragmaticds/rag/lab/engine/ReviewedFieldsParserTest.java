package com.pragmaticds.rag.lab.engine;

import com.pragmaticds.rag.lab.engine.LabContractException.Code;
import com.pragmaticds.rag.lab.engine.ReviewedFields.GroupKind;
import com.pragmaticds.rag.lab.engine.ReviewedFields.ReviewedField;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Mirrors the engine's DocumentFieldsView / FieldView exactly (engine main @ 9a300e3). */
class ReviewedFieldsParserTest {

    private static final UUID DOC = UUID.fromString("77777777-7777-4777-8777-777777777770");
    private static final UUID PAGE = UUID.fromString("66666666-6666-4666-8666-666666666660");

    private static final String FIELD_TEMPLATE = """
            {"id":"@ID@","fieldName":"@NAME@","groupKey":@GROUP@,"groupKind":"@KIND@",
             "textProvenance":{"source":"NATIVE","ocrEngine":null},
             "dataType":"@TYPE@","displayedText":@DISPLAYED@,"rawValue":@RAW@,
             "normalized":{"text":@NTEXT@,"number":@NNUM@,"date":@NDATE@},
             "extractionMethod":"@METHOD@","extractorVersion":"engine/1.0.0",
             "confidence":@CONF@,
             "confidenceComponents":{"spanConfidence":1.0,"anchorStrength":0.9,"normalizerCertainty":1},
             "validationStatus":"NOT_VALIDATED","reviewStatus":"@REVIEW@",
             "effectiveStatus":"@EFFECTIVE@","sensitive":@SENSITIVE@,
             "evidence":[{"role":"VALUE","ordinal":0,"pageId":"@PAGE@","packagePageIndex":0,
                          "x":130.0,"y":94.1,"width":33.6,"height":10.2,
                          "textSpanId":4210,"layoutElementId":null}]}
            """;

    private static String field(String name, String group, String kind, String type,
                                String displayed, String raw, String ntext, String nnum,
                                String ndate, String method, String conf, String review,
                                String effective, String sensitive) {
        return FIELD_TEMPLATE
                .replace("@ID@", UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)).toString())
                .replace("@NAME@", name).replace("@GROUP@", group).replace("@KIND@", kind)
                .replace("@TYPE@", type).replace("@DISPLAYED@", displayed).replace("@RAW@", raw)
                .replace("@NTEXT@", ntext).replace("@NNUM@", nnum).replace("@NDATE@", ndate)
                .replace("@METHOD@", method).replace("@CONF@", conf).replace("@REVIEW@", review)
                .replace("@EFFECTIVE@", effective).replace("@SENSITIVE@", sensitive)
                .replace("@PAGE@", PAGE.toString());
    }

    private static String view(String... fields) {
        return "{\"documentId\":\"" + DOC + "\",\"documentTypeCode\":\"PAYSTUB\","
                + "\"schemaVersion\":\"2025.5a\",\"fields\":[" + String.join(",", fields) + "]}";
    }

    private static String machine() {
        return field("currentGrossPay", "null", "NONE", "MONEY", "\"4,670.69\"", "\"4,670.69\"",
                "null", "4670.69", "null", "TABLE_CLUSTER", "1.0", "NOT_REVIEWED", "MACHINE", "false");
    }

    private static String corrected() {
        return field("payDate", "null", "NONE", "DATE", "\"2026-01-17\"", "\"2026-01-11\"",
                "null", "null", "\"2026-01-17\"", "ANCHOR_LABEL", "0.9", "CORRECTED", "CORRECTED", "false");
    }

    private static String rejected() {
        return field("employerName", "\"A\"", "ROW", "STRING", "\"ACME WIDGETS LLC\"",
                "\"ACME WIDGETS LLC\"", "\"ACME WIDGETS LLC\"", "null", "null", "REGEX", "0.6",
                "REJECTED", "REJECTED", "false");
    }

    private static String masked() {
        return field("borrowerSsn", "null", "NONE", "STRING", "\"***-**-6789\"", "\"***-**-6789\"",
                "\"***-**-6789\"", "null", "null", "ANCHOR_LABEL", "0.9", "NOT_REVIEWED", "MACHINE", "true");
    }

    private final ReviewedFieldsParser parser = new ReviewedFieldsParser();

    private static byte[] bytes(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private void assertRejected(Code expected, String json) {
        LabContractException failure =
                assertThrows(LabContractException.class, () -> parser.parse(bytes(json)));
        assertEquals(expected, failure.code());
    }

    @Test
    void parsesTheThreeEffectiveStatusesAndRetainsIdentity() {
        ReviewedFields fields = parser.parse(bytes(view(machine(), corrected(), rejected(), masked())));

        assertEquals(DOC, fields.documentId());
        assertEquals("PAYSTUB", fields.documentTypeCode());
        assertEquals("2025.5a", fields.schemaVersion());
        assertEquals(4, fields.fields().size());
        assertTrue(fields.sha256().matches("[0-9a-f]{64}"));
        assertEquals(bytes(view(machine(), corrected(), rejected(), masked())).length, fields.byteCount());

        ReviewedField gross = fields.fields().get(0);
        assertEquals("currentGrossPay", gross.fieldName());
        assertNull(gross.groupKey());
        assertEquals(GroupKind.NONE, gross.groupKind());
        assertEquals(EngineResultEnvelope.ReviewState.MACHINE, gross.effectiveStatus());
        assertEquals("MONEY", gross.dataType());
        assertEquals(new BigDecimal("4670.69"), gross.normalized().number());
        assertEquals("TABLE_CLUSTER", gross.extractionMethod());
        assertEquals(List.of(PAGE), gross.evidencePageIds());

        ReviewedField payDate = fields.fields().get(1);
        assertEquals(EngineResultEnvelope.ReviewState.CORRECTED, payDate.effectiveStatus());
        assertEquals("2026-01-17", payDate.displayedText());
        assertEquals("2026-01-11", payDate.rawValue(), "rawValue stays the machine reading");
        assertEquals(LocalDate.parse("2026-01-17"), payDate.normalized().date());

        ReviewedField employer = fields.fields().get(2);
        assertEquals(EngineResultEnvelope.ReviewState.REJECTED, employer.effectiveStatus());
        assertEquals("A", employer.groupKey());
        assertEquals(GroupKind.ROW, employer.groupKind());
        assertEquals("ACME WIDGETS LLC", employer.displayedText(),
                "the parser keeps what the wire carried; the overlay is what drops it");

        assertTrue(fields.fields().get(3).sensitive());
        assertFalse(gross.sensitive());
    }

    @Test
    void aFieldWithNoValueArmsParsesWithNullsNotZeros() {
        String missing = field("federalWithholding", "null", "NONE", "MONEY", "null", "null",
                "null", "null", "null", "NONE", "0", "NOT_REVIEWED", "MACHINE", "false");
        ReviewedField parsed = parser.parse(bytes(view(missing))).fields().get(0);
        assertNull(parsed.displayedText());
        assertNull(parsed.rawValue());
        assertNull(parsed.normalized().number());
        assertNull(parsed.normalized().text());
        assertNull(parsed.normalized().date());
    }

    @Test
    void rejectsAnUnknownMemberAnywhere() {
        assertRejected(Code.READMODEL_MEMBER_UNKNOWN,
                view(machine()).replace("\"fields\":[", "\"extra\":1,\"fields\":["));
        assertRejected(Code.READMODEL_MEMBER_UNKNOWN,
                view(machine().replace("\"sensitive\":false", "\"sensitive\":false,\"note\":\"x\"")));
        assertRejected(Code.READMODEL_MEMBER_UNKNOWN,
                view(machine().replace("\"number\":4670.69", "\"number\":4670.69,\"json\":null")));
    }

    @Test
    void rejectsAMissingRequiredMember() {
        assertRejected(Code.READMODEL_MEMBER_MISSING,
                view(machine().replace("\"effectiveStatus\":\"MACHINE\",", "")));
        assertRejected(Code.READMODEL_MEMBER_MISSING,
                view(machine()).replace("\"schemaVersion\":\"2025.5a\",", ""));
    }

    @Test
    void rejectsVocabularyOutsideTheContract() {
        assertRejected(Code.READMODEL_VALUE_MALFORMED,
                view(machine().replace("\"effectiveStatus\":\"MACHINE\"", "\"effectiveStatus\":\"CONFIRMED\"")));
        assertRejected(Code.READMODEL_VALUE_MALFORMED,
                view(machine().replace("\"groupKind\":\"NONE\"", "\"groupKind\":\"TABLE\"")));
        assertRejected(Code.READMODEL_VALUE_MALFORMED,
                view(machine().replace("\"confidence\":1.0", "\"confidence\":1.5")));
        assertRejected(Code.READMODEL_VALUE_MALFORMED,
                view(machine().replace("\"packagePageIndex\":0", "\"packagePageIndex\":-1")));
        assertRejected(Code.READMODEL_VALUE_MALFORMED,
                view(machine().replace("\"sensitive\":false", "\"sensitive\":\"no\"")));
        assertRejected(Code.READMODEL_VALUE_MALFORMED,
                view(corrected().replace("\"date\":\"2026-01-17\"", "\"date\":\"17/01/2026\"")));
        // Type checks on dropped members
        assertRejected(Code.READMODEL_VALUE_MALFORMED,
                view(machine().replace("\"source\":\"NATIVE\"", "\"source\":123")));
        assertRejected(Code.READMODEL_VALUE_MALFORMED,
                view(machine().replace("\"ocrEngine\":null", "\"ocrEngine\":{}")));
        assertRejected(Code.READMODEL_VALUE_MALFORMED,
                view(machine().replace("\"anchorStrength\":0.9", "\"anchorStrength\":\"high\"")));
        assertRejected(Code.READMODEL_VALUE_MALFORMED,
                view(machine().replace("\"textSpanId\":4210", "\"textSpanId\":\"span-1\"")));
        assertRejected(Code.READMODEL_VALUE_MALFORMED,
                view(machine().replace("\"layoutElementId\":null", "\"layoutElementId\":\"not-a-uuid\"")));
    }

    @Test
    void theEngineCapturedReviewedGoldenParsesAndCarriesAllThreeStatuses() throws Exception {
        byte[] golden;
        try (var in = getClass().getClassLoader()
                .getResourceAsStream("engine-goldens/fields-paystub-reviewed.json")) {
            assertNotNull(in, "missing golden");
            golden = in.readAllBytes();
        }
        ReviewedFields fields = parser.parse(golden);
        Set<EngineResultEnvelope.ReviewState> statuses = fields.fields().stream()
                .map(ReviewedField::effectiveStatus).collect(Collectors.toSet());
        assertEquals(Set.of(EngineResultEnvelope.ReviewState.MACHINE,
                EngineResultEnvelope.ReviewState.CORRECTED,
                EngineResultEnvelope.ReviewState.REJECTED), statuses);
    }

    @Test
    void rejectsAnEmptyBodyAndANonObjectRoot() {
        assertRejected(Code.ENVELOPE_EMPTY, "");
        assertRejected(Code.ENVELOPE_ROOT_NOT_OBJECT, "[]");
        assertRejected(Code.ENVELOPE_NOT_STRICT_JSON, "{\"documentId\":1,,}");
    }
}
