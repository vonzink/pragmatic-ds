package com.pragmaticds.docengine.parsing.domain;

/**
 * The per-page text-layer verdict from {@code /v1/text} (docs/WORKER_CONTRACT.md): decides whether
 * OCR runs at all. NATIVE pages are NEVER sent to {@code /v1/ocr} — asserted by test, not assumed.
 */
public enum TextLayer {
    /** Usable text layer covers the page ink. */
    NATIVE,
    /** No meaningful text layer — the whole page goes to OCR. */
    SCANNED,
    /** Partial coverage (e.g. a native form with a pasted scan) — OCR runs on uncovered regions. */
    MIXED,
    /** Blank / no ink and no text. Also the pre-verdict placeholder at render time. */
    NONE
}
