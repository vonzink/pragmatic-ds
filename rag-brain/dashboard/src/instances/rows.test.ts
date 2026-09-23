import { describe, expect, it } from "vitest";
import { asRows } from "./rows";

/**
 * What arrives is checked rather than assumed, because the transport is lenient by design.
 *
 * A 2xx with an empty or unparseable body resolves to `undefined` rather than throwing, and some
 * endpoints answer `null` where a list is expected. `?? []` covers only the first of those, so a
 * body that parsed to something that is not a list reaches a `.map` and takes the screen down far
 * from the call that caused it.
 */
describe("asRows", () => {
  it("keeps a list and turns everything else into an empty one", () => {
    const rows = [{ id: "a" }];
    expect(asRows(rows)).toBe(rows);
    expect(asRows(undefined)).toEqual([]);
    expect(asRows(null)).toEqual([]);
    // The case `?? []` misses: a 2xx whose body parsed to an object.
    expect(asRows({ error: "not a list" } as never)).toEqual([]);
  });
});
