import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

/**
 * The whole control plane, driven through the real screens and the real HTTP client.
 *
 * Every other test in this directory mocks `instanceApi`, which is the right tool for asking what
 * one screen does. None of them can catch the failures that only appear between screens: a brain
 * that reaches a request as a slug instead of a UUID, a release id that survives a tab change, a
 * pointer version read on one panel and asserted by another. So this file mocks `fetch` and
 * nothing else, and every request below is one the browser would really have made.
 *
 * That also makes it the only test that exercises the api layer's scope rule — the legacy
 * `?brain=<slug>` injector matches `/api/ai/admin/instances`, and instance-control calls opt out of
 * it. A regression there would leave every screen working in isolation and the product answering
 * from the wrong brain.
 *
 * Timers are avoided rather than faked: the polled group returns a terminal status on its first
 * response, so the polling hook never schedules a retry. Polling's own behaviour — backoff,
 * stopping, surviving a dropped request — is covered by `useRunGroupPolling.test.tsx`, and driving
 * it again through a mounted app would test the timers rather than the flow.
 */

const BRAIN = "11111111-1111-4111-8111-111111111111";
const RELEASE_LIVE = "22222222-2222-4222-8222-222222222222";
const RELEASE_CANDIDATE = "33333333-3333-4333-8333-333333333333";
const COLLECTION = "44444444-4444-4444-8444-444444444444";
const PACKAGE = "55555555-5555-4555-8555-555555555555";
const REGISTRATION = "66666666-6666-4666-8666-666666666666";
const SNAPSHOT = "77777777-7777-4777-8777-777777777777";
const GROUP = "88888888-8888-4888-8888-888888888888";
const RUN = "99999999-9999-4999-8999-999999999999";

/** What the fake server would answer, per test. Mutated by a test before it drives the UI. */
interface ServerState {
  groupStatus: string;
  members: Record<string, unknown>[];
  preflightAcceptable: boolean;
  preflightBlockers: string[];
  collectionVersion: number;
  pointerVersion: number;
  promotionAllowed: boolean;
  promotionBlockers: string[];
  moveFails: string | null;
}

let server: ServerState;
/** Every request the UI made, in order, so a test can assert what reached the wire. */
let sent: { method: string; url: string; body: unknown; key: string | null }[];

function ok(body: unknown) {
  return { ok: true, status: 200, json: async () => body };
}

function fail(status: number, code: string) {
  return { ok: false, status, json: async () => ({ code }) };
}

function release(over: Record<string, unknown> = {}) {
  return {
    releaseId: RELEASE_LIVE, releaseNumber: 3, provenance: "WIZARD", live: true,
    manifestVersion: 2, provider: "anthropic", model: "claude-opus-5", collectionCount: 1,
    limitationCode: null, limitationFlags: [], createdAt: "2026-08-01T00:00:00Z", ...over,
  };
}

function member(over: Record<string, unknown> = {}) {
  return {
    memberIndex: 0, runId: RUN, instanceSlug: "income", releaseId: RELEASE_LIVE,
    registrationId: REGISTRATION, corpusSnapshotId: SNAPSHOT, status: "SUCCEEDED",
    failureCode: null, provider: "anthropic", model: "claude-opus-5", pricingVersionId: "p",
    expectedInputMin: 1000, expectedInputMax: 1400, expectedOutputMin: 200, expectedOutputMax: 400,
    expectedCostUsdMin: 0.01, expectedCostUsdMax: 0.03, estimateQuality: "EXACT",
    actualInputTokens: 1200, actualCachedTokens: 400, actualOutputTokens: 300,
    actualTotalTokens: 1500, actualCostUsd: 0.02, usageQuality: "REPORTED",
    createdAt: "2026-08-20T10:00:00Z", terminalAt: "2026-08-20T10:00:02Z",
    result: { monthlyIncome: 5000, citations: ["a"] }, ...over,
  };
}

/** Routes a request the way the real backend would, from `server`. */
function respond(method: string, path: string, body: unknown) {
  if (path.startsWith("/api/ai/admin/stats")) {
    return ok({ brain: { id: BRAIN, companyName: "Mortgage", slug: "mortgage" },
      corpus: { activeDocuments: 1, totalDocuments: 1, chunks: 3 } });
  }
  if (path.startsWith("/api/ai/admin/brains")) {
    return ok([{ id: BRAIN, slug: "mortgage", displayName: "Mortgage" }]);
  }
  if (path.startsWith("/api/ai/admin/instances/model-catalog")) {
    return ok([{ provider: "anthropic", model: "claude-opus-5", contextTokenCeiling: 200000,
      outputTokenCeiling: 64000, tokenizerStrategy: "EXACT", inputUsdPerMillion: 5,
      cachedInputUsdPerMillion: 0.5, outputUsdPerMillion: 25 }]);
  }
  if (path.startsWith("/api/ai/admin/instances/corpus-collections")) {
    return ok([{ id: COLLECTION, brainId: BRAIN, slug: "guidelines",
      displayName: "Guidelines", state: "ACTIVE", version: server.collectionVersion,
      clonedFromId: null, documentCount: 12 }]);
  }
  if (method === "POST" && path.startsWith("/api/ai/admin/instances/corpus-snapshots")) {
    return ok({ snapshotId: SNAPSHOT, brainId: BRAIN, manifestSha256: "e".repeat(64),
      collections: [{ collectionId: COLLECTION, collectionVersion: server.collectionVersion }],
      documents: [] });
  }
  if (method === "POST" && path.includes("/parsed-inputs")) {
    return ok({ registrationId: REGISTRATION, brainId: BRAIN, instanceSlug: "income",
      packageId: PACKAGE, revision: 4, processingJobId: "j", parseGeneration: 1,
      envelopeVersion: "1.0.0", canonicalizationVersion: "DOCENGINE-C14N-1",
      envelopeSha256: "a".repeat(64), envelopeSizeBytes: 1024, sourceSetSha256: "b".repeat(64),
      selectedSourceIds: ["s1"],
      compatibility: { compatible: true, rejection: null, supportedDocumentCount: 1,
        warnings: [] } });
  }
  if (method === "POST" && path.includes("/run-groups/preflight")) {
    return ok({ requestSha256: "c".repeat(64), comparisonBasisSha256: null,
      members: [{ memberIndex: 0, instanceSlug: "income", releaseId: RELEASE_LIVE,
        registrationId: REGISTRATION, corpusSnapshotId: SNAPSHOT, provider: "anthropic",
        model: "claude-opus-5", pricingVersionId: "p", inputTokensMin: 1000,
        inputTokensMax: 1400, outputTokensMin: 200, outputTokensMax: 400, costUsdMin: 0.01,
        costUsdMax: 0.03, estimateQuality: "EXACT" }],
      reservedMaximumUsd: 0.03, committedTodayUsd: 0, alreadyReservedUsd: 0, dailyBudgetUsd: 10,
      withinBudget: server.preflightAcceptable, acceptable: server.preflightAcceptable,
      blockingCodes: server.preflightBlockers });
  }
  if (method === "POST" && /\/run-groups(\?|$)/.test(path)) {
    return ok({ groupId: GROUP, created: true, memberRunIds: [RUN] });
  }
  if (method === "GET" && path.includes(`/run-groups/${GROUP}`)) {
    return ok({ group: { groupId: GROUP, brainId: BRAIN, mode: "INDEPENDENT",
      comparisonDimension: null, status: server.groupStatus, memberCount: server.members.length,
      createdAt: "2026-08-20T10:00:00Z", terminalAt: "2026-08-20T10:00:02Z",
      cancellationRequestedAt: null }, members: server.members });
  }
  if (method === "GET" && path.includes("/pointer")) {
    return ok({ liveReleaseId: RELEASE_LIVE, pointerVersion: server.pointerVersion,
      events: [{ action: "PROMOTE", fromReleaseId: null, toReleaseId: RELEASE_LIVE,
        pointerVersion: 1, actorId: "ops@example.com", changeReason: "first promotion of income",
        occurredAt: "2026-08-01T00:00:00Z" }] });
  }
  if (method === "GET" && path.includes("/configuration")) {
    return ok({ releaseId: RELEASE_LIVE, releaseNumber: 3, live: true, manifestVersion: 2,
      parsedData: { envelopeVersion: "1.0.0", canonicalizationVersion: "DOCENGINE-C14N-1",
        allowedDocumentTypes: ["PAYSTUB"], requireAnyDocumentTypes: ["PAYSTUB"],
        minimumSupportedDocuments: 1, reviewRequired: "WARN", missingFields: "PRESERVE" },
      model: { provider: "anthropic", model: "claude-opus-5", fallbackPolicy: "NONE" },
      corpus: [{ collectionId: COLLECTION, collectionVersion: 7 }], tools: [],
      output: { schemaId: "analyzer-envelope-v2", sha256: "a".repeat(64) },
      limits: { maximumInputTokens: 100000, maximumRetrievedTokens: 20000,
        maximumOutputTokens: 8000, maximumDiscussionTokens: 0, maximumConcurrentRuns: 2,
        maximumExpectedCostUsd: 1.5 },
      evaluations: { scenarioSetId: "income-smoke", scenarioSetVersion: 1, minimumScore: 0.8 },
      behaviorPresent: true });
  }
  if (method === "POST" && path.includes("/promotion-check")) {
    return ok({ allowed: server.promotionAllowed, blockingCodes: server.promotionBlockers });
  }
  if (method === "POST" && (path.includes("/apply-to-live") || path.includes("/rollback"))) {
    if (server.moveFails) return fail(409, server.moveFails);
    server.pointerVersion += 1;
    return ok({ liveReleaseId: RELEASE_CANDIDATE, pointerVersion: server.pointerVersion });
  }
  if (method === "POST" && path.includes("/evaluate")) {
    return ok({ evaluationId: "e", releaseId: RELEASE_CANDIDATE, scenarioSetId: "income-smoke",
      scenarioSetVersion: 1, score: 0.92, passed: true, reportSha256: "f".repeat(64),
      scenariosRun: 4, scenariosPassed: 4 });
  }
  if (method === "GET" && path.includes("/releases")) {
    return ok([release({ releaseId: RELEASE_CANDIDATE, releaseNumber: 4, live: false }),
      release()]);
  }
  if (method === "GET" && /\/instances\/[a-z-]+(\?|$)/.test(path)) {
    return ok({ brainId: BRAIN, slug: "income", displayName: "Income",
      purpose: "Analyze income.", state: "ACTIVE", liveReleaseNumber: 3, candidateCount: 1,
      manifestVersion: 2, provider: "anthropic", model: "claude-opus-5", collectionCount: 1,
      hasCandidateRelease: true, limitationCode: null, limitationFlags: [],
      createdAt: "2026-08-01T00:00:00Z", updatedAt: "2026-08-01T00:00:00Z",
      liveRelease: release() });
  }
  if (method === "GET" && path.startsWith("/api/ai/admin/instances")) {
    return ok([{ brainId: BRAIN, slug: "income", displayName: "Income",
      purpose: "Analyze income.", state: "ACTIVE", liveReleaseNumber: 3, candidateCount: 1,
      manifestVersion: 2, provider: "anthropic", model: "claude-opus-5", collectionCount: 1,
      hasCandidateRelease: true, limitationCode: null, limitationFlags: [],
      createdAt: "2026-08-01T00:00:00Z", updatedAt: "2026-08-01T00:00:00Z" }]);
  }
  return ok(body === undefined ? [] : []);
}

beforeEach(() => {
  vi.resetModules();
  vi.stubEnv("VITE_INSTANCE_CONTROL_ENABLED", "true");
  vi.stubGlobal("crypto", { randomUUID: () => `key-${sent.length}` });
  sessionStorage.setItem("rag-brain-admin-key", "test-key");

  sent = [];
  server = {
    groupStatus: "SUCCEEDED", members: [member()],
    preflightAcceptable: true, preflightBlockers: [],
    collectionVersion: 7, pointerVersion: 4,
    promotionAllowed: true, promotionBlockers: [], moveFails: null,
  };

  vi.stubGlobal("fetch", vi.fn(async (input: string, init: RequestInit = {}) => {
    const url = String(input);
    const method = (init.method ?? "GET").toUpperCase();
    const headers = new Headers(init.headers);
    const body = typeof init.body === "string" ? JSON.parse(init.body) : init.body;
    sent.push({ method, url, body, key: headers.get("Idempotency-Key") });
    return respond(method, url, body);
  }));
});

afterEach(() => {
  vi.unstubAllEnvs();
  vi.unstubAllGlobals();
  sessionStorage.clear();
  window.location.hash = "";
});

async function renderAt(path: string) {
  window.location.hash = `#${path}`;
  const App = (await import("../App")).default;
  return render(<App />);
}

/** Every request that actually went to the wire for one path fragment. */
function requestsFor(fragment: string) {
  return sent.filter((request) => request.url.includes(fragment));
}

/**
 * Build a two-member release comparison and run it.
 *
 * Compare does not adopt an existing group — a results table only exists because this browser
 * submitted the comparison — so reaching the results means going through the whole flow.
 */
async function runAComparison() {
  await renderAt(`/instances/${BRAIN}/income/compare`);

  await userEvent.type(await screen.findByLabelText("Package ID"), PACKAGE);
  await userEvent.click(screen.getByRole("button", { name: "Verify parse" }));
  await screen.findByText("Selected parse");
  await userEvent.click(await screen.findByRole("button", { name: "Freeze corpus" }));
  await screen.findByText(/Frozen\./);

  await userEvent.selectOptions(
    await screen.findByLabelText("Member 1 release"), RELEASE_CANDIDATE);
  await userEvent.selectOptions(screen.getByLabelText("Member 2 release"), RELEASE_LIVE);

  const create = await screen.findByRole("button", { name: "Create group" });
  await waitFor(() => expect(create).toHaveProperty("disabled", false));
  await userEvent.click(create);
}

describe("instance control, end to end", () => {
  it("runs an instance from the landing page and reports what it cost", async () => {
    await renderAt("/instances");

    // Landing lists across brains without any brain being "current".
    await userEvent.click(await screen.findByRole("link", { name: /Income/ }));
    await screen.findByRole("heading", { name: "Workbench" });

    await userEvent.type(await screen.findByLabelText("Package ID"), PACKAGE);
    await userEvent.click(screen.getByRole("button", { name: "Verify parse" }));
    await screen.findByText("Selected parse");

    await userEvent.click(await screen.findByRole("button", { name: "Freeze corpus" }));
    await screen.findByText(/Frozen\./);

    const run = await screen.findByRole("button", { name: "Run" });
    await waitFor(() => expect(run).toHaveProperty("disabled", false));
    await userEvent.click(run);

    await screen.findByText("Result");

    // The submission pinned all four, explicitly, with the brain as a UUID.
    const created = requestsFor("/run-groups?")[0];
    expect(created.url).toContain(`brain=${BRAIN}`);
    expect(created.key).toBeTruthy();
    expect((created.body as { members: unknown[] }).members).toEqual([{
      instanceSlug: "income", releaseId: RELEASE_LIVE,
      registrationId: REGISTRATION, corpusSnapshotId: SNAPSHOT,
    }]);

    // Expected and actual side by side, which is the point of keeping both.
    expect(screen.getByText("Actual est. cost").closest("div")!.textContent).toContain("$0.02");
    expect(screen.getByText("Expected cost").closest("div")!.textContent).toContain("$0.01");

    // A terminal group is asked about once and then left alone. The fragment is deliberately
    // tight: the run-pinned discussion route contains the group id as well.
    expect(requestsFor(`/run-groups/${GROUP}?`)).toHaveLength(1);
  });

  it("never lets the sidebar's brain slug reach an instance-control call", async () => {
    window.location.hash = "#/instances?brain=mortgage";
    await renderAt("/instances?brain=mortgage");

    await screen.findByRole("link", { name: /Income/ });

    // The legacy injector matches `/api/ai/admin/instances`, and these routes take a UUID. A
    // regression here leaves every screen working alone and the product reading another brain.
    for (const request of requestsFor("/api/ai/admin/instances")) {
      expect(request.url).not.toContain("brain=mortgage");
    }
    expect(requestsFor("/api/ai/admin/instances").length).toBeGreaterThan(0);
  });

  it("refuses to start a run the server priced as unaffordable", async () => {
    server.preflightAcceptable = false;
    server.preflightBlockers = ["BUDGET_EXCEEDED"];

    await renderAt(`/instances/${BRAIN}/income/workbench`);

    await userEvent.type(await screen.findByLabelText("Package ID"), PACKAGE);
    await userEvent.click(screen.getByRole("button", { name: "Verify parse" }));
    await userEvent.click(await screen.findByRole("button", { name: "Freeze corpus" }));

    await screen.findByText("BUDGET_EXCEEDED");
    expect(screen.getByRole("button", { name: "Run" })).toHaveProperty("disabled", true);
    // Nothing was created, which is the only thing that matters here.
    expect(requestsFor("/run-groups?")).toHaveLength(0);
  });

  it("keeps a member that worked when its sibling failed", async () => {
    server.groupStatus = "PARTIAL";
    server.members = [
      member(),
      member({ memberIndex: 1, runId: "run-2", status: "FAILED",
        failureCode: "PROVIDER_FAILED", result: null, actualInputTokens: null,
        actualCachedTokens: null, actualOutputTokens: null, actualTotalTokens: null,
        actualCostUsd: null, usageQuality: "UNAVAILABLE" }),
    ];

    await runAComparison();

    await screen.findByText("PARTIAL");
    // Scoped past the estimate table, which stays on screen beside the results by design.
    const table = screen.getByRole("table", { name: /Result, expected and actual/ });
    // Three facts at once: the group is partial rather than failed, the failure carries its own
    // code, and the sibling's answer survived.
    expect(within(table).getByText("PROVIDER_FAILED")).toBeTruthy();
    expect(within(table).getByText("SUCCEEDED")).toBeTruthy();
    expect(screen.getByText("Member 1 output")).toBeTruthy();
  });

  it("shows an unreported usage category as unavailable, never as zero", async () => {
    server.members = [member({ actualCachedTokens: null, usageQuality: "REPORTED" })];

    await runAComparison();

    const table = await screen.findByRole("table", { name: /Result, expected and actual/ });
    const cached = within(table).getByText("Cached input").closest("th")!;
    const column = Array.from(cached.parentElement!.children).indexOf(cached);
    const cell = within(table).getAllByRole("row")[1].children[column];
    // `Unavailable` and `0 tokens` are different facts, and an invoice will disagree with one.
    expect(cell.textContent).toContain("Unavailable");
    expect(cell.textContent).not.toContain("0");
  });

  it("evaluates a candidate, promotes it, and records why", async () => {
    await renderAt(`/instances/${BRAIN}/income/releases`);

    await userEvent.click(await screen.findByRole("button", { name: "Evaluate r4" }));
    await screen.findByText("Passed");

    await userEvent.click(
      await screen.findByRole("button", { name: "Review and apply to live" }));
    await userEvent.type(
      screen.getByLabelText(/^Change reason/), "promoting the evaluated income candidate");
    await userEvent.click(screen.getByRole("button", { name: "Apply to live" }));

    await waitFor(() => expect(requestsFor("/apply-to-live").length).toBe(1));
    const move = requestsFor("/apply-to-live")[0];
    const body = move.body as Record<string, unknown>;
    // Both halves of the compare-and-set, and a reason that will still mean something later.
    expect(body.expectedLiveReleaseId).toBe(RELEASE_LIVE);
    expect(body.expectedPointerVersion).toBe(4);
    expect(body.changeReason).toBe("promoting the evaluated income candidate");
    expect(move.key).toBeTruthy();
  });

  it("does not move the pointer when somebody else moved it first", async () => {
    server.moveFails = "LIVE_POINTER_CHANGED";

    await renderAt(`/instances/${BRAIN}/income/releases`);

    await userEvent.click(
      await screen.findByRole("button", { name: "Review and apply to live" }));
    await userEvent.type(
      screen.getByLabelText(/^Change reason/), "promoting the evaluated income candidate");
    await userEvent.click(screen.getByRole("button", { name: "Apply to live" }));

    await screen.findByText(/Production changed while this page was open/);
    // One attempt, and no dialog left open to press again by reflex.
    expect(requestsFor("/apply-to-live")).toHaveLength(1);
    expect(screen.queryByRole("dialog")).toBeNull();
  });

  it("surfaces a collection that moved since the release pinned it", async () => {
    server.collectionVersion = 9;

    await renderAt(`/instances/${BRAIN}/income/configuration`);

    // The release still retrieves v7; promotion will refuse until a release pins the current one.
    await screen.findByText(/still retrieves v7/);
    expect(screen.getByText("Moved on")).toBeTruthy();
  });

  it("shows every promotion gate the server returned", async () => {
    server.promotionAllowed = false;
    server.promotionBlockers = ["EVALUATION_NOT_RUN", "CORPUS_COLLECTION_VERSION_STALE"];

    await renderAt(`/instances/${BRAIN}/income/releases`);

    await screen.findByText("EVALUATION_NOT_RUN");
    expect(screen.getByText("CORPUS_COLLECTION_VERSION_STALE")).toBeTruthy();
    expect(screen.getByRole("button", { name: "Review and apply to live" }))
      .toHaveProperty("disabled", true);
  });

  it("carries the brain and instance from the URL, with no sidebar involved", async () => {
    await renderAt(`/instances/${BRAIN}/income/workbench`);

    await screen.findByRole("heading", { name: "Workbench" });
    // A workspace URL is shareable precisely because this holds.
    const detail = requestsFor("/instances/income?")[0];
    expect(detail.url).toContain(`brain=${BRAIN}`);
  });
});

/**
 * The parts of accessibility a mounted app can actually be asked about.
 *
 * Focus return, accessible names, live-region announcements and colour-independent status are all
 * decidable here and all easy to lose in a refactor. Zoom, viewport width and reduced motion are
 * not decidable in jsdom — those are CSS behaviours verified by reading the stylesheet and by
 * looking at a browser, and pretending otherwise with a passing test would be worse than not
 * testing them.
 */
describe("instance control, accessibility", () => {
  it("returns focus to whatever opened the live-change dialog", async () => {
    await renderAt(`/instances/${BRAIN}/income/releases`);

    const trigger = await screen.findByRole("button", { name: "Review and apply to live" });
    await userEvent.click(trigger);
    await screen.findByRole("dialog");

    await userEvent.click(screen.getByRole("button", { name: "Cancel" }));

    // A confirmation a keyboard user has to hunt their way back from is one they learn to skip.
    await waitFor(() => expect(document.activeElement).toBe(trigger));
  });

  it("moves focus into the dialog rather than leaving it behind the backdrop", async () => {
    await renderAt(`/instances/${BRAIN}/income/releases`);

    await userEvent.click(
      await screen.findByRole("button", { name: "Review and apply to live" }));

    const dialog = await screen.findByRole("dialog");
    expect(dialog.contains(document.activeElement)).toBe(true);
    expect(dialog.getAttribute("aria-modal")).toBe("true");
  });

  it("gives every control on the workbench an accessible name", async () => {
    await renderAt(`/instances/${BRAIN}/income/workbench`);
    await screen.findByRole("heading", { name: "Workbench" });

    // An unlabelled input is unreachable by voice and unreadable by a screen reader, and the way
    // it happens is a field added without its label.
    for (const control of [
      ...screen.queryAllByRole("textbox"),
      ...screen.queryAllByRole("combobox"),
      ...screen.queryAllByRole("button"),
      ...screen.queryAllByRole("checkbox"),
    ]) {
      const name = control.getAttribute("aria-label")
        ?? control.textContent?.trim()
        ?? "";
      const labelled = name.length > 0
        || document.querySelector(`label[for="${control.id}"]`) !== null
        || control.closest("label") !== null;
      expect(labelled, `${control.tagName} without an accessible name`).toBe(true);
    }
  });

  it("announces a run's state in a live region rather than only in colour", async () => {
    server.groupStatus = "PARTIAL";
    server.members = [
      member(),
      member({ memberIndex: 1, runId: "run-2", status: "FAILED",
        failureCode: "PROVIDER_FAILED", result: null, usageQuality: "UNAVAILABLE",
        actualInputTokens: null, actualCachedTokens: null, actualOutputTokens: null,
        actualTotalTokens: null, actualCostUsd: null }),
    ];

    await runAComparison();
    await screen.findByText("PARTIAL");

    // Announced, not merely coloured.
    expect(screen.getAllByRole("status").length).toBeGreaterThan(0);

    // And every status badge carries its own word, so a red pill and a green pill are still
    // distinguishable to anyone who cannot tell them apart by colour.
    for (const badge of Array.from(document.querySelectorAll(".badge"))) {
      expect(badge.textContent?.trim().length ?? 0).toBeGreaterThan(0);
    }
  });

  it("reports a failure through an alert rather than a silent empty state", async () => {
    server.preflightAcceptable = false;
    server.preflightBlockers = ["BUDGET_EXCEEDED"];

    await renderAt(`/instances/${BRAIN}/income/workbench`);
    await userEvent.type(await screen.findByLabelText("Package ID"), PACKAGE);
    await userEvent.click(screen.getByRole("button", { name: "Verify parse" }));
    await userEvent.click(await screen.findByRole("button", { name: "Freeze corpus" }));

    const refusal = await screen.findByRole("alert");
    expect(refusal.textContent).toContain("BUDGET_EXCEEDED");
  });

  it("reaches the primary action by keyboard alone", async () => {
    await renderAt(`/instances/${BRAIN}/income/workbench`);
    const field = await screen.findByLabelText("Package ID");

    field.focus();
    await userEvent.keyboard(PACKAGE);
    // Tab order runs through the form to its submit rather than around it.
    await userEvent.tab();
    await userEvent.tab();
    await userEvent.tab();
    await waitFor(() =>
      expect((document.activeElement as HTMLElement)?.textContent).toContain("Verify parse"));
  });
});
