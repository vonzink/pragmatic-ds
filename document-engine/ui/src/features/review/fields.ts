/**
 * The two judgements the field pane makes about a field, kept out of the
 * components that render them.
 *
 * Separate module partly for fast refresh (a `.tsx` that exports non-components
 * loses it) and partly because both are arguable: one is a definition the
 * engine owns, the other is a threshold the UI owns, and neither should be
 * buried inside JSX where nobody will find it to argue with.
 */

import type { FieldView } from '../../lib/api/types.ts';

/**
 * A field the engine looked for and did not find.
 *
 * `extractionMethod === 'NONE'` is the definitional marker —
 * `FieldExtractionService` writes it precisely when nothing matched. Confidence
 * 0 and `MANUAL_REVIEW_REQUIRED` accompany it, but they are consequences;
 * keying on the cause means a future validation rule that sets
 * MANUAL_REVIEW_REQUIRED on a field that *was* found does not accidentally make
 * it read as missing.
 */
export function isMissingField(field: FieldView): boolean {
  return field.extractionMethod === 'NONE';
}

/**
 * Below this, a value is flagged for a second look.
 *
 * ⚠️ **A display heuristic owned by the UI, not a decision borrowed from the
 * engine.** The engine does not currently publish a "this is low" line for
 * extraction; `validationStatus` is the authoritative signal it does publish.
 * When validation rules put a real threshold on the wire, this constant should
 * be deleted rather than tuned.
 *
 * Erring low is deliberate: flagging a good field costs a reviewer a glance,
 * missing a bad one costs a wrong number in a loan file.
 */
export const LOW_CONFIDENCE_THRESHOLD = 0.75;

export function isLowConfidence(confidence: number | null | undefined): boolean {
  return confidence !== null && confidence !== undefined && confidence < LOW_CONFIDENCE_THRESHOLD;
}
