package com.pragmaticds.rag.lab.web;

import com.pragmaticds.rag.lab.engine.EngineResultEnvelope;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.FieldOccurrence;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.FieldStatus;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.NormalizedValue;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.SchemaRef;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The envelope projection goes to a browser over a shared admin key. A sensitive value must be
 * dropped at the DTO, structurally, so no screen has to remember to hide it.
 */
class LabDtosTest {

    @Test
    void aSensitiveOccurrenceKeepsItsIdentityAndDropsEveryValueArm() {
        LabDtos.Field field = LabDtos.Field.from(occurrence(true));

        assertEquals("employee.ssn", field.name());
        assertEquals("FOUND", field.status());
        assertTrue(field.sensitive());
        assertNull(field.displayedText());
        assertNull(field.rawValue());
        assertNull(field.normalizedText());
        assertNull(field.normalizedNumber());
        assertNull(field.normalizedDate());
        assertEquals(1, field.evidence().size(), "evidence geometry is not a value");
    }

    @Test
    void aNonSensitiveOccurrenceStillCarriesEveryValueArmVerbatim() {
        LabDtos.Field field = LabDtos.Field.from(occurrence(false));

        assertEquals("123-45-6789", field.displayedText());
        assertEquals("123-45-6789", field.rawValue());
        assertEquals("123456789", field.normalizedText());
        assertEquals("123456789", field.normalizedNumber());
        assertEquals("2026-01-31", field.normalizedDate());
    }

    @Test
    void theFieldProjectionCarriesTheReviewState() {
        FieldOccurrence machine = occurrence(false);
        assertEquals("UNREVIEWED_SOURCE", LabDtos.Field.from(machine).reviewState());
        FieldOccurrence rejected = new FieldOccurrence(machine.name(), machine.groupKey(),
                FieldStatus.MISSING, machine.dataType(), null, null, null, machine.schema(), "NONE",
                machine.extractorVersion(), machine.confidence(), null, machine.validationStatus(),
                false, machine.evidence(), EngineResultEnvelope.ReviewState.REJECTED);
        assertEquals("REJECTED", LabDtos.Field.from(rejected).reviewState());
    }

    private static FieldOccurrence occurrence(boolean sensitive) {
        return new FieldOccurrence(
                "employee.ssn", null, FieldStatus.FOUND, "TEXT", "123-45-6789", "123-45-6789",
                new NormalizedValue("123456789", new BigDecimal("123456789"),
                        LocalDate.parse("2026-01-31"), null),
                new SchemaRef(UUID.randomUUID(), "1"), "ANCHORED", "1.4.0",
                new BigDecimal("0.97"), null, "OK", sensitive,
                List.of(new EngineResultEnvelope.EvidenceSpan(UUID.randomUUID(), null, 7L,
                        "VALUE", 0, new EngineResultEnvelope.Box(BigDecimal.ONE, BigDecimal.ONE,
                                BigDecimal.ONE, BigDecimal.ONE))));
    }
}
