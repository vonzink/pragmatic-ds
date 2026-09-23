/**
 * Why does this document START here?
 *
 * The document list already says what a document is and how confident the machine was about its
 * TYPE. It said nothing about its EDGES — and a wrong edge is worse than a wrong type: a two-month
 * bank statement merged into one document produces one beginning balance where there should be
 * two, and every downstream reconciliation then fails on a document that was never mis-read, only
 * mis-cut.
 *
 * **This is not a confidence signal.** It sits beside `ConfidenceBadge`, never inside it.
 * Classification confidence is about the document's TYPE; how its boundary was decided is a
 * different kind of fact, and folding them together would let a proven boundary on a
 * low-confidence type look like a weak boundary.
 *
 * **Colour carries the scrutiny, not the identity** — the same grammar `ProvenanceChip` uses.
 * Inferred and unrecorded boundaries are amber because they are the ones worth checking; human,
 * form-header and package-start boundaries use the neutral slate every other non-actionable chip
 * uses.
 */

import { boundaryLabel, boundaryTitle, isInferredBoundary } from './boundary.ts';
import type { BoundaryProvenance } from '../../lib/api/types.ts';

export type BoundaryChipProps = {
  provenance: BoundaryProvenance | null | undefined;
  className?: string;
  testId?: string;
};

export default function BoundaryChip({
  provenance,
  className = '',
  testId = 'boundary-provenance',
}: BoundaryChipProps) {
  const inferred = isInferredBoundary(provenance);

  return (
    <span
      data-testid={testId}
      data-boundary={provenance ?? 'UNRECORDED'}
      data-inferred={inferred ? 'true' : 'false'}
      title={boundaryTitle(provenance)}
      className={`rounded-sm px-1.5 py-0.5 font-mono text-[11px] font-medium ${
        inferred ? 'bg-amber-100 text-amber-900' : 'bg-slate-100 text-slate-600'
      } ${className}`}
    >
      {boundaryLabel(provenance)}
    </span>
  );
}
