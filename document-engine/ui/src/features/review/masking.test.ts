import { describe, expect, it } from 'vitest';
import { MASK_PLACEHOLDER, maskSensitive, maskSensitiveValue } from './masking.ts';

/*
 * Every value in this file is invented. There is no real SSN or account number
 * anywhere in this repository and the fixture generator cannot produce one —
 * see the provenance rules in docs/IMPLEMENTATION_PLAN.md.
 */

describe('maskSensitive', () => {
  it('keeps the last four characters and nothing else', () => {
    expect(maskSensitive('123-45-6789')).toBe('•••• 6789');
    expect(maskSensitive('000123456789')).toBe('•••• 6789');
  });

  it('masks a short value entirely, because "the last four" would be all of it', () => {
    expect(maskSensitive('1234')).toBe(MASK_PLACEHOLDER);
    expect(maskSensitive('12')).toBe(MASK_PLACEHOLDER);
  });

  it('masks absent and blank values rather than rendering an empty gap', () => {
    expect(maskSensitive(null)).toBe(MASK_PLACEHOLDER);
    expect(maskSensitive(undefined)).toBe(MASK_PLACEHOLDER);
    expect(maskSensitive('')).toBe(MASK_PLACEHOLDER);
    expect(maskSensitive('   ')).toBe(MASK_PLACEHOLDER);
  });

  it('does not leak length: two values of very different lengths mask identically', () => {
    // A proportional mask (one bullet per hidden character) would give an SSN
    // and a 16-digit card visibly different widths, which narrows both.
    expect(maskSensitive('11116789')).toBe(maskSensitive('1111111111111116789'));
  });

  it('never contains the original value', () => {
    const value = '987-65-4321';
    expect(maskSensitive(value)).not.toContain(value);
  });

  it('ignores surrounding whitespace when choosing the tail', () => {
    expect(maskSensitive('  123-45-6789  ')).toBe('•••• 6789');
  });
});

describe('maskSensitiveValue', () => {
  it('masks numbers as readily as strings — a normalised value is just as identifying', () => {
    expect(maskSensitiveValue(123456789)).toBe('•••• 6789');
  });

  it('distinguishes "masked" from "not present"', () => {
    // A row of bullets where there is no value at all would claim a value exists.
    expect(maskSensitiveValue(null)).toBeNull();
    expect(maskSensitiveValue(undefined)).toBeNull();
    expect(maskSensitiveValue('')).toBe(MASK_PLACEHOLDER);
  });
});
