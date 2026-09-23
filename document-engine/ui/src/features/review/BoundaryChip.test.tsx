import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import BoundaryChip from './BoundaryChip.tsx';
import { boundaryLabel, isInferredBoundary, pageChipTitle } from './boundary.ts';
import type { BoundaryProvenance } from '../../lib/api/types.ts';

/**
 * The chip's job is to make an INFERRED boundary visibly different from a proven or human-placed
 * one. These assert that difference, the vocabulary, and — the part most likely to rot — that a
 * provenance value this UI has never heard of still renders as itself instead of disappearing.
 */
describe('BoundaryChip', () => {
  it('renders the reviewer-facing label, not the wire token', () => {
    render(<BoundaryChip provenance="TYPE_CHANGE" />);
    expect(screen.getByTestId('boundary-provenance')).toHaveTextContent('type change');
  });

  it('marks inferred boundaries and only those', () => {
    const inferred: BoundaryProvenance[] = ['TYPE_CHANGE', 'AI'];
    // INSTANCE_CHANGE sits with the settled ones: the engine READ two different periods off two
    // pages, which is proof of the same kind as a form header — arrived at from a value rather
    // than a label. Grouping it with the inferred ones would send a reviewer to re-check a cut
    // there is nothing to re-check about.
    const settled: BoundaryProvenance[] = ['HUMAN', 'RULE', 'PACKAGE_START', 'INSTANCE_CHANGE'];

    for (const provenance of inferred) {
      expect(isInferredBoundary(provenance), provenance).toBe(true);
    }
    for (const provenance of settled) {
      expect(isInferredBoundary(provenance), provenance).toBe(false);
    }
  });

  it('treats an unrecorded boundary as worth a look, not as nothing', () => {
    // A document split before V24 genuinely does not know. Rendering it neutral-and-blank would
    // read as "nothing special here", which is the assumption the chip exists to prevent.
    render(<BoundaryChip provenance={null} />);
    const chip = screen.getByTestId('boundary-provenance');
    expect(chip).toHaveTextContent('unrecorded');
    expect(chip.dataset.inferred).toBe('true');
    expect(chip.dataset.boundary).toBe('UNRECORDED');
  });

  it('names the next-statement cut in the reviewer\'s words', () => {
    render(<BoundaryChip provenance="INSTANCE_CHANGE" />);
    const chip = screen.getByTestId('boundary-provenance');
    expect(chip).toHaveTextContent('next statement');
    expect(chip.dataset.inferred).toBe('false');
  });

  it('carries the reason in an attribute as well as the colour', () => {
    // Nothing may be conveyed by hue alone: the label is the text, and the value is queryable.
    render(<BoundaryChip provenance="RULE" />);
    const chip = screen.getByTestId('boundary-provenance');
    expect(chip.dataset.boundary).toBe('RULE');
    expect(chip.dataset.inferred).toBe('false');
    expect(chip).toHaveTextContent('form header');
    expect(chip.title).toContain('proven');
  });

  it('renders a provenance the engine invents that this UI does not know', () => {
    // The union is open on purpose. A closed one would turn the engine's next boundary reason
    // into a blank chip in a reviewer's list — the silent-vanish the open arm exists to stop.
    expect(boundaryLabel('SUB_PAGE_REGION' as BoundaryProvenance)).toBe('sub page region');
    render(<BoundaryChip provenance={'SUB_PAGE_REGION' as BoundaryProvenance} />);
    expect(screen.getByTestId('boundary-provenance')).toHaveTextContent('sub page region');
  });
});

/**
 * The other half of Phase B: a sheet that two rule packs both claimed. The engine cannot cut
 * within a page, so this can never become a split — which is exactly why it has to become
 * VISIBLE. A licence photocopied beside a Social Security card is one of the most ordinary things
 * in a loan file, and until now the second document's words were captured, boxed, persisted, and
 * mentioned to nobody.
 */
describe('pageChipTitle', () => {
  const classification = {
    type: 'DRIVERS_LICENSE',
    confidence: 0.9,
    rulePackVersion: '1.0.0',
    coQualifyingTypes: [] as string[],
  };

  it('is the type and pack version on an ordinary page', () => {
    expect(pageChipTitle(classification)).toBe('DRIVERS_LICENSE · rule pack 1.0.0');
  });

  it('names the other document when two packs qualified on one sheet', () => {
    const title = pageChipTitle({
      ...classification,
      coQualifyingTypes: ['DRIVERS_LICENSE', 'SSN_CARD'],
    });
    expect(title).toContain('DRIVERS_LICENSE · rule pack 1.0.0');
    expect(title).toContain('SSN_CARD');
    // The limit is stated, not implied: a reviewer must not wait for a split that cannot come.
    expect(title).toContain('cannot split within a page');
  });

  it('says nothing extra when only one pack qualified', () => {
    // A single co-qualifying entry is not co-qualification — it is the winner listed once.
    expect(pageChipTitle({ ...classification, coQualifyingTypes: ['DRIVERS_LICENSE'] })).toBe(
      'DRIVERS_LICENSE · rule pack 1.0.0',
    );
  });

  it('reports an unclassified page as such rather than blank', () => {
    expect(pageChipTitle(null)).toBe('not classified');
  });
});

