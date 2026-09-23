/**
 * How a field's parts are turned into strings, in one place so the flat rows,
 * the cluster cells and the detail strip cannot disagree about them.
 *
 * Deliberately a `.ts` and not a `.tsx`: these are functions, and a module that
 * mixes them with components loses fast refresh for the components.
 */

import type { ConfidenceComponentsView, FieldView } from '../../lib/api/types.ts';

/** `payPeriodStart` -> `Pay Period Start`. Presentation only; the wire name is shown too. */
export function humaniseFieldName(fieldName: string): string {
  return fieldName
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
    .replace(/^./, (character) => character.toUpperCase());
}

/** The normalised value, whichever arm the data type populated. */
export function normalisedDisplay(field: FieldView): string | null {
  const normalized = field.normalized;
  if (!normalized) return null;
  if (normalized.text !== null) return normalized.text;
  if (normalized.number !== null) return String(normalized.number);
  if (normalized.date !== null) return normalized.date;
  return null;
}

/**
 * Confidence at the scale it is stored: `numeric(5,4)`, so `0.81` reads
 * `0.8100`.
 *
 * Padded from the value's own decimal representation rather than re-rounded.
 * `toFixed` would re-derive the digits from the binary double, which is a
 * rounding decision this function has no business making about a number the
 * server already decided; padding cannot change a digit. The fallback exists
 * only for a value that arrives outside the stored scale — at which point
 * something upstream is wrong and rounding is the least of it.
 */
export function scale4(value: number): string {
  const text = String(value);
  if (!/^-?\d+(?:\.\d{1,4})?$/.test(text)) return value.toFixed(4);
  const [whole, fraction = ''] = text.split('.');
  return `${whole}.${fraction.padEnd(4, '0')}`;
}

/**
 * `1 · 0.9 · 0.9` — span · anchor · normalizer, the three inputs whose product
 * is the confidence.
 *
 * Rendered as stored, NOT re-multiplied and NOT padded to a common scale: the
 * three are not same-scale decimals, and a UI that multiplied them in floating
 * point would print a product that disagrees with the server's `numeric(5,4)`
 * in the last digit for no reason a reviewer could ever diagnose.
 */
export function confidenceComponentsText(
  components: ConfidenceComponentsView | null,
): string | null {
  if (!components) return null;
  const { spanConfidence, anchorStrength, normalizerCertainty } = components;
  if (spanConfidence === null || anchorStrength === null || normalizerCertainty === null) {
    return null;
  }
  return [spanConfidence, anchorStrength, normalizerCertainty].map(String).join(' · ');
}

/** A sentence describing the evidence chain, for anyone not looking at the boxes. */
export function evidenceSummary(field: FieldView): string {
  if (field.evidence.length === 0) return 'No evidence — nothing on the page to point at.';
  const parts: string[] = [];
  for (const role of ['VALUE', 'LABEL', 'CONTEXT'] as const) {
    const forRole = field.evidence.filter((item) => item.role === role);
    if (forRole.length === 0) continue;
    const pages = [...new Set(forRole.map((item) => item.packagePageIndex + 1))].sort(
      (a, b) => a - b,
    );
    parts.push(
      `${role.toLowerCase()}: ${String(forRole.length)} on page ${pages.map(String).join(', ')}`,
    );
  }
  return parts.join(' · ');
}
