/**
 * Was this document's boundary PROVEN, inferred, or placed by a person? — the wording, in one
 * place.
 *
 * The splitter decides where one document ends and the next begins, and until now the document
 * list showed the result without the reason. Those reasons are not equally trustworthy: a page
 * matching a pack anchor that declares a form header is proof, a page whose classified type merely
 * differed from its neighbour's is an inference that cannot see a seam between two documents of
 * the SAME type, and a boundary a reviewer placed outranks both. A reviewer triaging a package
 * wants the inferred ones first; showing all four identically hides exactly the ones worth
 * checking.
 *
 * Separate from `BoundaryChip.tsx` because these are functions, not components — the same split
 * `provenance.ts` and `fields.ts` already make.
 */

import type { BoundaryProvenance, PageClassificationView } from '../../lib/api/types.ts';

/**
 * The label, in the reviewer's vocabulary rather than the wire's. `TYPE_CHANGE` is the one worth
 * translating: it means "the page type changed here", which is the thing a reviewer can check
 * against the document, where the raw token reads like an event that happened to the document.
 *
 * A null provenance renders `unrecorded`, never an empty string or a guess: documents split before
 * the engine recorded provenance genuinely do not know, and a blank chip would read as "nothing
 * special here" — which is the assumption this chip exists to stop anyone making.
 */
export function boundaryLabel(provenance: BoundaryProvenance | null | undefined): string {
  switch (provenance) {
    case 'HUMAN':
      return 'human';
    case 'RULE':
      return 'form header';
    case 'PACKAGE_START':
      return 'package start';
    case 'INSTANCE_CHANGE':
      return 'next statement';
    case 'TYPE_CHANGE':
      return 'type change';
    case 'AI':
      return 'ai';
    case null:
    case undefined:
      return 'unrecorded';
    default:
      // An engine that invents a sixth reason must render as itself, not vanish. The union is
      // open for exactly this.
      return provenance.toLowerCase().replace(/_/g, ' ');
  }
}

/** The hover text: what the label means, and what it does NOT tell you. */
export function boundaryTitle(provenance: BoundaryProvenance | null | undefined): string {
  switch (provenance) {
    case 'HUMAN':
      return 'A reviewer placed or reshaped this document. The machine never re-guesses it.';
    case 'RULE':
      return 'A page matched a rule-pack anchor declaring a form header — the boundary is proven.';
    case 'PACKAGE_START':
      return 'This document opens the package. Its start was not inferred from anything.';
    case 'INSTANCE_CHANGE':
      return "The document's own printed identity — a bank statement's period — changed here, so the next one begins. Deterministic: read from the page, not inferred.";
    case 'TYPE_CHANGE':
      return 'The page type changed here. Inferred: this cannot see a seam between two documents of the same type.';
    case 'AI':
      return 'Proposed by boundary extraction and anchored to text on the page. Inferred — worth checking.';
    case null:
    case undefined:
      return 'This document was split before the engine recorded how boundaries were decided.';
    default:
      return `Boundary reason reported by the engine: ${provenance}.`;
  }
}

/**
 * Does this boundary deserve a second look?
 *
 * TRUE for the two inferred reasons and for `unrecorded`. FALSE for a human's decision, a proven
 * form header, and the package's own first document — none of which a reviewer can improve on by
 * staring at them. This drives the chip's colour, and the colour is redundant with the text on
 * purpose, so nothing is conveyed by hue alone.
 */
export function isInferredBoundary(provenance: BoundaryProvenance | null | undefined): boolean {
  // INSTANCE_CHANGE is settled, not inferred: the engine READ two different periods off two
  // pages. It is proof of the same kind as a form header, arrived at from a value instead of a
  // label, and a reviewer has nothing to improve by re-checking it.
  return (
    provenance !== 'HUMAN' &&
    provenance !== 'RULE' &&
    provenance !== 'PACKAGE_START' &&
    provenance !== 'INSTANCE_CHANGE'
  );
}

/**
 * The page chip's hover text — and, when two rule packs both qualified on one sheet, the SAY-SO.
 *
 * A page carrying a licence photocopied beside a Social Security card belongs to exactly one
 * document, because nothing in this engine can cut within a page. The second document's words are
 * captured, boxed and persisted, and until now nothing told a reviewer to look. Naming the
 * co-qualifying types here is the difference between a known limit and a silent loss.
 */
export function pageChipTitle(classification: PageClassificationView | null | undefined): string {
  if (!classification) return 'not classified';
  const base = `${classification.type} · rule pack ${classification.rulePackVersion}`;
  if (classification.coQualifyingTypes.length > 1) {
    return `${base}\n⚠ this sheet also matches ${classification.coQualifyingTypes.join(
      ', ',
    )} — it may carry more than one document, which the engine cannot split within a page`;
  }
  return base;
}
