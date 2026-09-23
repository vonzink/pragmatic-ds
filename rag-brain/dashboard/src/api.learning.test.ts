import { afterEach, describe, expect, it, vi } from "vitest";
import { learningApi } from "./api";

const okJson = (body: unknown) =>
  Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(body) } as Response);

const noContent = () =>
  Promise.resolve({
    ok: true,
    status: 204,
    json: () => Promise.reject(new SyntaxError("Unexpected end of JSON input")),
  } as Response);

afterEach(() => {
  vi.restoreAllMocks();
  sessionStorage.clear();
});

describe("learningApi", () => {
  it("toggles a brain's learning flag via POST with the enabled query", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockImplementation(() => okJson({ id: "b1", learningEnabled: true }));
    const res = await learningApi.toggle("b1", true);
    expect(res.learningEnabled).toBe(true);
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe("/api/ai/admin/brains/b1/learning?enabled=true");
    expect(init?.method).toBe("POST");
  });

  it("lists pending events for a brain, keyed by slug (the backend resolves ?brain= via BrainRepository.findBySlug)", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockImplementation(() => okJson([]));
    await learningApi.pending("acme-support");
    expect(fetchMock.mock.calls[0][0]).toBe("/api/ai/admin/learning/pending?brain=acme-support");
  });

  it("approves and rejects an event by id, scoped to the selected brain slug", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockImplementation(() => okJson({ id: "e1", status: "APPROVED" }));
    await learningApi.approve("e1", "acme-support");
    await learningApi.reject("e1", "acme-support");
    expect(fetchMock.mock.calls[0][0]).toBe("/api/ai/admin/learning/approve/e1?brain=acme-support");
    expect(fetchMock.mock.calls[0][1]?.method).toBe("POST");
    expect(fetchMock.mock.calls[1][0]).toBe("/api/ai/admin/learning/reject/e1?brain=acme-support");
  });

  it("resets weights for a brain and reads current weights, keyed by slug not id", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockImplementation(() => okJson([]));
    await learningApi.reset("acme-support");
    await learningApi.weights("acme-support");
    expect(fetchMock.mock.calls[0][0]).toBe("/api/ai/admin/learning/reset?brain=acme-support");
    expect(fetchMock.mock.calls[0][1]?.method).toBe("POST");
    expect(fetchMock.mock.calls[1][0]).toBe("/api/ai/admin/learning/weights?brain=acme-support");
  });

  it("submits public feedback with the session header, token, and body against a 204 No Content response", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockImplementation(() => noContent());
    await expect(
      learningApi.submitFeedback("acme", "pub_tok", "sess-1", {
        traceId: "trace-1",
        rating: "UP",
        reason: null,
      }),
    ).resolves.toBeUndefined();
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe("/api/ai/public/acme/feedback");
    expect(init?.method).toBe("POST");
    const headers = new Headers(init?.headers);
    expect(headers.get("X-Public-Brain-Token")).toBe("pub_tok");
    expect(headers.get("X-Session-Id")).toBe("sess-1");
    expect(JSON.parse(init?.body as string)).toEqual({ traceId: "trace-1", rating: "UP", reason: null });
  });

  it("does not throw a JSON parse error when the feedback endpoint returns an empty body", async () => {
    // Regression: the real FeedbackController returns ResponseEntity.noContent().build(),
    // so calling response.json() unconditionally would throw SyntaxError on empty body.
    vi.spyOn(globalThis, "fetch").mockImplementation(() => noContent());
    await expect(
      learningApi.submitFeedback("acme", "pub_tok", "sess-1", {
        traceId: "trace-2",
        rating: "DOWN",
        reason: "not helpful",
      }),
    ).resolves.not.toThrow();
  });

  it("approve/reject resolve to the actual Map shape returned by the controller", async () => {
    const fetchMock = vi
      .spyOn(globalThis, "fetch")
      .mockImplementationOnce(() => okJson({ approved: true, eventId: "e1" }))
      .mockImplementationOnce(() => okJson({ rejected: true, eventId: "e1" }));
    const approveRes = await learningApi.approve("e1", "acme-support");
    const rejectRes = await learningApi.reject("e1", "acme-support");
    expect(approveRes).toEqual({ approved: true, eventId: "e1" });
    expect(rejectRes).toEqual({ rejected: true, eventId: "e1" });
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it("reset resolves to the actual Map shape returned by the controller", async () => {
    vi.spyOn(globalThis, "fetch").mockImplementation(() => okJson({ reset: true, brainId: "b1" }));
    const res = await learningApi.reset("b1");
    expect(res).toEqual({ reset: true, brainId: "b1" });
  });

  it("submits admin feedback against the admin-key-authed trace endpoint, not the public one", async () => {
    // Matches AdminTraceFeedbackController: POST /api/ai/admin/traces/{id}/feedback,
    // gated by AdminApiKeyFilter (X-Admin-Api-Key), body { rating, reason }.
    const fetchMock = vi.spyOn(globalThis, "fetch").mockImplementation(() => noContent());
    await expect(learningApi.submitAdminFeedback("trace-1", "UP")).resolves.toBeUndefined();
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe("/api/ai/admin/traces/trace-1/feedback");
    expect(init?.method).toBe("POST");
    expect(JSON.parse(init?.body as string)).toEqual({ rating: "UP", reason: null });
    const headers = new Headers(init?.headers);
    expect(headers.get("X-Public-Brain-Token")).toBeNull();
  });
});
