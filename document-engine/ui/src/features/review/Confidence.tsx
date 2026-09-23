/**
 * Confidence, shown the same way wherever it appears — on a document's
 * classification and on every extracted field.
 *
 * The threshold that decides what counts as low lives in `fields.ts`, with the
 * reasoning attached to it.
 */

import { isLowConfidence } from './fields.ts';

export type ConfidenceBadgeProps = {
  confidence: number | null | undefined;
  /** Suppresses the flag styling where low confidence is not itself the story. */
  neutral?: boolean;
  className?: string;
};

/**
 * A percentage plus, when it is low, a visible marker.
 *
 * The marker is a triangle glyph and a word, not just a colour — same reasoning
 * as the evidence roles: colour alone does not survive a colour-vision
 * deficiency or a greyscale print of a screen.
 */
export default function ConfidenceBadge({
  confidence,
  neutral = false,
  className = '',
}: ConfidenceBadgeProps) {
  if (confidence === null || confidence === undefined) {
    return (
      <span data-testid="confidence" data-confidence="" className={`text-xs text-slate-400 ${className}`}>
        no score
      </span>
    );
  }

  const low = !neutral && isLowConfidence(confidence);
  const percent = `${String(Math.round(confidence * 100))}%`;

  return (
    <span
      data-testid="confidence"
      data-confidence={confidence}
      data-low={low ? 'true' : 'false'}
      title={low ? 'Below the review threshold' : undefined}
      className={`inline-flex items-center gap-1 rounded-sm px-1.5 py-0.5 text-xs tabular-nums ${
        low ? 'bg-amber-100 font-medium text-amber-900 ring-1 ring-amber-400' : 'bg-slate-100 text-slate-600'
      } ${className}`}
    >
      {low ? <span aria-hidden="true">▲</span> : null}
      {percent}
      {low ? <span className="sr-only"> — low confidence</span> : null}
    </span>
  );
}
