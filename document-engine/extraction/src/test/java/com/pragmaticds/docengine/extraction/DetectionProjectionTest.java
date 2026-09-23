package com.pragmaticds.docengine.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import com.pragmaticds.docengine.extraction.extract.DetectionRef;
import com.pragmaticds.docengine.parsing.domain.LayoutElement;
import com.pragmaticds.docengine.parsing.domain.LayoutElementType;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@code FieldExtractionService.projectDetections}: CHECKBOX/SIGNATURE elements become
 * {@link DetectionRef}s in element order; every other element type stays invisible to the
 * detector rungs; {@code checked} is parsed from the attributes JSON the same way
 * {@code toNode} parses {@code row}/{@code col}, and is null for signatures — and for a
 * checkbox whose attributes carry no verdict, which must project as unknown, not as a guess.
 *
 * <p>Entities built here are unpersisted, so {@code elementId} is null — the ids are asserted
 * end-to-end by the T6/T7 engine ITs against real rows.
 */
class DetectionProjectionTest {

    private static LayoutElement element(
            LayoutElementType type,
            String x,
            String y,
            String width,
            String height,
            String confidence,
            String attributes) {
        return new LayoutElement(
                UUID.randomUUID(),
                null,
                type,
                0,
                new BigDecimal(x),
                new BigDecimal(y),
                new BigDecimal(width),
                new BigDecimal(height),
                null,
                new BigDecimal(confidence),
                "checkbox-cv",
                "0.0.0-test",
                attributes);
    }

    private static LayoutElement checkbox(String attributes) {
        return element(
                LayoutElementType.CHECKBOX, "120.0", "200.0", "10.0", "10.0", "0.91", attributes);
    }

    @Test
    void only_checkbox_and_signature_elements_project() {
        List<DetectionRef> detections =
                FieldExtractionService.projectDetections(
                        List.of(
                                element(
                                        LayoutElementType.TABLE,
                                        "66.0", "168.0", "494.0", "110.0", "0.95",
                                        "{\"rows\": 2, \"cols\": 2}"),
                                element(
                                        LayoutElementType.PARAGRAPH,
                                        "72.0", "94.0", "200.0", "40.0", "0.90",
                                        null),
                                checkbox("{\"checked\": true, \"fillRatio\": 0.42}"),
                                element(
                                        LayoutElementType.SIGNATURE,
                                        "200.0", "375.0", "120.0", "28.0", "0.84",
                                        "{\"inkFraction\": 0.21}")));

        assertThat(detections).hasSize(2);
        assertThat(detections.get(0).type()).isEqualTo(LayoutElementType.CHECKBOX);
        assertThat(detections.get(1).type()).isEqualTo(LayoutElementType.SIGNATURE);
    }

    @Test
    void checked_state_parses_from_the_attributes_json() {
        List<DetectionRef> detections =
                FieldExtractionService.projectDetections(
                        List.of(
                                checkbox("{\"checked\": true, \"fillRatio\": 0.42}"),
                                checkbox("{\"checked\": false, \"fillRatio\": 0.02}")));

        assertThat(detections.get(0).checked()).isTrue();
        assertThat(detections.get(1).checked()).isFalse();
    }

    @Test
    void a_signature_has_no_checked_state() {
        List<DetectionRef> detections =
                FieldExtractionService.projectDetections(
                        List.of(
                                element(
                                        LayoutElementType.SIGNATURE,
                                        "200.0", "375.0", "120.0", "28.0", "0.84",
                                        "{\"inkFraction\": 0.21}")));

        assertThat(detections.get(0).checked()).isNull();
    }

    @Test
    void a_checkbox_missing_its_checked_attribute_projects_as_null_not_a_guess() {
        List<DetectionRef> detections =
                FieldExtractionService.projectDetections(List.of(checkbox("{}")));

        assertThat(detections.get(0).checked()).isNull();
    }

    @Test
    void box_and_confidence_carry_verbatim() {
        DetectionRef detection =
                FieldExtractionService.projectDetections(
                                List.of(
                                        element(
                                                LayoutElementType.CHECKBOX,
                                                "121.5", "203.7", "9.8", "10.2", "0.8700",
                                                "{\"checked\": true, \"fillRatio\": 0.42}")))
                        .get(0);

        assertThat(detection.box().x()).isEqualByComparingTo("121.5");
        assertThat(detection.box().y()).isEqualByComparingTo("203.7");
        assertThat(detection.box().width()).isEqualByComparingTo("9.8");
        assertThat(detection.box().height()).isEqualByComparingTo("10.2");
        assertThat(detection.confidence()).isEqualByComparingTo("0.87");
    }
}
