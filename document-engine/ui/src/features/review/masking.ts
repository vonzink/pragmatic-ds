/**
 * Client-side masking for values the extraction schema marks `sensitive` —
 * SSNs and license/account/loan/policy numbers as of Spec 3.
 *
 * ## Where the security boundary is (it is NOT here)
 *
 * **The server masks first.** Sensitive values are masked at the serializer
 * boundary (`platform/…/pii/MaskingSerializer`, shipped in Spec 1 Phase 7 and
 * proven by `ResponseMaskingIT`): `GET /v1/documents/{id}/fields`,
 * `GET /v1/packages/{id}/export`, and `GET /v1/documents/{id}/history` all
 * emit `•••-••-6789` / `••••1234`-style strings for sensitive fields, and no
 * unmask endpoint exists (Spec 3 design D3 — deliberately). The raw value
 * never reaches this client, the network tab, or a saved HAR.
 *
 * ## What this file is, then
 *
 * A presentation-layer default on top of that: a reviewer verifying a paystub
 * does not need even the masked tail on screen unprompted, so sensitive values
 * render as `•••• 6789` until deliberately revealed. The "Reveal" toggle can
 * only ever reveal the SERVER-masked string — revealing shows `•••-••-6789`,
 * never a raw value, by design.
 */

/** U+2022 BULLET. One glyph, so the mask width is predictable in any font. */
const BULLET = '•';

/** How many leading characters the mask replaces, regardless of the true length. */
const MASK_WIDTH = 4;

/** How many trailing characters survive. Four is the banking convention. */
const VISIBLE_TAIL = 4;

/** What a masked-but-empty value renders as. */
export const MASK_PLACEHOLDER = BULLET.repeat(MASK_WIDTH);

/**
 * Masks a sensitive value, keeping at most the last four characters.
 *
 * The mask is a FIXED four bullets rather than one-per-hidden-character: a
 * proportional mask leaks the value's length, which for an SSN or an account
 * number is a meaningful narrowing.
 *
 * ```
 * maskSensitive('123-45-6789')   === '•••• 6789'
 * maskSensitive('4111111111111') === '•••• 1111'
 * maskSensitive('1234')          === '••••'        // too short to spare a tail
 * maskSensitive('')              === '••••'
 * maskSensitive(null)            === '••••'
 * ```
 *
 * A value of four characters or fewer is masked ENTIRELY: revealing "the last
 * four" of a four-character value reveals the value.
 */
export function maskSensitive(value: string | null | undefined): string {
  const trimmed = value?.trim() ?? '';
  if (trimmed.length <= VISIBLE_TAIL) return MASK_PLACEHOLDER;
  return `${MASK_PLACEHOLDER} ${trimmed.slice(-VISIBLE_TAIL)}`;
}

/**
 * Masks anything a field might carry — including a normalised number or date,
 * which are just as identifying as the raw string and just as easy to forget.
 *
 * Returns `null` for a value that was already absent, so a caller can tell
 * "masked" apart from "not present" and render the missing-field state instead
 * of a row of bullets that implies a value exists.
 */
export function maskSensitiveValue(value: string | number | null | undefined): string | null {
  if (value === null || value === undefined) return null;
  return maskSensitive(String(value));
}
