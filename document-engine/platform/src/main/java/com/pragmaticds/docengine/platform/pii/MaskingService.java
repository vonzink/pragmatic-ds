package com.pragmaticds.docengine.platform.pii;

/**
 * The single home for the PII masking FORMAT (docs Phase 7 data-protection). Pure and static so it
 * is exhaustively unit-testable and callable from the {@link MaskingSerializer} without any Spring
 * wiring: the boundary that actually hides a sensitive value is the serializer, and this class is
 * only the shape of what it reveals.
 *
 * <p>Contract: reveal AT MOST the last four characters, mask the rest.
 *
 * <ul>
 *   <li>SSN-shaped (exactly nine digits, ignoring punctuation) &rarr; {@code •••-••-6789}
 *   <li>account/other (longer than four) &rarr; {@code ••••1234} (last four characters)
 *   <li>short (four or fewer) or empty &rarr; fully masked, revealing nothing
 *   <li>{@code null} &rarr; {@code null} (a null value carries no PII to hide)
 * </ul>
 */
public final class MaskingService {

    private MaskingService() {}

    private static final String BULLET = "•";

    /** Masks {@code raw} per the class contract; never reveals more than the last four characters. */
    public static String mask(String raw) {
        if (raw == null) {
            return null;
        }
        // SSN shape is judged on digits alone so "123-45-6789" and "123456789" mask identically.
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.length() == 9) {
            return BULLET.repeat(3) + "-" + BULLET.repeat(2) + "-" + digits.substring(5);
        }
        int length = raw.length();
        if (length <= 4) {
            // Revealing the last four of a value this short would reveal all of it.
            return BULLET.repeat(length);
        }
        return BULLET.repeat(4) + raw.substring(length - 4);
    }
}
