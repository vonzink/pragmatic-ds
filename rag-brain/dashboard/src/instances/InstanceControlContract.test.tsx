import { render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

/**
 * Cold loads, deep links, and what the admin surface shows of a connector's work.
 *
 * `InstanceControl.e2e.test.tsx` drives the flows — run, compare, promote — through a mounted
 * app. This file proves the property those flows quietly depend on: every route stands up from
 * its URL alone. A refresh, a bookmark, or a pasted link is a fresh module graph with no screen
 * having run before it, so each test here renders the app cold at one route and asserts the
 * screen identifies itself; the workspace test further asserts the requests carry the brain UUID
 * from the URL's own segments — never the sidebar's legacy `?brain=<slug>`.
 *
 * The last test is the admin half of the connector contract: a group a connector created is
 * ordinary history in the run queue — visible, statused, and carrying nothing tenant-shaped,
 * because the admin DTO has no field for the tenant to hide in.
 */

const BRAIN = "11111111-1111-4111-8111-111111111111";
const GROUP = "88888888-8888-4888-8888-888888888888";

let sent: { method: string; url: string }[];

function ok(body: unknown) {
  return { ok: true, status: 200, json: async () => body };
}

function respond(method: string, path: string) {
  if (path.startsWith("/api/ai/admin/stats")) {
    return ok({ brain: { id: BRAIN, companyName: "Mortgage", slug: "mortgage" },
      corpus: { activeDocuments: 1, totalDocuments: 1, chunks: 3 } });
  }
  if (path.startsWith("/api/ai/admin/brains")) {
    return ok([{ id: BRAIN, slug: "mortgage", displayName: "Mortgage" }]);
  }
  if (path.startsWith("/api/ai/admin/instances/model-catalog")) {
    return ok([{ provider: "synthetic", model: "synthetic-analyzer",
      contextTokenCeiling: 100000, outputTokenCeiling: 8000,
      tokenizerStrategy: "CONSERVATIVE_RANGE", inputUsdPerMillion: 1,
      cachedInputUsdPerMillion: null, outputUsdPerMillion: 5 }]);
  }
  if (path.startsWith("/api/ai/admin/instances/wizard-options")) {
    return ok({ parserVersions: [{ envelopeVersion: "1.0.0",
        canonicalizationVersion: "DOCENGINE-C14N-1" }],
      outputSchemas: [{ schemaId: "analyzer-envelope-v2", sha256: "a".repeat(64) }],
      scenarioSets: [{ scenarioSetId: "income-smoke", version: 1, scenarioCount: 4 }],
      tools: [] });
  }
  if (path.startsWith("/api/ai/admin/instances/corpus-collections")) {
    return ok([]);
  }
  if (method === "GET" && /\/run-groups\?/.test(path)) {
    // A connector-created group, exactly as the admin API answers it: identifiers, status,
    // counts — structurally nothing tenant-shaped, because the DTO has no such field.
    return ok([{ groupId: GROUP, brainId: BRAIN, mode: "INDEPENDENT",
      comparisonDimension: null, status: "SUCCEEDED", memberCount: 1,
      createdAt: "2026-08-20T10:00:00Z", terminalAt: "2026-08-20T10:00:02Z",
      cancellationRequestedAt: null }]);
  }
  if (method === "GET" && path.includes("/pointer")) {
    return ok({ liveReleaseId: null, pointerVersion: 0, events: [] });
  }
  if (method === "GET" && path.includes("/releases")) {
    return ok([]);
  }
  if (method === "GET" && /\/instances\/[a-z-]+(\?|$)/.test(path)) {
    return ok({ brainId: BRAIN, slug: "income", displayName: "Income",
      purpose: "Synthetic fixture.", state: "ACTIVE", liveReleaseNumber: null,
      candidateCount: 0, manifestVersion: 2, provider: "synthetic",
      model: "synthetic-analyzer", collectionCount: 0, hasCandidateRelease: false,
      limitationCode: null, limitationFlags: [],
      createdAt: "2026-08-01T00:00:00Z", updatedAt: "2026-08-01T00:00:00Z",
      liveRelease: null });
  }
  if (method === "GET" && path.startsWith("/api/ai/admin/instances")) {
    return ok([]);
  }
  return ok([]);
}

async function coldLoadAt(path: string) {
  window.location.hash = `#${path}`;
  const App = (await import("../App")).default;
  return render(<App />);
}

beforeEach(() => {
  vi.resetModules();
  vi.stubEnv("VITE_INSTANCE_CONTROL_ENABLED", "true");
  sessionStorage.setItem("rag-brain-admin-key", "test-key");
  sent = [];
  vi.stubGlobal("fetch", vi.fn(async (input: string, init: RequestInit = {}) => {
    const url = String(input);
    const method = (init.method ?? "GET").toUpperCase();
    sent.push({ method, url });
    return respond(method, url);
  }));
});

afterEach(() => {
  vi.unstubAllEnvs();
  vi.unstubAllGlobals();
  sessionStorage.clear();
  window.location.hash = "";
});

describe("every route stands up from its URL alone", () => {
  it("cold-loads the landing", async () => {
    await coldLoadAt("/instances");
    await waitFor(() =>
      expect(screen.getByRole("heading", { name: "Instances" })).toBeTruthy());
  });

  it("cold-loads the wizard", async () => {
    await coldLoadAt("/instances/new");
    await waitFor(() =>
      expect(screen.getByRole("heading", { name: "Create an instance" })).toBeTruthy());
  });

  it("cold-loads the run queue", async () => {
    await coldLoadAt("/instance-runs");
    await waitFor(() =>
      expect(screen.getByRole("heading", { name: "Run queue" })).toBeTruthy());
  });

  it("cold-loads the independent run builder", async () => {
    await coldLoadAt("/instance-runs/new");
    await waitFor(() =>
      expect(screen.getByRole("heading", { name: "Run independently" })).toBeTruthy());
  });

  it("cold-loads the model catalog", async () => {
    await coldLoadAt("/instance-models");
    await waitFor(() =>
      expect(screen.getByRole("heading", { name: "Models and providers" })).toBeTruthy());
  });
});

describe("a workspace deep link", () => {
  it("rebuilds its scope from the URL segments, never the sidebar slug", async () => {
    await coldLoadAt(`/instances/${BRAIN}/income/releases`);

    await waitFor(() => {
      const workspaceCalls = sent.filter(
          (request) => request.url.includes("/api/ai/admin/instances/")
              && !request.url.includes("wizard-options"));
      expect(workspaceCalls.length).toBeGreaterThan(0);
    });
    // Every instance-control request that names a brain names the UUID from the URL; the
    // legacy injector's ?brain=mortgage must never reach these paths.
    for (const request of sent.filter((r) =>
        r.url.includes("/api/ai/admin/instances"))) {
      expect(request.url).not.toContain("brain=mortgage");
    }
    expect(sent.some((r) => r.url.includes(BRAIN))).toBe(true);
  });
});

describe("a connector-created group in the admin queue", () => {
  it("is ordinary visible history carrying nothing tenant-shaped", async () => {
    const view = await coldLoadAt("/instance-runs");

    await waitFor(() =>
        expect(screen.getByText("SUCCEEDED")).toBeTruthy());
    // The row exists and identifies itself; the page contains nothing tenant-like, because
    // the admin DTO structurally cannot carry one.
    expect(view.container.textContent).not.toMatch(/tenant/i);
  });
});
