/**
 * The one formatting helper the viewer and the overlay share.
 *
 * It exists as its own module so both can use it without either importing the
 * other — and so nobody is tempted to add "just a little" geometry alongside a
 * component, which is how a second coordinate implementation gets born.
 */

/**
 * A CSS length from an unrounded pixel value.
 *
 * Two decimals is roughly 1/200th of a device pixel: invisible, but enough that
 * a box and its page still agree at 4x zoom. Anything coarser would round the
 * overlay off the text it is pointing at.
 */
export function px(value: number): string {
  return `${String(Math.round(value * 100) / 100)}px`;
}
