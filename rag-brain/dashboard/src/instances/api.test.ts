import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { adminKey, api } from "../api";
import { instanceApi } from "./api";

/**
 * Instance-control calls carry their own scope, and the sidebar must not add to it.
 *
 * The legacy dashboard resolves `?brain=<slug>` out of the HashRouter fragment and appends it to
 * every `/api/ai/admin` and `/api/ai/documents` call, so that screens which never thread a brain
 * explicitly still target the selected one. Instance-control endpoints live under
 * `/api/ai/admin/instances`, which that rule matches — and they take `@RequestParam UUID brain`.
 * A slug arriving where a UUID is expected is the mild failure; the real one is scope being
 * decided by whatever the sidebar happened to be showing rather than by the caller.
 *
 * So these calls opt out of the injection entirely. The legacy behaviour is asserted here too,
 * because "instance calls opted out" is only meaningful next to proof that everything else still
 * opts in.
 */

const store = new Map<string, string>();

beforeEach(() => {
  vi.stubGlobal("sessionStorage", {
    getItem: (k: string) => store.get(k) ?? null,
    setItem: (k: string, v: string) => void store.set(k, v),
    removeItem: (k: string) => void store.delete(k),
  });
  store.clear();
  adminKey.set("k");
  // The sidebar is sitting on a brain, as it almost always is.
  window.location.hash = "#/instances?brain=mortgage";
});

afterEach(() => {
  vi.unstubAllGlobals();
  window.location.hash = "";
});

const BRAIN = "11111111-1111-4111-8111-111111111111";

function fetchSpy() {
  const spy = vi.fn(async () => new Response(JSON.stringify([]), { status: 200 }));
  vi.stubGlobal("fetch", spy);
  return spy;
}

/** The URL the client actually requested. */
function requestedUrl(spy: ReturnType<typeof fetchSpy>): string {
  return String((spy.mock.calls[0] as unknown as [string, RequestInit])[0]);
}

describe("instance-control client", () => {
  it("never carries the sidebar's brain slug", async () => {
    const spy = fetchSpy();

    await instanceApi.get(`/api/ai/admin/instances?brain=${BRAIN}`);

    expect(requestedUrl(spy)).not.toContain("mortgage");
  });

  it("carries the caller's explicit brain exactly once", async () => {
    const spy = fetchSpy();

    await instanceApi.get(`/api/ai/admin/instances?brain=${BRAIN}`);

    const url = requestedUrl(spy);
    expect(url).toContain(`brain=${BRAIN}`);
    // Two brain params would let the backend pick either one, and which it picks is a
    // servlet-container detail rather than a decision anybody made.
    expect(url.match(/[?&]brain=/g)).toHaveLength(1);
  });

  it("leaves a call that names no brain without one", async () => {
    const spy = fetchSpy();

    // The model catalog is deployment-wide, not brain-scoped. Injecting here would invent a
    // scope the endpoint does not have.
    await instanceApi.get("/api/ai/admin/instances/models");

    expect(requestedUrl(spy)).not.toContain("brain=");
  });

  it("opts out on every verb, not only reads", async () => {
    const spy = fetchSpy();

    await instanceApi.post(`/api/ai/admin/instances?brain=${BRAIN}`, { slug: "income" });

    expect(requestedUrl(spy)).not.toContain("mortgage");
  });

  it("still sends the admin key", async () => {
    const spy = fetchSpy();

    await instanceApi.get(`/api/ai/admin/instances?brain=${BRAIN}`);

    const init = (spy.mock.calls[0] as unknown as [string, RequestInit])[1];
    expect(new Headers(init.headers).get("X-Admin-Api-Key")).toBe("k");
  });

  it("reuses one idempotency key rather than minting one per attempt", async () => {
    const spy = fetchSpy();

    await instanceApi.postIdempotent(
      `/api/ai/admin/instances/run-groups?brain=${BRAIN}`, { mode: "INDEPENDENT" }, "key-1");

    const init = (spy.mock.calls[0] as unknown as [string, RequestInit])[1];
    expect(new Headers(init.headers).get("Idempotency-Key")).toBe("key-1");
    expect(requestedUrl(spy)).not.toContain("mortgage");
  });

  // ============================================================ the other half

  it("leaves the legacy client injecting, which is why opting out means something", async () => {
    const spy = fetchSpy();

    await api.get("/api/ai/admin/stats");

    expect(requestedUrl(spy)).toContain("brain=mortgage");
  });
});
