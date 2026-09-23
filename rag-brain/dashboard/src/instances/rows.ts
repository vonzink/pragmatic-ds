/**
 * What a list response is worth putting into state.
 *
 * The shared transport resolves an empty or unparseable 2xx body to `undefined` rather than
 * throwing, and production genuinely produces those — a proxy's empty 200, an endpoint that
 * answers `null` where a list was expected. `?? []` covers the undefined and nothing else, so a
 * body that parsed to an object still reaches a `.map` or a `.find` and takes the screen down with
 * a type error nowhere near the call that caused it.
 *
 * Checking here rather than at each use means every list on a screen fails the same way — as an
 * empty list beside whatever error the screen already shows — instead of one of them failing
 * differently and louder.
 */
export function asRows<T>(value: T[] | null | undefined): T[] {
  return Array.isArray(value) ? value : [];
}
