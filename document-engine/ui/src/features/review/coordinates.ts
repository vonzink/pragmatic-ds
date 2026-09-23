/**
 * The UI half of the canonical coordinate space — the single conversion point.
 *
 * The worker normalises three disagreeing sources into one space
 * (worker/src/pragmaticds_docengine_worker/geometry.py); this module is the mirror on
 * the display side. Everything the API hands us is:
 *
 *     PDF points · top-left origin · rotation-0 · rounded to 0.1pt
 *
 * and everything a browser needs to absolutely-position an overlay is CSS
 * pixels in the *displayed* (rotated, scaled) viewport. This file is the only
 * place those two spaces are allowed to meet.
 *
 * ## The rotation convention
 *
 * `PageGeometry.rotation` is the PDF `/Rotate` entry as `GET /v1/packages/{id}/pages`
 * reports it: the number of degrees a viewer turns the page **clockwise** for
 * display. `widthPt`/`heightPt` are the rotation-0 dimensions and do *not*
 * account for it, which is why `viewportSize` swaps the axes at 90 and 270.
 *
 * The mapping below is the same clockwise quarter-turn the worker's
 * `rotate_box` applies, and it agrees term-for-term with what pdf.js's
 * `PageViewport` transform produces for `page.getViewport({ scale, rotation })`.
 * Both were checked by hand against the four rotation matrices; see
 * coordinates.test.ts, whose expected numbers are derived from the geometry
 * rather than captured from this implementation.
 *
 * ## Why this file is dull on purpose
 *
 * Coordinate drift is how evidence systems quietly begin to lie: a bug here
 * produces plausible-looking boxes over the wrong words with no exception
 * raised anywhere. Pure functions, no React, no pdf.js import — so the whole
 * thing is testable with arithmetic. Change nothing without a hand-computed
 * test.
 */

/** A quarter turn. The only rotations a PDF `/Rotate` may take. */
export type Rotation = 0 | 90 | 180 | 270;

/** A stored evidence box: PDF points, top-left origin, at rotation-0. */
export type PdfBox = {
  x: number;
  y: number;
  width: number;
  height: number;
};

/** A page as `GET /v1/packages/{id}/pages` reports it. Dimensions are rotation-0. */
export type PageGeometry = {
  widthPt: number;
  heightPt: number;
  /** PDF `/Rotate`: degrees a viewer turns the page clockwise. 0 | 90 | 180 | 270. */
  rotation: number;
};

/** A box in the displayed viewport: CSS px, top-left origin, ready for `position: absolute`. */
export type ViewportBox = {
  left: number;
  top: number;
  width: number;
  height: number;
};

/** The displayed size of a page in CSS px. */
export type ViewportSize = {
  width: number;
  height: number;
};

/**
 * Thrown when an input cannot describe a real page. Distinct from `Error` so a
 * caller can tell "this page row is malformed" apart from a rendering failure.
 */
export class CoordinateError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'CoordinateError';
  }
}

/**
 * Reduces any quarter-turn — including negative and >= 360 values — to 0/90/180/270.
 *
 * @throws {CoordinateError} if `degrees` is not an integer multiple of 90.
 */
export function normalizeRotation(degrees: number): Rotation {
  if (!Number.isInteger(degrees) || degrees % 90 !== 0) {
    throw new CoordinateError(
      `rotation must be an integer multiple of 90 degrees, got ${String(degrees)}`,
    );
  }
  return (((degrees % 360) + 360) % 360) as Rotation;
}

/**
 * The rotation actually applied on screen: the page's own `/Rotate` plus any
 * extra turn the reviewer has asked for.
 *
 * @throws {CoordinateError} if either component is not a quarter turn.
 */
export function totalRotation(page: PageGeometry, extraRotation = 0): Rotation {
  // Normalise each component separately so a malformed page row is reported as
  // such, rather than being cancelled out by a compensating extraRotation.
  const pageRotation = normalizeRotation(page.rotation);
  const extra = normalizeRotation(extraRotation);
  return normalizeRotation(pageRotation + extra);
}

function assertScale(scale: number): void {
  if (!Number.isFinite(scale) || scale <= 0) {
    throw new CoordinateError(`scale must be a finite positive number, got ${String(scale)}`);
  }
}

function assertPage(page: PageGeometry): void {
  if (!Number.isFinite(page.widthPt) || page.widthPt <= 0) {
    throw new CoordinateError(`page widthPt must be a finite positive number, got ${String(page.widthPt)}`);
  }
  if (!Number.isFinite(page.heightPt) || page.heightPt <= 0) {
    throw new CoordinateError(`page heightPt must be a finite positive number, got ${String(page.heightPt)}`);
  }
}

/**
 * The displayed page size in CSS px — what pdf.js's viewport `width`/`height`
 * report for the same `{ scale, rotation }`. Axes swap at 90 and 270.
 */
export function viewportSize(page: PageGeometry, scale: number, extraRotation = 0): ViewportSize {
  const rotation = totalRotation(page, extraRotation);
  assertPage(page);
  assertScale(scale);

  const swapped = rotation === 90 || rotation === 270;
  return {
    width: (swapped ? page.heightPt : page.widthPt) * scale,
    height: (swapped ? page.widthPt : page.heightPt) * scale,
  };
}

/**
 * Maps a stored evidence box into the displayed viewport.
 *
 * A point `(x, y)` in rotation-0 top-left space lands, under a clockwise turn
 * of `R` on a `W0 x H0` page, at:
 *
 *     R = 0    (x,          y)
 *     R = 90   (H0 - y,     x)
 *     R = 180  (W0 - x,     H0 - y)
 *     R = 270  (y,          W0 - x)
 *
 * Applying that to both corners of the box, then multiplying by `scale`, gives
 * the four cases below. Width and height trade places at 90 and 270.
 *
 * The result is deliberately unrounded: rounding is the renderer's business,
 * and 0.1pt of stored precision should not be thrown away at 4x zoom.
 *
 * @throws {CoordinateError} on a non-quarter-turn rotation, a non-positive
 *   scale, or a page with non-positive dimensions.
 */
export function pdfBoxToViewport(
  box: PdfBox,
  page: PageGeometry,
  scale: number,
  extraRotation = 0,
): ViewportBox {
  const rotation = totalRotation(page, extraRotation);
  assertPage(page);
  assertScale(scale);

  const { x, y, width: w, height: h } = box;
  const { widthPt: w0, heightPt: h0 } = page;

  switch (rotation) {
    case 0:
      return { left: x * scale, top: y * scale, width: w * scale, height: h * scale };
    case 90:
      return {
        left: (h0 - y - h) * scale,
        top: x * scale,
        width: h * scale,
        height: w * scale,
      };
    case 180:
      return {
        left: (w0 - x - w) * scale,
        top: (h0 - y - h) * scale,
        width: w * scale,
        height: h * scale,
      };
    case 270:
      return {
        left: y * scale,
        top: (w0 - x - w) * scale,
        width: h * scale,
        height: w * scale,
      };
  }
}

/**
 * The exact inverse of {@link pdfBoxToViewport}: takes a box measured in the
 * displayed viewport back to stored space.
 *
 * Needed the moment the UI lets a reviewer draw or drag a box — and it makes
 * the round-trip invariant testable, which is the cheapest guard there is
 * against an axis swap that happens to look plausible in one direction.
 *
 * The result is unrounded; callers writing back to the API are responsible for
 * the 0.1pt storage precision.
 *
 * @throws {CoordinateError} under the same conditions as {@link pdfBoxToViewport}.
 */
export function viewportBoxToPdf(
  viewportBox: ViewportBox,
  page: PageGeometry,
  scale: number,
  extraRotation = 0,
): PdfBox {
  const rotation = totalRotation(page, extraRotation);
  assertPage(page);
  assertScale(scale);

  const left = viewportBox.left / scale;
  const top = viewportBox.top / scale;
  const vw = viewportBox.width / scale;
  const vh = viewportBox.height / scale;
  const { widthPt: w0, heightPt: h0 } = page;

  switch (rotation) {
    case 0:
      return { x: left, y: top, width: vw, height: vh };
    case 90:
      // left = h0 - y - h, top = x, vw = h, vh = w
      return { x: top, y: h0 - left - vw, width: vh, height: vw };
    case 180:
      return { x: w0 - left - vw, y: h0 - top - vh, width: vw, height: vh };
    case 270:
      // left = y, top = w0 - x - w, vw = h, vh = w
      return { x: w0 - top - vh, y: left, width: vh, height: vw };
  }
}
