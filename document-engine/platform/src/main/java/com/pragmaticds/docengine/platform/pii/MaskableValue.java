package com.pragmaticds.docengine.platform.pii;

/**
 * A field value paired with whether it is sensitive, carried in place of a raw {@code String}/{@code
 * Object} on every response DTO that emits a field value. The wrapping is the control: a value can
 * only reach the wire through {@link MaskingSerializer}, so a sensitive value is masked centrally
 * and no controller ever has to remember to call {@code mask()}. A future response DTO that forgets
 * to wrap a value is caught by the ArchUnit rule, not by a leak in production.
 *
 * <p>{@code raw} is an {@code Object}, not a {@code String}, on purpose: a non-sensitive value keeps
 * its natural JSON type (a normalized number stays a JSON number, a date-string a string), so
 * wrapping changes the wire shape for exactly nothing until a field is marked sensitive.
 */
public record MaskableValue(Object raw, boolean sensitive) {

    /** The value {@code raw} with its sensitivity; {@code raw} may be null (a null value has no PII). */
    public static MaskableValue of(Object raw, boolean sensitive) {
        return new MaskableValue(raw, sensitive);
    }
}
