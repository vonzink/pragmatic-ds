package com.pragmaticds.docengine.extraction.web;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * WHERE A CAPTURED VALUE'S CHARACTERS CAME FROM — a PDF's own text layer, or optical recognition.
 *
 * <p><b>Why a consumer needs it.</b> An OCR'd value and a native-text value render identically
 * today, and they do not deserve identical trust: one was read from bytes the document author
 * wrote, the other was guessed from pixels. A reviewer should look harder at the second, and a
 * downstream scorer should be free to weight it differently.
 *
 * <p><b>Why it is not a confidence component.</b> Extraction confidence is exactly three factors —
 * span · anchor · normalizer — and their product is the number. Provenance is a different KIND of
 * fact: it says which pipeline produced the characters, not how sure that pipeline was. Folding it
 * in would change a published formula and destroy the one thing the three components are for, which
 * is that a human can multiply them back. It travels beside confidence, never inside it.
 *
 * @param source {@code "NATIVE"} — every contributing span came from the PDF's text layer;
 *     {@code "OCR"} — every contributing span came from optical recognition; {@code "MIXED"} — the
 *     value's characters came from BOTH, which is the real case on a {@code MIXED} page where a
 *     native-text form carries an OCR'd handwritten or stamped region; {@code "UNKNOWN"} — no text
 *     span backs this value at all, which is what a MISSING occurrence and an element-only capture
 *     both look like. Never null: the whole point is that a consumer never has to guess, and an
 *     omitted key would be exactly the ambiguity {@code groupKind} was added to end.
 * @param ocrEngine the engine that produced the OCR characters — {@code "RAPIDOCR"} or
 *     {@code "TESSERACT"}, the two the worker's ladder can emit, joined with {@code "+"} in the
 *     rare case one value's spans were reconciled from both. Null exactly when no OCR span
 *     contributed, so it is null for every {@code NATIVE} and every {@code UNKNOWN} value and
 *     non-null for every {@code OCR} and {@code MIXED} one that named its engine.
 */
public record TextProvenanceView(String source, String ocrEngine) {

    /** Every contributing span came from the PDF's own text layer. */
    public static final String NATIVE = "NATIVE";

    /** Every contributing span came from optical recognition. */
    public static final String OCR = "OCR";

    /** The value's characters came from BOTH a text layer and OCR. */
    public static final String MIXED = "MIXED";

    /** No text span backs this value — a missing occurrence, or an element-only capture. */
    public static final String UNKNOWN = "UNKNOWN";

    /** The honest floor, shared by every path that has no span to look at. */
    public static final TextProvenanceView UNKNOWN_PROVENANCE = new TextProvenanceView(UNKNOWN, null);

    /**
     * THE ONE WORDING. {@code NATIVE} · {@code OCR RAPIDOCR} · {@code MIXED RAPIDOCR+TESSERACT} ·
     * {@code UNKNOWN} — the exact string the Markdown projection prints and the review UI's badge
     * shows, so the two surfaces cannot drift into telling different stories about one value.
     *
     * <p>{@code @JsonIgnore} because this is a rendering of the two components beside it, not a
     * third component: putting it on the wire would invite a consumer to parse the label instead of
     * reading {@link #source} and {@link #ocrEngine}, and then the label could never change.
     */
    @JsonIgnore
    public String label() {
        return ocrEngine == null ? source : source + " " + ocrEngine;
    }
}
