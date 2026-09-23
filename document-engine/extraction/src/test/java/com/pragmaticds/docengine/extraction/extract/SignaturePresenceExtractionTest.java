package com.pragmaticds.docengine.extraction.extract;

import static org.assertj.core.api.Assertions.assertThat;

import com.pragmaticds.docengine.classification.match.Box;
import com.pragmaticds.docengine.classification.rules.AnchorKind;
import com.pragmaticds.docengine.extraction.schema.DataType;
import com.pragmaticds.docengine.extraction.schema.ExtractionMethod;
import com.pragmaticds.docengine.extraction.schema.ExtractorSpec;
import com.pragmaticds.docengine.extraction.schema.FieldSpec;
import com.pragmaticds.docengine.extraction.schema.LabelSpec;
import com.pragmaticds.docengine.extraction.schema.RegionSpec;
import com.pragmaticds.docengine.extraction.schema.SchemaDefinition;
import com.pragmaticds.docengine.extraction.schema.Window;
import com.pragmaticds.docengine.parsing.domain.LayoutElementType;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * SIGNATURE_PRESENCE: the region label locates the signature block; the window grows the label
 * box per side; any SIGNATURE detection overlapping the window means SIGNED, none means
 * UNSIGNED — a FOUND answer with label evidence only, the one legal empty-VALUE-evidence
 * outcome. A page without the region label fails the RUNG (D5): "could not find the signature
 * block" must never masquerade as "unsigned".
 */
class SignaturePresenceExtractionTest {

    private final DefaultFieldExtractionEngine engine = new DefaultFieldExtractionEngine();

    private static SpanRef span(long id, String text, String x, String y, String w, String h) {
        return span(id, text, x, y, w, h, "1");
    }

    private static SpanRef span(
            long id, String text, String x, String y, String w, String h, String confidence) {
        return new SpanRef(
                id,
                text,
                new Box(new BigDecimal(x), new BigDecimal(y), new BigDecimal(w), new BigDecimal(h)),
                new BigDecimal(confidence));
    }

    private static DetectionRef signature(
            UUID elementId, String x, String y, String w, String h, String confidence) {
        return new DetectionRef(
                elementId,
                LayoutElementType.SIGNATURE,
                new Box(new BigDecimal(x), new BigDecimal(y), new BigDecimal(w), new BigDecimal(h)),
                null,
                new BigDecimal(confidence));
    }

    private static PageContent page(List<SpanRef> spans, List<DetectionRef> detections) {
        return new PageContent(UUID.randomUUID(), 0, spans, List.of(), detections);
    }

    private static ExtractorSpec signatureRung(
            double strength, String label, double left, double right, double above, double below) {
        return new ExtractorSpec(
                ExtractionMethod.SIGNATURE_PRESENCE,
                strength,
                null,
                null,
                null,
                null,
                null,
                new RegionSpec(
                        new LabelSpec(AnchorKind.LITERAL, label),
                        new Window(left, right, above, below)));
    }

    /** The CONTRACTS example window: right 240, above 40, below 8. */
    private static ExtractorSpec buyerRung() {
        return signatureRung(0.9, "Buyer's Signature", 0.0, 240.0, 40.0, 8.0);
    }

    private static FieldSpec buyerSigned(ExtractorSpec... rungs) {
        return new FieldSpec("buyerSigned", DataType.ENUM, true, null, false, List.of(rungs));
    }

    private static SchemaDefinition schema(FieldSpec field) {
        return new SchemaDefinition("PURCHASE_CONTRACT", "1.0.0", List.of(field));
    }

    /** "Buyer's Signature" at y=400 — label union box [72,170]×[400,410]. */
    private static List<SpanRef> signatureBlockSpans() {
        return List.of(
                span(1, "Buyer's", "72.0", "400.0", "44.0", "10.0"),
                span(2, "Signature", "120.0", "400.0", "50.0", "10.0"));
    }

    @Test
    void a_signature_in_the_window_is_SIGNED_with_element_backed_evidence() {
        UUID inkId = UUID.randomUUID();
        // Window: x [72, 410], y [360, 418]. The strokes at (200, 375) sit inside it.
        PageContent content =
                page(
                        signatureBlockSpans(),
                        List.of(signature(inkId, "200.0", "375.0", "120.0", "28.0", "0.84")));
        FieldSpec field = buyerSigned(buyerRung());

        FieldOutcome outcome = engine.extract(schema(field), List.of(content)).get(0);

        assertThat(outcome.found()).isTrue();
        assertThat(outcome.method()).isEqualTo(ExtractionMethod.SIGNATURE_PRESENCE);
        assertThat(outcome.anchorStrength()).isEqualTo(0.9);
        assertThat(outcome.pageId()).isEqualTo(content.pageId());
        assertThat(outcome.displayedText()).isEqualTo("SIGNED");
        assertThat(outcome.rawValue()).isEqualTo("SIGNED");
        assertThat(outcome.normalized().text()).isEqualTo("SIGNED");
        assertThat(outcome.normalized().certainty()).isEqualByComparingTo("1");
        // VALUE evidence: the signature ELEMENT — box + element id, NO span.
        assertThat(outcome.valueEvidence()).hasSize(1);
        EvidenceRef value = outcome.valueEvidence().get(0);
        assertThat(value.spanId()).isNull();
        assertThat(value.layoutElementId()).isEqualTo(inkId);
        assertThat(value.box().x()).isEqualByComparingTo("200.0");
        assertThat(value.box().y()).isEqualByComparingTo("375.0");
        // LABEL evidence: the region label spans, always.
        assertThat(outcome.labelEvidence())
                .extracting(EvidenceRef::spanId)
                .containsExactly(1L, 2L);
        // Detection confidence rides the spanConfidence slot.
        assertThat(outcome.confidence().spanConfidence()).isEqualByComparingTo("0.84");
        assertThat(outcome.confidence().anchorStrength()).isEqualByComparingTo("0.9");
        assertThat(outcome.confidence().normalizerCertainty()).isEqualByComparingTo("1");
        assertThat(outcome.confidence().overall()).isEqualTo(new BigDecimal("0.7560"));
    }

    @Test
    void no_signature_detection_is_UNSIGNED_found_with_no_value_evidence() {
        // The OCR'd "Signature" word is the shakiest label span at 0.8: the absence claim is
        // exactly as good as the region localization.
        List<SpanRef> spans =
                List.of(
                        span(1, "Buyer's", "72.0", "400.0", "44.0", "10.0"),
                        span(2, "Signature", "120.0", "400.0", "50.0", "10.0", "0.8"));
        PageContent content = page(spans, List.of());
        FieldSpec field = buyerSigned(buyerRung());

        FieldOutcome outcome = engine.extract(schema(field), List.of(content)).get(0);

        assertThat(outcome.found()).as("UNSIGNED is an ANSWER, not an absence").isTrue();
        assertThat(outcome.method()).isEqualTo(ExtractionMethod.SIGNATURE_PRESENCE);
        assertThat(outcome.displayedText()).isEqualTo("UNSIGNED");
        assertThat(outcome.rawValue()).isEqualTo("UNSIGNED");
        assertThat(outcome.normalized().text()).isEqualTo("UNSIGNED");
        // The one legal empty-VALUE-evidence found outcome (FieldOutcome contract exception).
        assertThat(outcome.valueEvidence()).isEmpty();
        assertThat(outcome.labelEvidence())
                .extracting(EvidenceRef::spanId)
                .containsExactly(1L, 2L);
        assertThat(outcome.confidence().spanConfidence()).isEqualByComparingTo("0.8");
        assertThat(outcome.confidence().anchorStrength()).isEqualByComparingTo("0.9");
        assertThat(outcome.confidence().normalizerCertainty()).isEqualByComparingTo("1");
        assertThat(outcome.confidence().overall()).isEqualTo(new BigDecimal("0.7200"));
    }

    @Test
    void a_signature_outside_the_window_is_UNSIGNED() {
        // Ink exists on the page, but 90pt below the window's bottom edge (418): not this
        // block's signature.
        PageContent content =
                page(
                        signatureBlockSpans(),
                        List.of(
                                signature(
                                        UUID.randomUUID(),
                                        "200.0", "500.0", "120.0", "28.0", "0.84")));
        FieldSpec field = buyerSigned(buyerRung());

        FieldOutcome outcome = engine.extract(schema(field), List.of(content)).get(0);

        assertThat(outcome.displayedText()).isEqualTo("UNSIGNED");
        assertThat(outcome.valueEvidence()).isEmpty();
    }

    @Test
    void a_missing_region_anchor_fails_the_rung_not_unsigned() {
        // D5: the page has ink but NO "Buyer's Signature" label — this is "could not find the
        // signature block" (missing → MANUAL_REVIEW_REQUIRED), never "unsigned".
        List<SpanRef> spans = List.of(span(1, "Seller", "72.0", "400.0", "32.0", "10.0"));
        PageContent content =
                page(
                        spans,
                        List.of(
                                signature(
                                        UUID.randomUUID(),
                                        "200.0", "375.0", "120.0", "28.0", "0.84")));
        FieldSpec field = buyerSigned(buyerRung());

        FieldOutcome outcome = engine.extract(schema(field), List.of(content)).get(0);

        assertThat(outcome).isEqualTo(FieldOutcome.missing(field));
        assertThat(outcome.method()).isEqualTo(ExtractionMethod.NONE);
    }

    @Test
    void the_window_grows_from_the_label_box_per_side() {
        // Strokes at x [300, 360]: reachable ONLY through the 240pt rightward growth — the
        // zero-growth control window ([72,170]×[400,410]) misses them.
        DetectionRef farRight =
                signature(UUID.randomUUID(), "300.0", "402.0", "60.0", "6.0", "0.9");
        PageContent content = page(signatureBlockSpans(), List.of(farRight));

        FieldOutcome wide =
                engine.extract(
                                schema(
                                        buyerSigned(
                                                signatureRung(
                                                        0.9, "Buyer's Signature",
                                                        0.0, 240.0, 0.0, 0.0))),
                                List.of(content))
                        .get(0);
        FieldOutcome narrow =
                engine.extract(
                                schema(
                                        buyerSigned(
                                                signatureRung(
                                                        0.9, "Buyer's Signature",
                                                        0.0, 0.0, 0.0, 0.0))),
                                List.of(content))
                        .get(0);

        assertThat(wide.displayedText()).isEqualTo("SIGNED");
        assertThat(narrow.displayedText()).isEqualTo("UNSIGNED");
    }

    @Test
    void a_checkbox_detection_never_counts_as_a_signature() {
        DetectionRef impostor =
                new DetectionRef(
                        UUID.randomUUID(),
                        LayoutElementType.CHECKBOX,
                        new Box(
                                new BigDecimal("200.0"),
                                new BigDecimal("402.0"),
                                new BigDecimal("10.0"),
                                new BigDecimal("10.0")),
                        true,
                        new BigDecimal("0.99"));
        PageContent content = page(signatureBlockSpans(), List.of(impostor));
        FieldSpec field = buyerSigned(buyerRung());

        FieldOutcome outcome = engine.extract(schema(field), List.of(content)).get(0);

        assertThat(outcome.displayedText()).isEqualTo("UNSIGNED");
        assertThat(outcome.valueEvidence()).isEmpty();
    }

    @Test
    void the_first_intersecting_signature_in_reading_order_is_cited() {
        UUID firstId = UUID.randomUUID();
        PageContent content =
                page(
                        signatureBlockSpans(),
                        List.of(
                                signature(firstId, "180.0", "380.0", "60.0", "20.0", "0.7"),
                                signature(
                                        UUID.randomUUID(),
                                        "260.0", "380.0", "60.0", "20.0", "0.95")));
        FieldSpec field = buyerSigned(buyerRung());

        FieldOutcome outcome = engine.extract(schema(field), List.of(content)).get(0);

        assertThat(outcome.displayedText()).isEqualTo("SIGNED");
        assertThat(outcome.valueEvidence()).hasSize(1);
        assertThat(outcome.valueEvidence().get(0).layoutElementId()).isEqualTo(firstId);
        assertThat(outcome.confidence().spanConfidence()).isEqualByComparingTo("0.7");
    }
}
