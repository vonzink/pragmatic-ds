import { describe, expect, it } from 'vitest';

import { formatElapsed, formatUsd, ocrShare } from './usage.ts';

describe('formatElapsed', () => {
  it('keeps sub-second work in milliseconds, because rounding it to 0s reads as "instant"', () => {
    expect(formatElapsed(0)).toBe('0 ms');
    expect(formatElapsed(940)).toBe('940 ms');
  });

  it('switches to seconds once there are seconds to show', () => {
    expect(formatElapsed(1000)).toBe('1.0 s');
    expect(formatElapsed(41230)).toBe('41.2 s');
  });

  it('switches to minutes past a minute — a 26-page scan is minutes, not 184000 ms', () => {
    expect(formatElapsed(60000)).toBe('1 m 00 s');
    expect(formatElapsed(184300)).toBe('3 m 04 s');
  });

  it('says "not recorded" rather than 0 when the number is genuinely absent', () => {
    // A null duration means the stage never wrote one. Printing 0 ms would claim a
    // measurement that was never taken — the same lie as an invented dollar figure.
    expect(formatElapsed(null)).toBe('not recorded');
  });
});

describe('formatUsd', () => {
  it('shows an exact zero as $0.00, not as a blank or a dash', () => {
    expect(formatUsd(0)).toBe('$0.00');
  });

  it('keeps sub-cent spend visible instead of rounding it away to $0.00', () => {
    // The moment Phase L lands, per-document spend will be fractions of a cent. Rounding
    // to two places would render every one of them as "$0.00" — indistinguishable from
    // the zero that means "no model was called", which is the one distinction this whole
    // block exists to preserve.
    expect(formatUsd(0.0045)).toBe('$0.0045');
    expect(formatUsd(0.000001)).toBe('$0.000001');
  });

  it('uses cents once there are cents', () => {
    expect(formatUsd(1.5)).toBe('$1.50');
    expect(formatUsd(12.345)).toBe('$12.35');
  });
});

describe('ocrShare', () => {
  it('is a whole-number percentage of the pages that went through OCR', () => {
    expect(ocrShare(26, 26)).toBe(100);
    expect(ocrShare(0, 4)).toBe(0);
    expect(ocrShare(1, 3)).toBe(33);
  });

  it('is null for a package with no pages, rather than a divide-by-zero NaN', () => {
    expect(ocrShare(0, 0)).toBeNull();
  });
});
