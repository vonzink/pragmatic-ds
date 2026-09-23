/**
 * Was this value READ, or was it RECOGNISED? — the wording, in one place.
 *
 * **The label is the server's own, verbatim.** `NATIVE`, `OCR RAPIDOCR`,
 * `MIXED RAPIDOCR+TESSERACT`, `UNKNOWN` — the exact strings
 * `TextProvenanceView.label()` prints into the `fields.md` projection. A
 * reviewer reading the rendering and a reviewer reading the screen must not
 * have to translate between two vocabularies for one fact, so the rule is
 * stated once per language and pinned against the other side in
 * `ProvenanceChip.test.tsx`.
 *
 * Separate from `ProvenanceChip.tsx` because these are functions, not
 * components — the same split `fields.ts` and `masking.ts` already make.
 */

import type { TextProvenanceView } from '../../lib/api/types.ts';

/**
 * The one wording — mirrors `TextProvenanceView.label()` on the server.
 *
 * A null or absent provenance renders `UNKNOWN` rather than an empty string:
 * a blank badge would read as "native", which is the assumption this label
 * exists to stop anyone making.
 */
export function provenanceLabel(provenance: TextProvenanceView | null | undefined): string {
  if (!provenance) return 'UNKNOWN';
  return provenance.ocrEngine ? `${provenance.source} ${provenance.ocrEngine}` : provenance.source;
}

/**
 * True when ANY of the value's characters were recognised rather than read —
 * so `MIXED` counts. It drives the badge's amber, and the rule is deliberately
 * "any", not "mostly": one guessed span in a value is a reason to look.
 */
export function isRecognised(provenance: TextProvenanceView | null | undefined): boolean {
  return provenance?.source === 'OCR' || provenance?.source === 'MIXED';
}

/** The longer sentence, for the badge's tooltip and its accessible description. */
export function provenanceTitle(provenance: TextProvenanceView | null | undefined): string {
  const source = provenance?.source ?? 'UNKNOWN';
  const engine = provenance?.ocrEngine ?? 'OCR';
  if (source === 'NATIVE') return "Read from the document's own text layer";
  if (source === 'OCR') return `Recognised from the page image by ${engine} — check this value`;
  if (source === 'MIXED') {
    return `Partly read from the text layer, partly recognised by ${engine} — check this value`;
  }
  return 'No text span backs this value, so its source cannot be stated';
}
