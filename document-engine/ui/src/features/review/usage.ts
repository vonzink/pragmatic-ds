/**
 * Formatting judgements for the usage summary, kept out of JSX for the same
 * reason `fields.ts` keeps its threshold out of JSX: a judgement in a template
 * is a judgement nobody can test.
 *
 * The one that matters is {@link formatUsd}. Everything else is cosmetic.
 */

/**
 * Elapsed, at a scale a human reads without counting digits.
 *
 * `null` is NOT zero. A stage with no `durationMs` never wrote one; printing
 * `0 ms` would claim a measurement that was never taken, which is the same
 * class of dishonesty as printing an invented dollar figure.
 */
export function formatElapsed(ms: number | null): string {
  if (ms === null) return 'not recorded';
  if (ms < 1000) return `${String(Math.round(ms))} ms`;
  if (ms < 60000) return `${(ms / 1000).toFixed(1)} s`;
  const minutes = Math.floor(ms / 60000);
  const seconds = Math.floor((ms % 60000) / 1000);
  return `${String(minutes)} m ${String(seconds).padStart(2, '0')} s`;
}

/**
 * Money, with sub-cent spend kept VISIBLE.
 *
 * Two decimal places is the obvious choice and the wrong one here. Per-document
 * model spend, when it arrives, will be fractions of a cent; `toFixed(2)` would
 * render every one of them as `$0.00` — indistinguishable from the zero that
 * means "no model was called". That distinction is the entire reason this block
 * exists, so a non-zero amount below a cent shows its real digits and only a
 * genuine zero shows `$0.00`.
 */
export function formatUsd(amount: number): string {
  if (amount === 0) return '$0.00';
  if (Math.abs(amount) < 0.01) {
    // Six places is the ledger's own scale (`ai_interpretation.cost_usd` is
    // numeric(10,6)), so this never invents precision the source lacks.
    return `$${amount.toFixed(6).replace(/0+$/, '')}`;
  }
  return `$${amount.toFixed(2)}`;
}

/**
 * The OCR share as a whole-number percentage, or null for an empty package.
 *
 * A share rather than a bare count because the count alone does not say what
 * kind of document this was: "26 OCR pages" out of 26 is a scanned return,
 * out of 200 it is a rounding error.
 */
export function ocrShare(ocrPages: number, totalPages: number): number | null {
  if (totalPages <= 0) return null;
  return Math.round((ocrPages / totalPages) * 100);
}
