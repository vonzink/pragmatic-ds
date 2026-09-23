import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { api, adminKey, currentBrainSlug } from "./api";

const store = new Map<string, string>();

beforeEach(() => {
  vi.stubGlobal("sessionStorage", {
    getItem: (k: string) => store.get(k) ?? null,
    setItem: (k: string, v: string) => void store.set(k, v),
    removeItem: (k: string) => void store.delete(k),
  });
  store.clear();
  adminKey.set("k");
  window.location.hash = "";
});

afterEach(() => {
  vi.unstubAllGlobals();
  window.location.hash = "";
});

function fetchOk() {
  return vi.fn(async () => new Response(JSON.stringify({ ok: true }), { status: 200 }));
}
function calledPath(mock: ReturnType<typeof fetchOk>) {
  return (mock.mock.calls[0] as unknown as [string, RequestInit])[0];
}

describe("URL brain injection (HashRouter)", () => {
  it("reads the active brain slug from the hash query", () => {
    window.location.hash = "#/corpus?brain=mortgage&scope=income";
    expect(currentBrainSlug()).toBe("mortgage");
  });

  it("returns null when the hash carries no brain", () => {
    window.location.hash = "#/corpus?scope=income";
    expect(currentBrainSlug()).toBeNull();
  });

  it("injects ?brain= into brain-scoped admin calls", async () => {
    window.location.hash = "#/settings?brain=mortgage";
    const f = fetchOk();
    vi.stubGlobal("fetch", f);
    await api.get("/api/ai/admin/stats");
    expect(calledPath(f)).toBe("/api/ai/admin/stats?brain=mortgage");
  });

  it("injects ?brain= into documents calls, merging with an existing query", async () => {
    window.location.hash = "#/corpus?brain=mortgage&scope=income";
    const f = fetchOk();
    vi.stubGlobal("fetch", f);
    await api.post("/api/ai/documents/sync?dryRun=true");
    expect(calledPath(f)).toBe("/api/ai/documents/sync?dryRun=true&brain=mortgage");
  });

  it("never overrides a brain= the caller already set", async () => {
    window.location.hash = "#/corpus?brain=mortgage";
    const f = fetchOk();
    vi.stubGlobal("fetch", f);
    await api.get("/api/ai/documents?brain=suite");
    expect(calledPath(f)).toBe("/api/ai/documents?brain=suite");
  });

  it("does not touch public (non-admin) endpoints", async () => {
    window.location.hash = "#/console?brain=mortgage";
    const f = fetchOk();
    vi.stubGlobal("fetch", f);
    await api.post("/api/ai/mortgage/ask", { question: "hi" });
    expect(calledPath(f)).toBe("/api/ai/mortgage/ask");
  });

  it("leaves FormData uploads unrewritten (brain travels as a form field)", async () => {
    window.location.hash = "#/corpus?brain=mortgage";
    const f = fetchOk();
    vi.stubGlobal("fetch", f);
    const form = new FormData();
    form.append("brain", "mortgage");
    await api.upload("/api/ai/documents/upload", form);
    expect(calledPath(f)).toBe("/api/ai/documents/upload");
  });

  it("makes no change when no brain is selected", async () => {
    window.location.hash = "#/corpus";
    const f = fetchOk();
    vi.stubGlobal("fetch", f);
    await api.get("/api/ai/documents");
    expect(calledPath(f)).toBe("/api/ai/documents");
  });
});
