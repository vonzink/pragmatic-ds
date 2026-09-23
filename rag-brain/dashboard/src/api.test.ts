import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { api, adminKey, ApiError, AuthError, brainsApi, labApi, legacyAskPath, toolAdaptersApi, toolDefinitionsApi } from "./api";

const store = new Map<string, string>();

beforeEach(() => {
  vi.stubGlobal("sessionStorage", {
    getItem: (k: string) => store.get(k) ?? null,
    setItem: (k: string, v: string) => void store.set(k, v),
    removeItem: (k: string) => void store.delete(k),
  });
  store.clear();
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
});

function fetchReturning(status: number, body: unknown) {
  return vi.fn(async () => new Response(JSON.stringify(body), { status }));
}

describe("api client", () => {
  it("sends the admin key header on every request", async () => {
    adminKey.set("secret-key");
    const fetchMock = fetchReturning(200, { ok: true });
    vi.stubGlobal("fetch", fetchMock);

    await api.get("/api/ai/admin/stats");

    const call = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    const init = call[1];
    expect(new Headers(init.headers).get("X-Admin-Api-Key")).toBe("secret-key");
  });

  it("treats an empty 200 body as no content instead of throwing", async () => {
    adminKey.set("k");
    // Backend returns null (empty body) for an unconfigured tool adapter as HTTP 200.
    vi.stubGlobal("fetch", vi.fn(async () => new Response("", { status: 200 })));

    await expect(api.get("/api/ai/admin/tool-adapters/searchLoans?brain=mortgage"))
      .resolves.toBeUndefined();
  });

  it("clears the key and throws AuthError on 401", async () => {
    adminKey.set("bad-key");
    vi.stubGlobal("fetch", fetchReturning(401, { error: "nope" }));

    await expect(api.get("/api/ai/admin/stats")).rejects.toBeInstanceOf(AuthError);
    expect(adminKey.get()).toBeNull();
  });

  it("throws the server error message on non-401 failures", async () => {
    adminKey.set("k");
    vi.stubGlobal("fetch", fetchReturning(400, { error: "retrieval.top-k must be between 1 and 50" }));

    await expect(api.put("/api/ai/admin/settings", {})).rejects.toThrow(
      "retrieval.top-k must be between 1 and 50");
  });

  it("posts to the per-brain activate endpoint by id", async () => {
    adminKey.set("k");
    const fetchMock = fetchReturning(200, { id: "abc", slug: "lending", isDefault: true });
    vi.stubGlobal("fetch", fetchMock);

    await brainsApi.activate("abc");

    const call = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect(call[0]).toBe("/api/ai/admin/brains/abc/activate");
    expect((call[1].method ?? "GET")).toBe("POST");
  });

  it("builds legacy ask paths from the configured route slug and target brain", () => {
    vi.stubEnv("VITE_LEGACY_ASK_SLUG", "mortgage");

    expect(legacyAskPath("acme")).toBe("/api/ai/mortgage/ask?brain=acme");
  });

  it("creates tool definitions for a selected brain slug", async () => {
    adminKey.set("k");
    const fetchMock = fetchReturning(200, {
      id: "tool-1",
      brainId: "brain-1",
      name: "searchVisibleLoans",
      description: "Search loans visible to the current user.",
      mode: "READ",
      confirmationRequired: false,
      requiredPermissions: ["dashboard.loans.read"],
      inputSchema: { type: "object", properties: {} },
      active: true,
      createdAt: "2026-07-03T00:00:00Z",
      updatedAt: "2026-07-03T00:00:00Z",
    });
    vi.stubGlobal("fetch", fetchMock);

    await toolDefinitionsApi.create("dashboard-brain", {
      name: "searchVisibleLoans",
      description: "Search loans visible to the current user.",
      mode: "READ",
      confirmationRequired: false,
      requiredPermissions: ["dashboard.loans.read"],
      inputSchema: { type: "object", properties: {} },
    });

    const call = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect(call[0]).toBe("/api/ai/admin/tool-definitions?brain=dashboard-brain");
    expect((call[1].method ?? "GET")).toBe("POST");
    expect(JSON.parse(call[1].body as string)).toEqual({
      name: "searchVisibleLoans",
      description: "Search loans visible to the current user.",
      mode: "READ",
      confirmationRequired: false,
      requiredPermissions: ["dashboard.loans.read"],
      inputSchema: { type: "object", properties: {} },
    });
  });

  it("upserts tool adapters for a selected brain slug and tool name", async () => {
    adminKey.set("k");
    const fetchMock = fetchReturning(200, {
      id: "adapter-1",
      brainId: "brain-1",
      toolName: "searchLoans",
      enabled: true,
      httpMethod: "POST",
      urlTemplate: "https://dashboard.example.com/api/search",
      authMode: "BEARER_TOKEN",
      secretRef: "dashboard_api",
      apiKeyHeader: "",
      staticHeaders: { "X-App": "rag-brain" },
      requestBodyTemplate: { query: "{query}" },
      timeoutMs: 5000,
      createdAt: "2026-07-03T00:00:00Z",
      updatedAt: "2026-07-03T00:00:00Z",
    });
    vi.stubGlobal("fetch", fetchMock);

    await toolAdaptersApi.upsert("dashboard-brain", "searchLoans", {
      enabled: true,
      httpMethod: "POST",
      urlTemplate: "https://dashboard.example.com/api/search",
      authMode: "BEARER_TOKEN",
      secretRef: "dashboard_api",
      apiKeyHeader: "",
      staticHeaders: { "X-App": "rag-brain" },
      requestBodyTemplate: { query: "{query}" },
      timeoutMs: 5000,
      allowedHosts: ["dashboard.example.com"],
    });

    const call = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect(call[0]).toBe("/api/ai/admin/tool-adapters/searchLoans?brain=dashboard-brain");
    expect((call[1].method ?? "GET")).toBe("PUT");
    expect(JSON.parse(call[1].body as string)).toEqual({
      enabled: true,
      httpMethod: "POST",
      urlTemplate: "https://dashboard.example.com/api/search",
      authMode: "BEARER_TOKEN",
      secretRef: "dashboard_api",
      apiKeyHeader: "",
      staticHeaders: { "X-App": "rag-brain" },
      requestBodyTemplate: { query: "{query}" },
      timeoutMs: 5000,
      allowedHosts: ["dashboard.example.com"],
    });
  });
});

// ============================================================ Income Lab prototype helpers

describe("idempotent lab helpers", () => {
  const KEY = "6f1d1f1e-0000-4000-8000-000000000001";

  function labFetch(status = 201, body: unknown = { created: true }) {
    return vi.fn(async () => new Response(JSON.stringify(body), { status }));
  }

  function callOf(fetchMock: ReturnType<typeof labFetch>, index = 0) {
    return fetchMock.mock.calls[index] as unknown as [string, RequestInit];
  }

  beforeEach(() => {
    adminKey.set("k");
    window.location.hash = "#/lab/income?brain=mortgage";
  });

  it("sends Idempotency-Key and JSON content type on postIdempotent", async () => {
    const fetchMock = labFetch();
    vi.stubGlobal("fetch", fetchMock);

    await api.postIdempotent("/api/ai/admin/lab/instances/income/runs", { packageId: "p" }, KEY);

    const [url, init] = callOf(fetchMock);
    const headers = new Headers(init.headers);
    expect(headers.get("Idempotency-Key")).toBe(KEY);
    expect(headers.get("Content-Type")).toBe("application/json");
    expect(headers.get("X-Admin-Api-Key")).toBe("k");
    expect(url).toBe("/api/ai/admin/lab/instances/income/runs?brain=mortgage");
    expect(JSON.parse(init.body as string)).toEqual({ packageId: "p" });
  });

  it("sends Idempotency-Key without a JSON content type on uploadIdempotent", async () => {
    const fetchMock = labFetch();
    vi.stubGlobal("fetch", fetchMock);
    const form = new FormData();
    form.append("file", new Blob(["x"]), "synthetic.pdf");

    await api.uploadIdempotent("/api/ai/admin/lab/instances/income/documents", form, KEY);

    const [, init] = callOf(fetchMock);
    const headers = new Headers(init.headers);
    expect(headers.get("Idempotency-Key")).toBe(KEY);
    // The browser must set its own multipart boundary; a JSON type here breaks the upload.
    expect(headers.get("Content-Type")).toBeNull();
    expect(init.body).toBe(form);
  });

  it("carries the hash-route brain exactly once on a multipart upload", async () => {
    const fetchMock = labFetch();
    vi.stubGlobal("fetch", fetchMock);
    const form = new FormData();
    form.append("file", new Blob(["x"]), "synthetic.pdf");

    await api.uploadIdempotent("/api/ai/admin/lab/instances/income/documents", form, KEY);

    // request() bypasses the brain rewrite for FormData, so the helper adds it — and only it.
    const [url] = callOf(fetchMock);
    expect(url).toBe("/api/ai/admin/lab/instances/income/documents?brain=mortgage");
    expect(url.match(/brain=/g)).toHaveLength(1);
    expect(form.get("brain")).toBeNull();
  });

  it("does not add a second brain when the caller already named one", async () => {
    const fetchMock = labFetch();
    vi.stubGlobal("fetch", fetchMock);

    await api.uploadIdempotent(
      "/api/ai/admin/lab/instances/income/documents?brain=explicit", new FormData(), KEY);

    const [url] = callOf(fetchMock);
    expect(url.match(/brain=/g)).toHaveLength(1);
    expect(url).toContain("brain=explicit");
  });

  it("sends the identical key when the same action is retried after a network failure", async () => {
    const fetchMock = vi.fn()
      .mockRejectedValueOnce(new TypeError("network down"))
      .mockResolvedValueOnce(new Response(JSON.stringify({ created: true }), { status: 201 }));
    vi.stubGlobal("fetch", fetchMock);
    const form = new FormData();

    await expect(api.uploadIdempotent("/api/ai/admin/lab/instances/income/documents", form, KEY))
      .rejects.toThrow("network down");
    await api.uploadIdempotent("/api/ai/admin/lab/instances/income/documents", form, KEY);

    expect(new Headers(callOf(fetchMock, 0)[1].headers).get("Idempotency-Key")).toBe(KEY);
    expect(new Headers(callOf(fetchMock, 1)[1].headers).get("Idempotency-Key")).toBe(KEY);
  });

  it("leaves the ordinary helpers without an idempotency header", async () => {
    const fetchMock = labFetch(200, { ok: true });
    vi.stubGlobal("fetch", fetchMock);

    await api.post("/api/ai/admin/brains", { slug: "x" });
    await api.get("/api/ai/admin/stats");

    expect(new Headers(callOf(fetchMock, 0)[1].headers).get("Idempotency-Key")).toBeNull();
    expect(new Headers(callOf(fetchMock, 1)[1].headers).get("Idempotency-Key")).toBeNull();
  });
});

describe("lab error bodies", () => {
  /** Runs a request that must fail and hands back the ApiError it threw. */
  async function failureOf(run: () => Promise<unknown>): Promise<ApiError> {
    let thrown: unknown;
    let succeeded = false;
    try {
      await run();
      succeeded = true;
    } catch (e) {
      thrown = e;
    }
    if (succeeded) throw new Error("expected the request to fail");
    expect(thrown).toBeInstanceOf(ApiError);
    return thrown as ApiError;
  }

  it("surfaces the code, correlation id, and counts from a message-free lab error body", async () => {
    adminKey.set("k");
    vi.stubGlobal("fetch", vi.fn(async () => new Response(
      JSON.stringify({
        code: "RETENTION_NOT_CONFIGURED",
        correlationId: "corr-1",
        counts: { files: 2 },
      }),
      { status: 503 })));

    const failure = await failureOf(() => api.get("/api/ai/admin/lab/instances"));

    expect(failure.status).toBe(503);
    expect(failure.code).toBe("RETENTION_NOT_CONFIGURED");
    expect(failure.correlationId).toBe("corr-1");
    expect(failure.counts).toEqual({ files: 2 });
    // No prose was invented: the message is the code the server actually sent.
    expect(failure.message).toBe("RETENTION_NOT_CONFIGURED");
  });

  it("keeps the existing {error} bodies behaving exactly as before", async () => {
    adminKey.set("k");
    vi.stubGlobal("fetch", vi.fn(async () => new Response(
      JSON.stringify({ error: "retrieval.top-k must be between 1 and 50" }), { status: 400 })));

    const failure = await failureOf(() => api.put("/api/ai/admin/settings", {}));

    expect(failure.message).toBe("retrieval.top-k must be between 1 and 50");
    expect(failure.code).toBeNull();
    expect(failure.correlationId).toBeNull();
  });
});

describe("labApi", () => {
  function jsonFetch(body: unknown = {}) {
    return vi.fn(async () => new Response(JSON.stringify(body), { status: 200 }));
  }

  beforeEach(() => {
    adminKey.set("k");
    window.location.hash = "#/lab/income?brain=mortgage";
  });

  it("addresses every prototype route under the already-gated admin prefix", async () => {
    const fetchMock = jsonFetch();
    vi.stubGlobal("fetch", fetchMock);

    await labApi.instances();
    await labApi.documentStatus("pkg-1", "job-1");
    await labApi.envelope("pkg-1", 2);
    await labApi.history();
    await labApi.run("run-1");
    await labApi.discussion("run-1");

    const urls = fetchMock.mock.calls.map((c) => (c as unknown as [string])[0]);
    expect(urls).toEqual([
      "/api/ai/admin/lab/instances?brain=mortgage",
      "/api/ai/admin/lab/documents/pkg-1?jobId=job-1&brain=mortgage",
      "/api/ai/admin/lab/documents/pkg-1/envelope?revision=2&brain=mortgage",
      "/api/ai/admin/lab/runs?instance=income&brain=mortgage",
      "/api/ai/admin/lab/runs/run-1?brain=mortgage",
      "/api/ai/admin/lab/runs/run-1/messages?brain=mortgage",
    ]);
  });

  it("purges a run through DELETE and never through a route that could reach the engine package",
    async () => {
      const fetchMock = jsonFetch({ deleted: true, enginePackageRetained: true });
      vi.stubGlobal("fetch", fetchMock);

      await labApi.purgeRun("run-1");

      const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
      expect(init.method).toBe("DELETE");
      expect(url).toBe("/api/ai/admin/lab/runs/run-1?brain=mortgage");
    });
});
