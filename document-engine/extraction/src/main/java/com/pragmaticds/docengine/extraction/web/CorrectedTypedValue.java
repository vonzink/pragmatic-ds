package com.pragmaticds.docengine.extraction.web;

import com.pragmaticds.docengine.extraction.extract.NormalizedValue;
import com.pragmaticds.docengine.extraction.extract.Normalizers;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Re-derives the TYPED value of a human-corrected field at read time, keyed by the field's data
 * type, so the read endpoints never present the machine's stale typed value next to a corrected
 * display.
 *
 * <p>A correction stores only the string a reviewer typed. The machine's Layer-2 normalized columns
 * still hold the pre-correction number/date — correct for {@code raw_value} (the machine capture is
 * preserved), but wrong for the effective typed arm a downstream LOS ingests. Without this, a
 * MONEY field corrected from "$3,565.87" to "8,888.88" would export {@code displayedText="8,888.88"}
 * beside {@code normalizedValue=3565.87}, and the consumer would book the pre-correction figure
 * (Phase 7b review finding).
 *
 * <p><b>Parsing is {@link Normalizers}' — not a second implementation of it.</b> This class used to
 * carry its own money and date parsers "in the same spirit as the extraction normalizers", and they
 * drifted: the local money parser rejected the accounting negative {@code (18,470)} that
 * {@code Normalizers} accepts, so a reviewer correcting a Schedule E loss by typing exactly what
 * the form prints got a NULL typed value in the export while the machine path would have produced
 * -18470. The local date parser resolved {@code yyyy} SMART where {@code Normalizers} is
 * {@code uuuu} STRICT (SMART silently clamps an impossible day rather than rejecting it) and knew
 * no two-digit-year format at all. A human's correction and the machine's capture must type
 * IDENTICALLY — they are read side by side, and the export cannot say the reviewer's own value is
 * unparseable while the machine's equivalent is not.
 *
 * <p>Best-effort by design, unchanged: a correction that does not parse for its type (a reviewer
 * typing "N/A" into a MONEY field) yields an all-null typed value, so the export emits {@code null}
 * rather than a wrong number — the {@code displayedText} and {@code reviewStatus=CORRECTED} still
 * carry the truth. What IS discarded is the normalizer's certainty: a correction's confidence is a
 * human decision, never a parse score, and the three machine confidence components stay the
 * machine's (consumer contract §4.2).
 */
record CorrectedTypedValue(String text, BigDecimal number, LocalDate date) {

    private static final CorrectedTypedValue EMPTY = new CorrectedTypedValue(null, null, null);

    static CorrectedTypedValue of(String dataType, String corrected) {
        if (corrected == null || corrected.isBlank() || dataType == null) {
            return EMPTY;
        }
        String value = corrected.strip();
        return switch (dataType) {
            case "MONEY", "NUMBER" -> normalized("money", value);
            case "DATE" -> normalized("date", value);
            // STRING, ENUM, and any unknown type: the corrected text is itself the typed value.
            default -> new CorrectedTypedValue(value, null, null);
        };
    }

    /** The single populated arm, for the export payload: number, else date-as-ISO, else text. */
    Object exportArm() {
        if (number != null) {
            return number;
        }
        if (date != null) {
            return date.toString();
        }
        return text;
    }

    /**
     * One normalizer, applied to the reviewer's string. {@code Normalizers} populates exactly one
     * arm per type and returns empty for anything it cannot parse, which is precisely this class's
     * best-effort contract — so the mapping is total and needs no per-type branch.
     */
    private static CorrectedTypedValue normalized(String normalizerName, String value) {
        Optional<NormalizedValue> parsed = Normalizers.normalize(normalizerName, value);
        return parsed.map(
                        normalizedValue ->
                                new CorrectedTypedValue(
                                        normalizedValue.text(),
                                        normalizedValue.number(),
                                        normalizedValue.date()))
                .orElse(EMPTY);
    }
}
