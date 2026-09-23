/**
 * Was this value READ, or was it RECOGNISED?
 *
 * The product owner's question, made visible: "if OCR is used can we identify
 * that in the findings — a label that shows parsed vs OCR." Until now a number
 * lifted from a PDF's own text layer and a number guessed from pixels rendered
 * identically, which quietly asked a reviewer to extend the same trust to both.
 *
 * **Per VALUE, not per page.** `PageViewer` shows the page's `textLayer` beside
 * the page number, and that is a genuinely different fact: on a `MIXED` page the
 * page-level verdict is `MIXED` for every field on it, including the ones read
 * entirely from the text layer. Only this badge can be right about one value.
 *
 * **Colour carries the scrutiny, not the identity.** `OCR` and `MIXED` are amber
 * because they are the ones worth a second look; `NATIVE` and `UNKNOWN` use the
 * neutral slate every other non-actionable chip uses — the same `StatusChip`
 * grammar, monospaced to sit beside `extractionMethod`. The colour is redundant
 * with the text on purpose, so nothing is conveyed by hue alone.
 *
 * **This is not a confidence signal.** It sits beside `ConfidenceBadge`, never
 * inside it. Extraction confidence is three components whose product is the
 * score; where the characters came from is a different kind of fact, and folding
 * it in would break a published formula a reviewer is meant to multiply back.
 */

import { isRecognised, provenanceLabel, provenanceTitle } from './provenance.ts';
import type { TextProvenanceView } from '../../lib/api/types.ts';

export type ProvenanceChipProps = {
  provenance: TextProvenanceView | null | undefined;
  className?: string;
  testId?: string;
};

export default function ProvenanceChip({
  provenance,
  className = '',
  testId = 'text-provenance',
}: ProvenanceChipProps) {
  const source = provenance?.source ?? 'UNKNOWN';
  const recognised = isRecognised(provenance);

  return (
    <span
      data-testid={testId}
      data-provenance={source}
      data-ocr-engine={provenance?.ocrEngine ?? ''}
      data-recognised={recognised ? 'true' : 'false'}
      title={provenanceTitle(provenance)}
      className={`rounded-sm px-1.5 py-0.5 font-mono text-[11px] font-medium ${
        recognised ? 'bg-amber-100 text-amber-900' : 'bg-slate-100 text-slate-600'
      } ${className}`}
    >
      {provenanceLabel(provenance)}
    </span>
  );
}
