import { describe, expect, it } from 'vitest';
import {
  CoordinateError,
  normalizeRotation,
  pdfBoxToViewport,
  totalRotation,
  viewportBoxToPdf,
  viewportSize,
  type PageGeometry,
  type PdfBox,
  type Rotation,
} from './coordinates.ts';

/*
 * Every expected number in this file was derived by hand from the geometry,
 * never captured from a run of the implementation. Phase 2's as-built notes
 * record why: a test that encodes whatever the code happens to emit proves
 * only that the code is deterministic.
 *
 * The derivation, once, for a W0 x H0 page and a rotation-0 top-left box
 * (x, y, w, h). A clockwise turn of R maps a point (x, y) to:
 *
 *     R = 0    (x,      y)
 *     R = 90   (H0 - y, x)
 *     R = 180  (W0 - x, H0 - y)
 *     R = 270  (y,      W0 - x)
 *
 * Feeding both corners through and taking min/max gives the box:
 *
 *     R = 0    left = x            top = y            w x h
 *     R = 90   left = H0 - y - h   top = x            h x w
 *     R = 180  left = W0 - x - w   top = H0 - y - h   w x h
 *     R = 270  left = y            top = W0 - x - w   h x w
 *
 * then everything is multiplied by `scale`. This matches, term for term, the
 * transform pdf.js builds in PageViewport for the same rotation, and the
 * clockwise quarter-turn the worker applies in geometry.rotate_box.
 */

/** US Letter, the size every fixture page is generated at. */
const W0 = 612;
const H0 = 792;
const LETTER: PageGeometry = { widthPt: W0, heightPt: H0, rotation: 0 };

/**
 * The real thing: fixtures/truth/paystub_complete.json, field `netPay`,
 * valueWords[0] — the word "$3,565.87" on page 0.
 */
const NET_PAY: PdfBox = { x: 124.7, y: 343.4, width: 53.4, height: 11.1 };

// Hand-arithmetic on NET_PAY against a 612 x 792 page:
//   x + w              = 124.7 + 53.4 = 178.1
//   y + h              = 343.4 + 11.1 = 354.5
//   H0 - y - h         = 792 - 354.5  = 437.5
//   W0 - x - w         = 612 - 178.1  = 433.9

/** Compares to 1e-6, which is far below the 0.1pt the API stores. */
function expectBox(actual: { left: number; top: number; width: number; height: number }, expected: {
  left: number;
  top: number;
  width: number;
  height: number;
}): void {
  expect(actual.left).toBeCloseTo(expected.left, 6);
  expect(actual.top).toBeCloseTo(expected.top, 6);
  expect(actual.width).toBeCloseTo(expected.width, 6);
  expect(actual.height).toBeCloseTo(expected.height, 6);
}

const SCALES = [0.5, 1, 1.5, 2, 4] as const;
const ROTATIONS: Rotation[] = [0, 90, 180, 270];

describe('normalizeRotation', () => {
  it.each([
    [0, 0],
    [90, 90],
    [180, 180],
    [270, 270],
    [360, 0],
    [450, 90],
    [-90, 270],
    [-180, 180],
    [-270, 90],
    [-360, 0],
    [720, 0],
  ])('reduces %i to %i', (input, expected) => {
    expect(normalizeRotation(input)).toBe(expected);
  });

  it.each([45, 1, -45, 89, 91, 0.5, 90.0001, Number.NaN, Number.POSITIVE_INFINITY])(
    'rejects %p as not a quarter turn',
    (input) => {
      expect(() => normalizeRotation(input)).toThrow(CoordinateError);
    },
  );

  it('names the offending value in the message', () => {
    expect(() => normalizeRotation(45)).toThrow(/multiple of 90 degrees, got 45/);
  });
});

describe('totalRotation', () => {
  it('adds the reviewer rotation to the page rotation', () => {
    expect(totalRotation({ ...LETTER, rotation: 90 }, 90)).toBe(180);
    expect(totalRotation({ ...LETTER, rotation: 270 }, 90)).toBe(0);
    expect(totalRotation({ ...LETTER, rotation: 180 }, 270)).toBe(90);
    expect(totalRotation({ ...LETTER, rotation: 0 }, -90)).toBe(270);
  });

  it('defaults extraRotation to zero', () => {
    expect(totalRotation({ ...LETTER, rotation: 270 })).toBe(270);
  });

  it('rejects a malformed page rotation even when extraRotation would cancel it', () => {
    expect(() => totalRotation({ ...LETTER, rotation: 45 }, 45)).toThrow(CoordinateError);
  });

  it('rejects a malformed extraRotation', () => {
    expect(() => totalRotation(LETTER, 30)).toThrow(CoordinateError);
  });
});

describe('viewportSize', () => {
  it('is the page size at rotation 0, scale 1', () => {
    expect(viewportSize(LETTER, 1)).toEqual({ width: 612, height: 792 });
  });

  it.each(SCALES)('scales both axes linearly at scale %p', (scale) => {
    expect(viewportSize(LETTER, scale)).toEqual({ width: 612 * scale, height: 792 * scale });
  });

  it('swaps the axes at 90 and 270 and leaves them at 0 and 180', () => {
    expect(viewportSize({ ...LETTER, rotation: 0 }, 1)).toEqual({ width: 612, height: 792 });
    expect(viewportSize({ ...LETTER, rotation: 90 }, 1)).toEqual({ width: 792, height: 612 });
    expect(viewportSize({ ...LETTER, rotation: 180 }, 1)).toEqual({ width: 612, height: 792 });
    expect(viewportSize({ ...LETTER, rotation: 270 }, 1)).toEqual({ width: 792, height: 612 });
  });

  it('applies scale after the axis swap', () => {
    // 792 x 612 at 2x, hand-computed.
    expect(viewportSize({ ...LETTER, rotation: 90 }, 2)).toEqual({ width: 1584, height: 1224 });
  });

  it('honours extraRotation', () => {
    expect(viewportSize({ ...LETTER, rotation: 90 }, 1, 90)).toEqual({ width: 612, height: 792 });
    expect(viewportSize({ ...LETTER, rotation: 0 }, 1, 270)).toEqual({ width: 792, height: 612 });
  });

  it.each([0, -1, Number.NaN, Number.POSITIVE_INFINITY])('rejects scale %p', (scale) => {
    expect(() => viewportSize(LETTER, scale)).toThrow(CoordinateError);
  });

  it.each([0, -612, Number.NaN])('rejects widthPt %p', (widthPt) => {
    expect(() => viewportSize({ ...LETTER, widthPt }, 1)).toThrow(CoordinateError);
  });

  it.each([0, -792, Number.NaN])('rejects heightPt %p', (heightPt) => {
    expect(() => viewportSize({ ...LETTER, heightPt }, 1)).toThrow(CoordinateError);
  });
});

describe('pdfBoxToViewport — identity', () => {
  it('maps a box to itself at rotation 0, scale 1, preserving the top-left origin', () => {
    expectBox(pdfBoxToViewport(NET_PAY, LETTER, 1), {
      left: 124.7,
      top: 343.4,
      width: 53.4,
      height: 11.1,
    });
  });

  it('does not flip y — the box stays in the top half of a 792pt page', () => {
    // If the origin were flipped, top would be 792 - 343.4 - 11.1 = 437.5.
    const { top } = pdfBoxToViewport(NET_PAY, LETTER, 1);
    expect(top).toBeCloseTo(343.4, 6);
    expect(top).not.toBeCloseTo(437.5, 6);
  });

  it('rejects a non-quarter-turn page rotation', () => {
    expect(() => pdfBoxToViewport(NET_PAY, { ...LETTER, rotation: 45 }, 1)).toThrow(CoordinateError);
    expect(() => pdfBoxToViewport(NET_PAY, { ...LETTER, rotation: 1 }, 1)).toThrow(CoordinateError);
    expect(() => pdfBoxToViewport(NET_PAY, LETTER, 1, 45)).toThrow(CoordinateError);
  });

  it.each([0, -2, Number.NaN, Number.POSITIVE_INFINITY])('rejects scale %p', (scale) => {
    expect(() => pdfBoxToViewport(NET_PAY, LETTER, scale)).toThrow(CoordinateError);
  });
});

/*
 * Hand-computed expectations for NET_PAY on a 612 x 792 page.
 *
 * rotation 0    left = 124.7   top = 343.4   53.4 x 11.1
 * rotation 90   left = 437.5   top = 124.7   11.1 x 53.4
 * rotation 180  left = 433.9   top = 437.5   53.4 x 11.1
 * rotation 270  left = 343.4   top = 433.9   11.1 x 53.4
 *
 * multiplied out at each zoom level:
 */
const EXPECTED: Record<Rotation, Record<(typeof SCALES)[number], ViewportBoxLiteral>> = {
  0: {
    0.5: { left: 62.35, top: 171.7, width: 26.7, height: 5.55 },
    1: { left: 124.7, top: 343.4, width: 53.4, height: 11.1 },
    1.5: { left: 187.05, top: 515.1, width: 80.1, height: 16.65 },
    2: { left: 249.4, top: 686.8, width: 106.8, height: 22.2 },
    4: { left: 498.8, top: 1373.6, width: 213.6, height: 44.4 },
  },
  90: {
    0.5: { left: 218.75, top: 62.35, width: 5.55, height: 26.7 },
    1: { left: 437.5, top: 124.7, width: 11.1, height: 53.4 },
    1.5: { left: 656.25, top: 187.05, width: 16.65, height: 80.1 },
    2: { left: 875, top: 249.4, width: 22.2, height: 106.8 },
    4: { left: 1750, top: 498.8, width: 44.4, height: 213.6 },
  },
  180: {
    0.5: { left: 216.95, top: 218.75, width: 26.7, height: 5.55 },
    1: { left: 433.9, top: 437.5, width: 53.4, height: 11.1 },
    1.5: { left: 650.85, top: 656.25, width: 80.1, height: 16.65 },
    2: { left: 867.8, top: 875, width: 106.8, height: 22.2 },
    4: { left: 1735.6, top: 1750, width: 213.6, height: 44.4 },
  },
  270: {
    0.5: { left: 171.7, top: 216.95, width: 5.55, height: 26.7 },
    1: { left: 343.4, top: 433.9, width: 11.1, height: 53.4 },
    1.5: { left: 515.1, top: 650.85, width: 16.65, height: 80.1 },
    2: { left: 686.8, top: 867.8, width: 22.2, height: 106.8 },
    4: { left: 1373.6, top: 1735.6, width: 44.4, height: 213.6 },
  },
};

type ViewportBoxLiteral = { left: number; top: number; width: number; height: number };

describe('pdfBoxToViewport — the netPay evidence box at every zoom and rotation', () => {
  for (const rotation of ROTATIONS) {
    for (const scale of SCALES) {
      it(`rotation ${rotation}, scale ${scale}`, () => {
        const page: PageGeometry = { ...LETTER, rotation };
        expectBox(pdfBoxToViewport(NET_PAY, page, scale), EXPECTED[rotation][scale]);
      });

      it(`rotation ${rotation}, scale ${scale} — reached via extraRotation instead`, () => {
        // Same total rotation, split differently between the page and the reviewer.
        const page: PageGeometry = { ...LETTER, rotation: 180 };
        const extra = rotation - 180;
        expectBox(pdfBoxToViewport(NET_PAY, page, scale, extra), EXPECTED[rotation][scale]);
      });
    }
  }
});

describe('pdfBoxToViewport — linearity in scale', () => {
  for (const rotation of ROTATIONS) {
    it(`left, top, width and height are all proportional to scale at rotation ${rotation}`, () => {
      const page: PageGeometry = { ...LETTER, rotation };
      const unit = pdfBoxToViewport(NET_PAY, page, 1);
      for (const scale of SCALES) {
        const scaled = pdfBoxToViewport(NET_PAY, page, scale);
        expectBox(scaled, {
          left: unit.left * scale,
          top: unit.top * scale,
          width: unit.width * scale,
          height: unit.height * scale,
        });
      }
    });
  }
});

/*
 * The corner test. A box in a page corner must land in the corresponding
 * rotated corner — this is what fails loudly on an axis swap or an origin flip,
 * both of which look plausible in the middle of the page.
 *
 * The probe is deliberately non-square (10 wide, 20 tall) so a width/height
 * swap cannot hide.
 */
const PROBE_W = 10;
const PROBE_H = 20;

describe('pdfBoxToViewport — page corners land in the rotated corners', () => {
  const topLeft: PdfBox = { x: 0, y: 0, width: PROBE_W, height: PROBE_H };
  const bottomRight: PdfBox = {
    x: W0 - PROBE_W,
    y: H0 - PROBE_H,
    width: PROBE_W,
    height: PROBE_H,
  };

  // Hand-derived. Viewport is 612 x 792 at 0/180 and 792 x 612 at 90/270.
  //
  //   top-left probe (0, 0, 10, 20)
  //     0    left 0,   top 0     -> viewport top-left
  //     90   left 772, top 0     -> right edge 772 + 20 = 792 = viewport width  -> top-right
  //     180  left 602, top 772   -> 602 + 10 = 612, 772 + 20 = 792              -> bottom-right
  //     270  left 0,   top 602   -> bottom 602 + 10 = 612 = viewport height     -> bottom-left
  const TOP_LEFT_EXPECTED: Record<Rotation, ViewportBoxLiteral> = {
    0: { left: 0, top: 0, width: 10, height: 20 },
    90: { left: 772, top: 0, width: 20, height: 10 },
    180: { left: 602, top: 772, width: 10, height: 20 },
    270: { left: 0, top: 602, width: 20, height: 10 },
  };

  //   bottom-right probe (602, 772, 10, 20)
  //     0    left 602, top 772   -> bottom-right
  //     90   left 0,   top 602   -> bottom 602 + 10 = 612                       -> bottom-left
  //     180  left 0,   top 0                                                    -> top-left
  //     270  left 772, top 0     -> right edge 772 + 20 = 792                   -> top-right
  const BOTTOM_RIGHT_EXPECTED: Record<Rotation, ViewportBoxLiteral> = {
    0: { left: 602, top: 772, width: 10, height: 20 },
    90: { left: 0, top: 602, width: 20, height: 10 },
    180: { left: 0, top: 0, width: 10, height: 20 },
    270: { left: 772, top: 0, width: 20, height: 10 },
  };

  for (const rotation of ROTATIONS) {
    it(`the rotation-0 top-left probe at rotation ${rotation}`, () => {
      const page: PageGeometry = { ...LETTER, rotation };
      expectBox(pdfBoxToViewport(topLeft, page, 1), TOP_LEFT_EXPECTED[rotation]);
    });

    it(`the rotation-0 bottom-right probe at rotation ${rotation}`, () => {
      const page: PageGeometry = { ...LETTER, rotation };
      expectBox(pdfBoxToViewport(bottomRight, page, 1), BOTTOM_RIGHT_EXPECTED[rotation]);
    });

    it(`both probes stay flush against the viewport edges at rotation ${rotation}`, () => {
      const page: PageGeometry = { ...LETTER, rotation };
      const { width: vw, height: vh } = viewportSize(page, 1);

      for (const probe of [topLeft, bottomRight]) {
        const b = pdfBoxToViewport(probe, page, 1);
        const touchesLeftOrRight = b.left === 0 || Math.abs(b.left + b.width - vw) < 1e-9;
        const touchesTopOrBottom = b.top === 0 || Math.abs(b.top + b.height - vh) < 1e-9;
        expect(touchesLeftOrRight).toBe(true);
        expect(touchesTopOrBottom).toBe(true);
      }
    });
  }
});

describe('pdfBoxToViewport — containment and area invariants', () => {
  for (const rotation of ROTATIONS) {
    for (const scale of SCALES) {
      it(`a box strictly inside the page stays strictly inside the viewport (rotation ${rotation}, scale ${scale})`, () => {
        const page: PageGeometry = { ...LETTER, rotation };
        const { width: vw, height: vh } = viewportSize(page, scale);
        const box = pdfBoxToViewport(NET_PAY, page, scale);

        expect(box.left).toBeGreaterThan(0);
        expect(box.top).toBeGreaterThan(0);
        expect(box.left + box.width).toBeLessThan(vw);
        expect(box.top + box.height).toBeLessThan(vh);
      });

      it(`area scales by scale squared (rotation ${rotation}, scale ${scale})`, () => {
        const page: PageGeometry = { ...LETTER, rotation };
        const box = pdfBoxToViewport(NET_PAY, page, scale);
        const pdfArea = NET_PAY.width * NET_PAY.height;
        expect(box.width * box.height).toBeCloseTo(pdfArea * scale * scale, 6);
      });

      it(`rotation preserves the box's longer axis in absolute terms (rotation ${rotation}, scale ${scale})`, () => {
        const page: PageGeometry = { ...LETTER, rotation };
        const box = pdfBoxToViewport(NET_PAY, page, scale);
        const sides = [box.width, box.height].sort((a, b) => a - b);
        expect(sides[0]).toBeCloseTo(NET_PAY.height * scale, 6);
        expect(sides[1]).toBeCloseTo(NET_PAY.width * scale, 6);
      });
    }
  }
});

describe('viewportBoxToPdf — round trip', () => {
  const boxes: Array<[string, PdfBox]> = [
    ['netPay value word', NET_PAY],
    ['page top-left probe', { x: 0, y: 0, width: PROBE_W, height: PROBE_H }],
    ['page bottom-right probe', { x: W0 - PROBE_W, y: H0 - PROBE_H, width: PROBE_W, height: PROBE_H }],
    ['whole page', { x: 0, y: 0, width: W0, height: H0 }],
  ];

  for (const rotation of ROTATIONS) {
    for (const scale of SCALES) {
      for (const [label, box] of boxes) {
        it(`${label} survives viewport -> pdf -> viewport at rotation ${rotation}, scale ${scale}`, () => {
          const page: PageGeometry = { ...LETTER, rotation };
          const back = viewportBoxToPdf(pdfBoxToViewport(box, page, scale), page, scale);
          expect(back.x).toBeCloseTo(box.x, 6);
          expect(back.y).toBeCloseTo(box.y, 6);
          expect(back.width).toBeCloseTo(box.width, 6);
          expect(back.height).toBeCloseTo(box.height, 6);
        });
      }
    }
  }

  it('rejects the same malformed inputs as the forward direction', () => {
    const viewport = { left: 0, top: 0, width: 10, height: 10 };
    expect(() => viewportBoxToPdf(viewport, { ...LETTER, rotation: 45 }, 1)).toThrow(
      CoordinateError,
    );
    expect(() => viewportBoxToPdf(viewport, LETTER, 0)).toThrow(CoordinateError);
    expect(() => viewportBoxToPdf(viewport, { ...LETTER, widthPt: 0 }, 1)).toThrow(CoordinateError);
  });
});

describe('agreement with the worker geometry module', () => {
  /*
   * worker/src/pragmaticds_docengine_worker/geometry.py rotate_box(), transcribed.
   * Independent restatement of the same clockwise quarter turn: if the UI and
   * the worker ever disagree about which way "rotation" turns, this fails.
   */
  function workerRotateBox(box: PdfBox, rotation: Rotation, pageW: number, pageH: number): PdfBox {
    switch (rotation) {
      case 0:
        return box;
      case 90:
        return { x: pageH - box.y - box.height, y: box.x, width: box.height, height: box.width };
      case 180:
        return {
          x: pageW - box.x - box.width,
          y: pageH - box.y - box.height,
          width: box.width,
          height: box.height,
        };
      case 270:
        return { x: box.y, y: pageW - box.x - box.width, width: box.height, height: box.width };
    }
  }

  for (const rotation of ROTATIONS) {
    it(`pdfBoxToViewport at scale 1 equals the worker's rotate_box at rotation ${rotation}`, () => {
      const page: PageGeometry = { ...LETTER, rotation };
      const ours = pdfBoxToViewport(NET_PAY, page, 1);
      const theirs = workerRotateBox(NET_PAY, rotation, W0, H0);
      expectBox(ours, {
        left: theirs.x,
        top: theirs.y,
        width: theirs.width,
        height: theirs.height,
      });
    });
  }
});

describe('a non-square page cannot hide an axis swap', () => {
  // A4-ish, deliberately different from Letter so W0 and H0 are never confusable.
  const A4: PageGeometry = { widthPt: 595.3, heightPt: 841.9, rotation: 90 };
  const box: PdfBox = { x: 100, y: 200, width: 40, height: 12 };

  it('rotation 90 on a 595.3 x 841.9 page', () => {
    // left = 841.9 - 200 - 12 = 629.9, top = 100, 12 x 40
    expectBox(pdfBoxToViewport(box, A4, 1), { left: 629.9, top: 100, width: 12, height: 40 });
    expect(viewportSize(A4, 1)).toEqual({ width: 841.9, height: 595.3 });
  });

  it('rotation 270 on a 595.3 x 841.9 page', () => {
    // left = 200, top = 595.3 - 100 - 40 = 455.3, 12 x 40
    expectBox(pdfBoxToViewport(box, { ...A4, rotation: 270 }, 1), {
      left: 200,
      top: 455.3,
      width: 12,
      height: 40,
    });
  });
});
