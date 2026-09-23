import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import EvidenceOverlay from './EvidenceOverlay.tsx';
import { pdfBoxToViewport, type PageGeometry } from './coordinates.ts';
import {
  BORROWER_NAME,
  PAGE_1_ID,
  PAGE_2_ID,
  PAGE_HEIGHT_PT,
  PAGE_WIDTH_PT,
  PAY_FREQUENCY,
} from '../../test/fixtures.ts';
import type { EvidenceView } from '../../lib/api/types.ts';

/*
 * `coordinates.ts` is NOT mocked anywhere in this file. Its correctness is the
 * acceptance criterion — "zoom and page rotation keep the overlay aligned" —
 * so the expectations below CALL it rather than restating what it should
 * return. What is being tested here is that the overlay hands it the right
 * inputs and puts its output in the right CSS property; the arithmetic itself
 * is proved in coordinates.test.ts against hand-derived numbers.
 */

const LETTER: PageGeometry = {
  widthPt: PAGE_WIDTH_PT,
  heightPt: PAGE_HEIGHT_PT,
  rotation: 0,
};

/** Reads a rendered box's inline style back as numbers. */
function positionOf(element: HTMLElement) {
  return {
    left: Number.parseFloat(element.style.left),
    top: Number.parseFloat(element.style.top),
    width: Number.parseFloat(element.style.width),
    height: Number.parseFloat(element.style.height),
  };
}

function expectAgrees(
  element: HTMLElement,
  box: EvidenceView,
  page: PageGeometry,
  scale: number,
  extraRotation = 0,
) {
  const expected = pdfBoxToViewport(box, page, scale, extraRotation);
  const actual = positionOf(element);
  // 1 decimal: the DOM value is rounded to 0.01px, far inside this tolerance.
  expect(actual.left).toBeCloseTo(expected.left, 1);
  expect(actual.top).toBeCloseTo(expected.top, 1);
  expect(actual.width).toBeCloseTo(expected.width, 1);
  expect(actual.height).toBeCloseTo(expected.height, 1);
}

describe('EvidenceOverlay', () => {
  it('renders one box per piece of evidence on the visible page', () => {
    render(
      <EvidenceOverlay
        evidence={BORROWER_NAME.evidence}
        pageId={PAGE_1_ID}
        page={LETTER}
        scale={1}
        occurrence={BORROWER_NAME.fieldName}
      />,
    );

    expect(screen.getAllByTestId('evidence-box')).toHaveLength(4);
  });

  it('tags every box with the occurrence it belongs to, not merely the field name', () => {
    render(
      <EvidenceOverlay
        evidence={BORROWER_NAME.evidence}
        pageId={PAGE_1_ID}
        page={LETTER}
        scale={1}
        occurrence="rentsReceived#B"
      />,
    );

    // The coordinate, not the name: three occurrences of one repeating field
    // would otherwise tag their boxes identically, and "only B's boxes are
    // drawn" would be an unassertable claim.
    const tagged = screen.getAllByTestId('evidence-box');
    expect(tagged.every((box) => box.dataset.occurrence === 'rentsReceived#B')).toBe(true);
  });

  it('distinguishes VALUE from LABEL in the DOM, not only in colour', () => {
    render(
      <EvidenceOverlay evidence={BORROWER_NAME.evidence} pageId={PAGE_1_ID} page={LETTER} scale={1} />,
    );

    const boxes = screen.getAllByTestId('evidence-box');
    const roles = boxes.map((box) => box.dataset.role);
    expect(roles.filter((role) => role === 'VALUE')).toHaveLength(3);
    expect(roles.filter((role) => role === 'LABEL')).toHaveLength(1);

    // Distinguishable without reading a colour: different border style, and a
    // visible word naming the role.
    const value = boxes.find((box) => box.dataset.role === 'VALUE');
    const label = boxes.find((box) => box.dataset.role === 'LABEL');
    expect(value?.className).toContain('border-solid');
    expect(label?.className).toContain('border-dashed');
    expect(value?.textContent).toBe('Value');
    expect(label?.textContent).toBe('Label');
  });

  it('draws nothing for evidence that belongs to another page', () => {
    // payFrequency's evidence is entirely on page 2 of the twopage fixture.
    render(
      <EvidenceOverlay evidence={PAY_FREQUENCY.evidence} pageId={PAGE_1_ID} page={LETTER} scale={1} />,
    );

    expect(screen.queryAllByTestId('evidence-box')).toHaveLength(0);
  });

  it('draws that same evidence once the right page is showing', () => {
    render(
      <EvidenceOverlay evidence={PAY_FREQUENCY.evidence} pageId={PAGE_2_ID} page={LETTER} scale={1} />,
    );

    expect(screen.getAllByTestId('evidence-box')).toHaveLength(3);
  });

  it.each([1, 2.5])('agrees with pdfBoxToViewport at scale %s', (scale) => {
    render(
      <EvidenceOverlay
        evidence={BORROWER_NAME.evidence}
        pageId={PAGE_1_ID}
        page={LETTER}
        scale={scale}
      />,
    );

    const boxes = screen.getAllByTestId('evidence-box');
    BORROWER_NAME.evidence.forEach((item, index) => {
      expectAgrees(boxes[index], item, LETTER, scale);
    });
  });

  it('scales linearly between zoom levels, so a doubled zoom doubles the box', () => {
    const single = render(
      <EvidenceOverlay evidence={[BORROWER_NAME.evidence[0]]} pageId={PAGE_1_ID} page={LETTER} scale={1} />,
    );
    const atOne = positionOf(single.getAllByTestId('evidence-box')[0]);
    single.unmount();

    const doubled = render(
      <EvidenceOverlay evidence={[BORROWER_NAME.evidence[0]]} pageId={PAGE_1_ID} page={LETTER} scale={2} />,
    );
    const atTwo = positionOf(doubled.getAllByTestId('evidence-box')[0]);

    expect(atTwo.left).toBeCloseTo(atOne.left * 2, 1);
    expect(atTwo.top).toBeCloseTo(atOne.top * 2, 1);
    expect(atTwo.width).toBeCloseTo(atOne.width * 2, 1);
  });

  it.each([0, 90, 180, 270])('agrees with pdfBoxToViewport at %s degrees of extra rotation', (rotation) => {
    render(
      <EvidenceOverlay
        evidence={[BORROWER_NAME.evidence[0]]}
        pageId={PAGE_1_ID}
        page={LETTER}
        scale={1.5}
        extraRotation={rotation}
      />,
    );

    expectAgrees(
      screen.getAllByTestId('evidence-box')[0],
      BORROWER_NAME.evidence[0],
      LETTER,
      1.5,
      rotation,
    );
  });

  it('honours a page that is already rotated, on top of the reviewer’s turn', () => {
    const rotatedPage: PageGeometry = { ...LETTER, rotation: 90 };
    render(
      <EvidenceOverlay
        evidence={[BORROWER_NAME.evidence[0]]}
        pageId={PAGE_1_ID}
        page={rotatedPage}
        scale={1}
        extraRotation={90}
      />,
    );

    // Total 180 — the point of routing through coordinates.ts rather than
    // reading `rotation` off the page and forgetting the reviewer's turn.
    expectAgrees(
      screen.getAllByTestId('evidence-box')[0],
      BORROWER_NAME.evidence[0],
      rotatedPage,
      1,
      90,
    );
  });

  it('says so loudly when a page row cannot describe a real page', () => {
    // A silent empty overlay is the one failure this subsystem must never have.
    render(
      <EvidenceOverlay
        evidence={BORROWER_NAME.evidence}
        pageId={PAGE_1_ID}
        page={{ widthPt: 612, heightPt: 792, rotation: 45 }}
        scale={1}
      />,
    );

    expect(screen.getByTestId('evidence-overlay-error')).toBeInTheDocument();
    expect(screen.queryAllByTestId('evidence-box')).toHaveLength(0);
  });
});
