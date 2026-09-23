import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import App from "../App";
import IncomeLab from "./IncomeLab";

/**
 * These tests drive the real api client against a stubbed `fetch`, rather than mocking `../api`.
 * The point of Task 7 is largely the wire behaviour — the `Idempotency-Key` header, the brain
 * query on a multipart body, one engine call on a same-key retry — and a module mock would assert
 * that the screen called a function, not that the request was right.
 */

const ADMIN_KEY = "test-admin-key";
const PACKAGE_ID = "55555555-5555-4555-8555-555555555555";
const JOB_ID = "66666666-6666-4666-8666-666666666666";
const REGISTRATION_ID = "44444444-4444-4444-8444-444444444444";
const RUN_ID = "77777777-7777-4777-8777-777777777777";
const OLDER_RUN_ID = "88888888-8888-4888-8888-888888888888";
const PAGE_A = "11111111-1111-4111-8111-111111111111";
const DOC_A = "33333333-3333-4333-8333-333333333333";

/** More significant digits than an IEEE-754 double can hold. */
const DECIMAL_CANARY = "12345678901234567890.123456789";

const PROTOTYPE = {
  code: "PROTOTYPE_LIVE_DEPENDENCIES",
  liveDependencies: [
    "CALCULATOR_IMPLEMENTATION", "CORPUS_CONTENTS", "MODEL_INFERENCE_BEHAVIOR",
    "MODEL_OUTPUT_TOKEN_BUDGET", "MODEL_PROVIDER_FALLBACK", "MODEL_PROVIDER_SELECTION",
    "RETRIEVAL_RANKING",
  ],
};

const INSTANCES = {
  instances: [{
    slug: "income", analyzerSlug: "income-v2",
    productionReleaseId: "99999999-9999-4999-8999-999999999999",
    productionReleaseNumber: 1, productionManifestSha256: "c".repeat(64),
    driftDetected: false, candidateReleaseId: null, candidateManifestSha256: null,
    prototype: PROTOTYPE,
  }],
  prototype: PROTOTYPE,
};

const REGISTRATION = {
  registrationId: REGISTRATION_ID, packageId: PACKAGE_ID, jobId: JOB_ID,
  sourceId: "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", sourceCount: 1,
  duplicateShaPrefixes: [], created: true, prototype: PROTOTYPE,
};

function status(state: string, extra: Record<string, unknown> = {}) {
  return {
    registrationId: REGISTRATION_ID, packageId: PACKAGE_ID, jobId: JOB_ID,
    status: state, currentStage: state === "COMPLETED" ? "DONE" : "EXTRACT",
    analyzable: state === "COMPLETED" || state === "HUMAN_REVIEW_REQUIRED",
    warnings: state === "HUMAN_REVIEW_REQUIRED" ? ["HUMAN_REVIEW_REQUIRED"] : [],
    revisions: state === "PROCESSING" ? [] : [{
      revision: 1, processingJobId: JOB_ID, parseGeneration: 1,
      envelopeSchemaVersion: "1.0.0", envelopeSha256: "d".repeat(64), envelopeSizeBytes: 4096,
      sourceSetSha256: "e".repeat(64), reuseEligibility: "ELIGIBLE",
      createdAt: "2026-08-17T12:00:00Z",
    }],
    prototype: PROTOTYPE,
    ...extra,
  };
}

function envelopeBody(overrides: Record<string, unknown> = {}) {
  return {
    registrationId: REGISTRATION_ID, packageId: PACKAGE_ID, revision: 1, parseGeneration: 1,
    processingJobId: JOB_ID, envelopeVersion: "1.0.0",
    canonicalizationVersion: "DOCENGINE-C14N-1", envelopeSha256: "d".repeat(64),
    envelopeSizeBytes: 4096, sourceSetSha256: "e".repeat(64), reuseEligibility: "ELIGIBLE",
    compatible: true, rejection: null, warnings: [],
    pages: [{
      id: PAGE_A, packagePageIndex: 0, sourcePageIndex: 0, widthPt: 612, heightPt: 792,
      rotation: 0, textLayer: "DIGITAL", blank: false, duplicate: false,
      documentTypeCode: "PAYSTUB", classificationConfidence: 0.99, classificationMethod: "RULES",
    }],
    documents: [{
      id: DOC_A, documentTypeCode: "PAYSTUB", ordinal: 0, pageIds: [PAGE_A],
      fields: [
        {
          name: "grossPay", groupKey: "A", status: "FOUND", dataType: "MONEY",
          displayedText: "12,345,678,901,234,567,890.123456789", rawValue: null,
          normalizedText: null, normalizedNumber: DECIMAL_CANARY, normalizedDate: null,
          confidence: 0.97, method: "TEXT_LAYER", extractorVersion: "v3",
          validationStatus: "VALID", sensitive: false,
          evidence: [{ pageId: PAGE_A, role: "VALUE", ordinal: 0,
                       box: { x: 72, y: 700, width: 120, height: 12 } }],
        },
        {
          name: "ytdGross", groupKey: null, status: "MISSING", dataType: "MONEY",
          displayedText: null, rawValue: null, normalizedText: null, normalizedNumber: null,
          normalizedDate: null, confidence: 0, method: "NONE", extractorVersion: null,
          validationStatus: null, sensitive: false, evidence: [],
        },
      ],
    }],
    unassignedPageIds: [], prototype: PROTOTYPE,
    ...overrides,
  };
}

function runBody(overrides: Record<string, unknown> = {}) {
  return {
    runId: RUN_ID, instanceSlug: "income", status: "SUCCEEDED", failureCode: null,
    releaseId: "99999999-9999-4999-8999-999999999999", releaseNumber: 1,
    releaseManifestSha256: "c".repeat(64),
    analysisRunId: "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", registrationId: REGISTRATION_ID,
    source: {
      packageId: PACKAGE_ID, revision: 1, parseGeneration: 1, processingJobId: JOB_ID,
      envelopeVersion: "1.0.0", canonicalizationVersion: "DOCENGINE-C14N-1",
      envelopeSha256: "d".repeat(64), envelopeSizeBytes: 4096, sourceSetSha256: "e".repeat(64),
      reuseEligibility: "ELIGIBLE", documentCount: 1, pageCount: 1,
    },
    analysis: {
      status: "SUCCEEDED", reportMarkdown: "Qualifying monthly income is stated below.",
      findingsJson: `{"monthlyIncome":"${DECIMAL_CANARY}"}`,
      citations: [{ sourceName: "Selling Guide", documentName: "B3-3.1", section: "B3-3.1-01",
                    pageNumber: "12", effectiveDate: "2026-01-01" }],
      provider: "anthropic", model: "test-model", inputTokens: 1200, outputTokens: 340,
      costUsd: 0.0123, providerAttempts: 1, reason: null,
    },
    createdAt: "2026-08-17T12:01:00Z", terminalAt: "2026-08-17T12:01:20Z",
    replayed: false, prototype: PROTOTYPE,
    ...overrides,
  };
}

function summary(runId: string, overrides: Record<string, unknown> = {}) {
  return {
    runId, instanceSlug: "income", status: "SUCCEEDED", failureCode: null,
    releaseId: "99999999-9999-4999-8999-999999999999",
    analysisRunId: "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", packageId: PACKAGE_ID, revision: 1,
    createdAt: "2026-08-17T12:01:00Z", terminalAt: "2026-08-17T12:01:20Z",
    discussionExchangeCount: 0, prototype: PROTOTYPE,
    ...overrides,
  };
}

function discussionBody(exchanges: unknown[] = []) {
  return { runId: RUN_ID, exchanges, replayed: false, prototype: PROTOTYPE };
}

// ---------------------------------------------------------------- fetch routing

type Call = { url: string; method: string; headers: Headers; body: BodyInit | null | undefined };
type Handler = (call: Call) => Response | Promise<Response>;

let calls: Call[] = [];

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status });
}

/** Routes by "METHOD path-prefix"; the first matching entry wins. */
function stubFetch(routes: Array<[string, Handler]>) {
  const fetchMock = vi.fn(async (url: string, init: RequestInit = {}) => {
    const method = (init.method ?? "GET").toUpperCase();
    const call: Call = { url, method, headers: new Headers(init.headers), body: init.body };
    calls.push(call);
    for (const [pattern, handler] of routes) {
      const [wantMethod, prefix] = pattern.split(" ");
      if (method === wantMethod && url.startsWith(prefix)) return handler(call);
    }
    return json({ code: "UNROUTED_IN_TEST", correlationId: "none", counts: {} }, 500);
  });
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

function callsTo(prefix: string, method = "GET") {
  return calls.filter((c) => c.method === method && c.url.startsWith(prefix));
}

const REGISTER = "POST /api/ai/admin/lab/instances/income/documents";
const STATUS = "GET /api/ai/admin/lab/documents/";
const RUNS = "POST /api/ai/admin/lab/instances/income/runs";
const HISTORY = "GET /api/ai/admin/lab/runs?instance=income";

/** The happy path every panel test starts from. */
function happyRoutes(overrides: Array<[string, Handler]> = []): Array<[string, Handler]> {
  return [
    ...overrides,
    ["GET /api/ai/admin/lab/instances", () => json(INSTANCES)],
    [REGISTER, () => json(REGISTRATION, 201)],
    ["GET /api/ai/admin/lab/documents/", (c) =>
      json(c.url.includes("/envelope") ? envelopeBody() : status("COMPLETED"))],
    [RUNS, () => json(runBody(), 201)],
    [HISTORY, () => json({ runs: [summary(RUN_ID)], prototype: PROTOTYPE })],
    ["GET /api/ai/admin/lab/runs/", (c) =>
      json(c.url.includes("/messages") ? discussionBody() : runBody())],
    ["POST /api/ai/admin/lab/runs/", () => json(discussionBody([{
      exchangeId: "cccccccc-cccc-4ccc-8ccc-cccccccccccc", sequenceNumber: 1, status: "SUCCEEDED",
      failureCode: null, prototypeLimitations: "PROTOTYPE_LIVE_DEPENDENCIES",
      messages: [
        { role: "USER", ordinal: 1, body: "Which documents drove the figure?",
          createdAt: "2026-08-17T12:02:00Z" },
        { role: "ASSISTANT", ordinal: 2, body: "The paystub occurrence group A.",
          createdAt: "2026-08-17T12:02:05Z" },
      ],
    }]), 201)],
    ["DELETE /api/ai/admin/lab/runs/", () => json({
      runId: RUN_ID, deleted: true, messagesDeleted: 2, exchangesDeleted: 1, payloadsDeleted: 1,
      documentsDeleted: 1, analysisRunDeleted: true, enginePackageRetained: true,
      prototype: PROTOTYPE,
    })],
    ["GET /api/ai/admin/stats", () => json({
      brain: { id: "brain-1", companyName: "Synthetic Co", slug: "mortgage" },
      corpus: { activeDocuments: 1, totalDocuments: 1, chunks: 10 },
    })],
  ];
}

function pdf(name = "synthetic-paystub.pdf") {
  return new File([new Uint8Array([0x25, 0x50, 0x44, 0x46])], name, { type: "application/pdf" });
}

const store = new Map<string, string>();
let uuidSeq = 0;

beforeEach(() => {
  calls = [];
  uuidSeq = 0;
  store.clear();
  store.set("rag-brain-admin-key", ADMIN_KEY);
  vi.stubGlobal("sessionStorage", {
    getItem: (k: string) => store.get(k) ?? null,
    setItem: (k: string, v: string) => void store.set(k, v),
    removeItem: (k: string) => void store.delete(k),
  });
  vi.stubGlobal("crypto", { randomUUID: () => `key-${++uuidSeq}` });
  window.location.hash = "#/lab/income?brain=mortgage";
  vi.stubEnv("VITE_FOLDER_AI_LAB_ENABLED", "true");
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
  vi.useRealTimers();
});

async function openLab() {
  render(<IncomeLab />);
  await screen.findByRole("heading", { name: "Income lab" });
}

async function reachParseOutput(user: ReturnType<typeof userEvent.setup>) {
  await openLab();
  await user.upload(screen.getByLabelText("Document file"), pdf());
  await user.click(screen.getByRole("button", { name: "Register document" }));
  await screen.findByText("COMPLETED");
  await user.click(screen.getByRole("button", { name: "Parse output" }));
}

// ================================================================ routing and the flag

describe("Income lab routing", () => {
  it("renders the workbench at /#/lab/income and keeps the brain in the nav query", async () => {
    stubFetch(happyRoutes());
    render(<App />);

    expect(await screen.findByRole("heading", { name: "Income lab" })).toBeTruthy();
    const link = screen.getByRole("link", { name: "Income lab" }) as HTMLAnchorElement;
    expect(link.getAttribute("href")).toBe("#/lab/income?brain=mortgage");
    // The Lab's own reads carried the selected brain, not the default one.
    expect(calls.some((c) => c.url === "/api/ai/admin/lab/instances?brain=mortgage")).toBe(true);
  });

  it("leaves the nav, the routes, and the default redirect untouched when the flag is off",
    async () => {
      vi.stubEnv("VITE_FOLDER_AI_LAB_ENABLED", "");
      stubFetch(happyRoutes());
      render(<App />);

      await screen.findByRole("link", { name: "Test console" });
      expect(screen.queryByRole("link", { name: "Income lab" })).toBeNull();
      expect(screen.queryByRole("heading", { name: "Income lab" })).toBeNull();
      // /#/lab/income fell through to the pre-existing catch-all redirect, brain preserved.
      await waitFor(() => expect(window.location.hash).toBe("#/corpus?brain=mortgage"));
      expect(calls.some((c) => c.url.includes("/lab/"))).toBe(false);
    });

  it("survives instance control being switched on beside it", async () => {
    // The two build flags are independent switches over the same dashboard, and the combination
    // nobody tests is the one that breaks: instance control reorders the navigation and demotes
    // the technical screens, and a Lab entry that lived only in the old list would vanish from a
    // deployment that turned both on.
    vi.stubEnv("VITE_INSTANCE_CONTROL_ENABLED", "true");
    stubFetch(happyRoutes());
    render(<App />);

    await screen.findByRole("link", { name: "Instances" });
    // Grouped, not removed: the Lab keeps its slot in the Operate group.
    expect(screen.getByRole("link", { name: "Income lab" })).toBeTruthy();
  });

  it("still routes to the lab when instance control owns the catch-all", async () => {
    vi.stubEnv("VITE_INSTANCE_CONTROL_ENABLED", "true");
    stubFetch(happyRoutes());
    render(<App />);

    // With instance control on, an unknown path lands on Instances rather than Corpus. The Lab's
    // own route is declared, so it is not an unknown path.
    expect(await screen.findByRole("heading", { name: "Income lab" })).toBeTruthy();
    expect(calls.some((c) => c.url.includes("/lab/instances"))).toBe(true);
  });

  it("keeps every existing nav destination when the flag is on", async () => {
    stubFetch(happyRoutes());
    render(<App />);

    await screen.findByRole("link", { name: "Income lab" });
    for (const name of ["Corpus library", "Brains", "Connect", "Connectors", "Tools",
                        "Personality", "Models & providers", "Rules & guardrails", "Vocabulary",
                        "Source links", "Page guides", "Test console", "Audit log"]) {
      expect(screen.getByRole("link", { name })).toBeTruthy();
    }
  });
});

// ================================================================ prototype boundary

describe("Income lab prototype boundary", () => {
  it("shows every live dependency the server declared, on the code it declared them under",
    async () => {
      stubFetch(happyRoutes());
      await openLab();

      const banner = await screen.findByTestId("prototype-boundary");
      expect(within(banner).getByText("PROTOTYPE_LIVE_DEPENDENCIES")).toBeTruthy();
      for (const dependency of PROTOTYPE.liveDependencies) {
        expect(within(banner).getByText(dependency)).toBeTruthy();
      }
    });

  it("names the absent capabilities instead of showing disabled controls for them", async () => {
    stubFetch(happyRoutes());
    await openLab();

    const note = await screen.findByTestId("absent-capabilities");
    expect(note.textContent).toContain("Compare");
    expect(note.textContent).toContain("Score");
    expect(note.textContent).toContain("Scenario");
    expect(note.textContent).toContain("Promote");
    for (const name of ["Compare", "Score", "Scenario", "Promote"]) {
      expect(screen.queryByRole("button", { name })).toBeNull();
    }
  });
});

// ================================================================ registration and polling

describe("Income lab registration", () => {
  it("refuses more than one dropped file without touching the network", async () => {
    stubFetch(happyRoutes());
    await openLab();

    // The input itself is single-select, so the browser cannot hand it two files; a drop can.
    // That is the path this rejection exists for, and it must never reach the network.
    fireEvent.drop(screen.getByTestId("lab-drop"),
      { dataTransfer: { files: [pdf("a.pdf"), pdf("b.pdf")] } });

    expect(await screen.findByText(/exactly one file/i)).toBeTruthy();
    expect(callsTo("/api/ai/admin/lab/instances/income/documents", "POST")).toHaveLength(0);
    expect((screen.getByRole("button", { name: "Register document" }) as HTMLButtonElement)
      .disabled).toBe(true);
  });

  it("registers one file with an Idempotency-Key and the brain in the query, exactly once",
    async () => {
      const user = userEvent.setup();
      stubFetch(happyRoutes());
      await openLab();

      await user.upload(screen.getByLabelText("Document file"), pdf());
      await user.click(screen.getByRole("button", { name: "Register document" }));

      await screen.findByText("COMPLETED");
      const register = callsTo("/api/ai/admin/lab/instances/income/documents", "POST");
      expect(register).toHaveLength(1);
      expect(register[0].headers.get("Idempotency-Key")).toBe("key-1");
      expect(register[0].url).toBe(
        "/api/ai/admin/lab/instances/income/documents?brain=mortgage");
      expect(register[0].url.match(/brain=/g)).toHaveLength(1);
      expect(register[0].headers.get("Content-Type")).toBeNull();
    });

  it("reuses the same upload key across a retry, and mints a new one only when the file changes",
    async () => {
      const user = userEvent.setup();
      let attempt = 0;
      stubFetch(happyRoutes([[REGISTER, () => {
        attempt += 1;
        if (attempt === 1) return json({ code: "ENGINE_TIMEOUT", correlationId: "c1", counts: {} }, 504);
        return json(REGISTRATION, 201);
      }]]));
      await openLab();

      await user.upload(screen.getByLabelText("Document file"), pdf());
      await user.click(screen.getByRole("button", { name: "Register document" }));
      await screen.findByText("ENGINE_TIMEOUT");
      await user.click(screen.getByRole("button", { name: "Register document" }));
      await screen.findByText("COMPLETED");

      const register = callsTo("/api/ai/admin/lab/instances/income/documents", "POST");
      expect(register.map((c) => c.headers.get("Idempotency-Key"))).toEqual(["key-1", "key-1"]);

      await user.upload(screen.getByLabelText("Document file"), pdf("other.pdf"));
      await user.click(screen.getByRole("button", { name: "Register document" }));
      await waitFor(() => expect(
        callsTo("/api/ai/admin/lab/instances/income/documents", "POST")).toHaveLength(3));
      expect(callsTo("/api/ai/admin/lab/instances/income/documents", "POST")[2]
        .headers.get("Idempotency-Key")).toBe("key-2");
    });

  /**
   * Settles everything React has pending — resolved promises, the renders they queue and the
   * passive effects those renders schedule — WITHOUT letting a pending timer fire, so the poll
   * interval below is still measured against the fake clock rather than smuggled past it.
   *
   * `act` rather than a bare microtask drain: from React 19 the work a promise continuation
   * schedules outside `act` is handed to the scheduler, which yields through a MessageChannel
   * task, so awaiting `Promise.resolve()` any number of times never reaches it. `act` flushes
   * that work directly and, unlike `advanceTimersByTimeAsync`, moves no clock.
   */
  async function flush() {
    for (let i = 0; i < 20; i += 1) await act(async () => {});
  }

  it("polls the returned job at a bounded interval until it is terminal", async () => {
    let polls = 0;
    stubFetch(happyRoutes([[STATUS, (c) => {
      if (c.url.includes("/envelope")) return json(envelopeBody());
      polls += 1;
      return json(status(polls < 3 ? "PROCESSING" : "COMPLETED"));
    }]]));
    const user = userEvent.setup();
    await openLab();
    await user.upload(screen.getByLabelText("Document file"), pdf());

    // Freeze time BEFORE registration resolves, so the poll timer it schedules is a fake one.
    // fireEvent is used from here on because userEvent's own delay would need the real clock.
    vi.useFakeTimers();
    fireEvent.click(screen.getByRole("button", { name: "Register document" }));
    await flush();
    expect(polls).toBe(1);

    await vi.advanceTimersByTimeAsync(1999);
    await flush();
    expect(polls).toBe(1);

    await vi.advanceTimersByTimeAsync(1);
    await flush();
    expect(polls).toBe(2);

    await vi.advanceTimersByTimeAsync(2000);
    await flush();
    expect(polls).toBe(3);

    // COMPLETED is terminal, so no further poll is ever scheduled.
    await vi.advanceTimersByTimeAsync(60000);
    await flush();
    expect(polls).toBe(3);
  });

  it("cancels polling on unmount", async () => {
    let polls = 0;
    stubFetch(happyRoutes([[STATUS, (c) => {
      if (c.url.includes("/envelope")) return json(envelopeBody());
      polls += 1;
      return json(status("PROCESSING"));
    }]]));
    const user = userEvent.setup();
    const view = render(<IncomeLab />);
    await screen.findByLabelText("Document file");
    await user.upload(screen.getByLabelText("Document file"), pdf());

    // Frozen before registration resolves, so the poll timer under test is a fake one and
    // advancing the clock genuinely would fire it if the cleanup had not cleared it.
    vi.useFakeTimers();
    fireEvent.click(screen.getByRole("button", { name: "Register document" }));
    await flush();
    expect(polls).toBe(1);
    expect(vi.getTimerCount()).toBe(1);

    view.unmount();
    expect(vi.getTimerCount()).toBe(0);
    await vi.advanceTimersByTimeAsync(60000);
    await flush();
    expect(polls).toBe(1);
  });

  it("shows a review-required job as analyzable but warns prominently", async () => {
    const user = userEvent.setup();
    stubFetch(happyRoutes([[STATUS, (c) =>
      json(c.url.includes("/envelope") ? envelopeBody() : status("HUMAN_REVIEW_REQUIRED"))]]));
    await openLab();

    await user.upload(screen.getByLabelText("Document file"), pdf());
    await user.click(screen.getByRole("button", { name: "Register document" }));

    const warning = await screen.findByTestId("status-warnings");
    expect(within(warning).getByText("HUMAN_REVIEW_REQUIRED")).toBeTruthy();
    await waitFor(() => expect(
      (screen.getByRole("button", { name: "Run income analysis" }) as HTMLButtonElement).disabled)
      .toBe(false));
  });

  it("blocks analysis for a failed job", async () => {
    const user = userEvent.setup();
    stubFetch(happyRoutes([[STATUS, () => json(status("FAILED"))]]));
    await openLab();

    await user.upload(screen.getByLabelText("Document file"), pdf());
    await user.click(screen.getByRole("button", { name: "Register document" }));

    await screen.findByText("FAILED");
    expect((screen.getByRole("button", { name: "Run income analysis" }) as HTMLButtonElement)
      .disabled).toBe(true);
  });
});

// ================================================================ parse output

describe("Income lab parse output", () => {
  it("displays a normalized decimal byte-for-byte, with no IEEE-754 rounding", async () => {
    const user = userEvent.setup();
    stubFetch(happyRoutes());
    await reachParseOutput(user);

    expect(await screen.findByText(DECIMAL_CANARY)).toBeTruthy();
    expect(screen.queryByText(String(Number(DECIMAL_CANARY)))).toBeNull();
  });

  it("keeps a missing field visible with its explicit method and confidence", async () => {
    const user = userEvent.setup();
    stubFetch(happyRoutes());
    await reachParseOutput(user);

    const row = await screen.findByTestId("field-ytdGross");
    expect(within(row).getByText("missing")).toBeTruthy();
    expect(within(row).getByText("NONE")).toBeTruthy();
    expect(row.textContent).not.toBe("");
  });

  it("says the envelope cannot distinguish an unreadable grouped field from an ungrouped one",
    async () => {
      const user = userEvent.setup();
      stubFetch(happyRoutes());
      await reachParseOutput(user);

      const row = await screen.findByTestId("field-ytdGross");
      expect(within(row).getByText("unknown")).toBeTruthy();
      const caveat = screen.getByTestId("grouping-caveat");
      expect(caveat.textContent).toContain("no grouping-kind member");
      expect(caveat.textContent?.toLowerCase()).toContain("cannot");
    });

  it("shows document type, group keys, evidence coordinates, and the read-only boundary",
    async () => {
      const user = userEvent.setup();
      stubFetch(happyRoutes());
      await reachParseOutput(user);

      const row = await screen.findByTestId("field-grossPay");
      expect(within(row).getByText("A")).toBeTruthy();
      expect(within(row).getByText("TEXT_LAYER")).toBeTruthy();
      expect(within(row).getByText(/p\. 1/)).toBeTruthy();
      expect(within(row).getByText(/72/)).toBeTruthy();
      expect(screen.getByText("PAYSTUB")).toBeTruthy();

      const readOnly = screen.getByTestId("parse-read-only");
      expect(readOnly.textContent).toContain("Read-only");
      for (const name of ["Correct", "Regroup", "Reclassify", "Reparse"]) {
        expect(screen.queryByRole("button", { name })).toBeNull();
      }
    });

  it("reports an incompatible envelope with its rejection code and refuses to run", async () => {
    const user = userEvent.setup();
    stubFetch(happyRoutes([[STATUS, (c) => json(c.url.includes("/envelope")
      ? envelopeBody({ compatible: false, rejection: "NO_SUPPORTED_DOCUMENT" })
      : status("COMPLETED"))]]));
    await reachParseOutput(user);

    expect(await screen.findByText("NO_SUPPORTED_DOCUMENT")).toBeTruthy();
    expect((screen.getByRole("button", { name: "Run income analysis" }) as HTMLButtonElement)
      .disabled).toBe(true);
  });

  it("puts wide parse tables in their own scroll container so a narrow viewport still works",
    async () => {
      const user = userEvent.setup();
      stubFetch(happyRoutes());
      await reachParseOutput(user);

      const scrollers = await screen.findAllByTestId("lab-scroll");
      expect(scrollers.length).toBeGreaterThan(0);
    });
});

// ================================================================ analysis

describe("Income lab analysis", () => {
  async function runAnalysis(user: ReturnType<typeof userEvent.setup>) {
    await reachParseOutput(user);
    await user.click(screen.getByRole("button", { name: "AI analysis" }));
    await user.click(screen.getByRole("button", { name: "Run income analysis" }));
  }

  it("starts one run per explicit action, each with its own key", async () => {
    const user = userEvent.setup();
    stubFetch(happyRoutes());
    await runAnalysis(user);
    await screen.findByText("Qualifying monthly income is stated below.");

    await user.click(screen.getByRole("button", { name: "Run income analysis" }));
    await waitFor(() => expect(callsTo(
      "/api/ai/admin/lab/instances/income/runs", "POST")).toHaveLength(2));

    const runs = callsTo("/api/ai/admin/lab/instances/income/runs", "POST");
    expect(runs[0].headers.get("Idempotency-Key")).toBe("key-2");
    expect(runs[1].headers.get("Idempotency-Key")).toBe("key-3");
    // The second analysis reused the parse: no new registration and no new envelope generation.
    expect(callsTo("/api/ai/admin/lab/instances/income/documents", "POST")).toHaveLength(1);
    expect(JSON.parse(runs[1].body as string)).toEqual({ packageId: PACKAGE_ID, revision: 1 });
  });

  it("shows the terminal status, report, findings, citations, cost, and pinned identities",
    async () => {
      const user = userEvent.setup();
      stubFetch(happyRoutes());
      await runAnalysis(user);

      const panel = await screen.findByTestId("analysis-panel");
      expect(within(panel).getByText("SUCCEEDED")).toBeTruthy();
      expect(within(panel).getByText("Qualifying monthly income is stated below.")).toBeTruthy();
      expect(within(panel).getByText(/monthlyIncome/)).toBeTruthy();
      expect(within(panel).getByText(/B3-3.1-01/)).toBeTruthy();
      expect(within(panel).getByText(/anthropic/)).toBeTruthy();
      expect(within(panel).getByText(/1200/)).toBeTruthy();
      expect(within(panel).getByText(/revision 1/)).toBeTruthy();
      expect(within(panel).getByText(/dddddddd/)).toBeTruthy();
      expect(within(panel).getByText("PROTOTYPE_LIVE_DEPENDENCIES")).toBeTruthy();
    });

  it("renders a 422 parsed-analysis failure as a code, correlation id, and counts — no prose",
    async () => {
      const user = userEvent.setup();
      stubFetch(happyRoutes([[RUNS, () => json({
        code: "PARSED_INPUT_INCOMPATIBLE", correlationId: "corr-42", counts: { documents: 0 },
      }, 422)]]));
      await runAnalysis(user);

      const failure = await screen.findByTestId("lab-failure");
      expect(within(failure).getByText("PARSED_INPUT_INCOMPATIBLE")).toBeTruthy();
      expect(within(failure).getByText(/corr-42/)).toBeTruthy();
      expect(within(failure).getByText(/documents/)).toBeTruthy();
      expect(failure.textContent).toContain("code, a correlation id, and counts");
    });
});

// ================================================================ configuration states

describe("Income lab configuration states", () => {
  it("renders the retention 503 as a configuration state, not a generic failure", async () => {
    stubFetch(happyRoutes([["GET /api/ai/admin/lab/instances", () => json({
      code: "RETENTION_NOT_CONFIGURED", correlationId: "corr-503", counts: {},
    }, 503)]]));
    await openLab();

    const config = await screen.findByTestId("lab-unconfigured");
    expect(within(config).getByText("RETENTION_NOT_CONFIGURED")).toBeTruthy();
    expect(config.textContent).toContain("ragbrain.lab.retention-days");
    expect(within(config).getByText(/corr-503/)).toBeTruthy();
    // No upload affordance is offered while the Lab cannot serve a request.
    expect(screen.queryByLabelText("Document file")).toBeNull();
  });

  it("renders the crypto-unavailable 503 as a configuration state too", async () => {
    stubFetch(happyRoutes([["GET /api/ai/admin/lab/instances", () => json({
      code: "KEY_UNAVAILABLE", correlationId: "corr-key", counts: {},
    }, 503)]]));
    await openLab();

    const config = await screen.findByTestId("lab-unconfigured");
    expect(within(config).getByText("KEY_UNAVAILABLE")).toBeTruthy();
  });

  it("admits it has no further detail for a code it does not recognise", async () => {
    stubFetch(happyRoutes([["GET /api/ai/admin/lab/instances", () => json({
      code: "SOME_FUTURE_CODE", correlationId: "corr-x", counts: {},
    }, 500)]]));
    await openLab();

    const failure = await screen.findByTestId("lab-failure");
    expect(within(failure).getByText("SOME_FUTURE_CODE")).toBeTruthy();
    expect(failure.textContent).toContain("code, a correlation id, and counts");
  });

  it("shows a loading state before the instances read resolves", async () => {
    let release: (() => void) | null = null;
    const gate = new Promise<void>((resolve) => { release = resolve; });
    stubFetch(happyRoutes([["GET /api/ai/admin/lab/instances",
      async () => { await gate; return json(INSTANCES); }]]));
    render(<IncomeLab />);

    expect(await screen.findByTestId("lab-loading")).toBeTruthy();
    release!();
    await waitFor(() => expect(screen.queryByTestId("lab-loading")).toBeNull());
  });
});

// ================================================================ history and discussion

describe("Income lab history and discussion", () => {
  async function openHistory(user: ReturnType<typeof userEvent.setup>) {
    await openLab();
    await user.click(screen.getByRole("button", { name: "Discussion & history" }));
  }

  it("says so plainly when there is no history yet", async () => {
    const user = userEvent.setup();
    stubFetch(happyRoutes([[HISTORY, () => json({ runs: [], prototype: PROTOTYPE })]]));
    await openHistory(user);

    expect(await screen.findByText(/No runs yet/i)).toBeTruthy();
  });

  it("selects a prior run without re-registering, re-parsing, or re-running", async () => {
    const user = userEvent.setup();
    stubFetch(happyRoutes([[HISTORY, () => json({
      runs: [summary(RUN_ID), summary(OLDER_RUN_ID)], prototype: PROTOTYPE,
    })]]));
    await openHistory(user);

    await user.click(await screen.findByRole("button", { name: `Open run ${OLDER_RUN_ID.slice(0, 8)}` }));

    await waitFor(() => expect(
      callsTo(`/api/ai/admin/lab/runs/${OLDER_RUN_ID}`)).not.toHaveLength(0));
    expect(callsTo("/api/ai/admin/lab/instances/income/documents", "POST")).toHaveLength(0);
    expect(callsTo("/api/ai/admin/lab/instances/income/runs", "POST")).toHaveLength(0);
    expect(calls.filter((c) => c.url.includes("/envelope"))).toHaveLength(0);
  });

  it("runs a new analysis on a registration already visible in history, reusing its parse",
    async () => {
      const user = userEvent.setup();
      stubFetch(happyRoutes());
      await openHistory(user);

      // The prototype has no box for typing an arbitrary package or revision id; this row IS the
      // reuse path for a registration this brain already owns.
      await user.click(
        await screen.findByRole("button", { name: `Run again from ${RUN_ID.slice(0, 8)}` }));

      await waitFor(() => expect(callsTo("/api/ai/admin/lab/instances/income/runs", "POST")).toHaveLength(1));
      const [started] = callsTo("/api/ai/admin/lab/instances/income/runs", "POST");
      expect(JSON.parse(started.body as string)).toEqual({ packageId: PACKAGE_ID, revision: 1 });
      expect(started.headers.get("Idempotency-Key")).toBe("key-1");
      // The existing parse is reused: nothing was registered and no envelope generation happened.
      expect(callsTo("/api/ai/admin/lab/instances/income/documents", "POST")).toHaveLength(0);
      expect(calls.filter((c) => c.url.includes("/envelope"))).toHaveLength(0);
      expect(await screen.findByTestId("analysis-panel")).toBeTruthy();
    });

  it("offers no rerun for a history row that pins no package or revision", async () => {
    const user = userEvent.setup();
    stubFetch(happyRoutes([[HISTORY, () => json({
      runs: [summary(RUN_ID, { packageId: null, revision: null, status: "FAILED" })],
      prototype: PROTOTYPE,
    })]]));
    await openHistory(user);
    await screen.findByRole("button", { name: `Open run ${RUN_ID.slice(0, 8)}` });

    expect(screen.queryByRole("button", { name: `Run again from ${RUN_ID.slice(0, 8)}` }))
      .toBeNull();
  });

  it("loads a run's transcript and appends one turn per Send, each with its own key", async () => {
    const user = userEvent.setup();
    stubFetch(happyRoutes());
    await openHistory(user);

    await user.click(await screen.findByRole("button", { name: `Open run ${RUN_ID.slice(0, 8)}` }));
    await screen.findByRole("textbox", { name: "Discussion question" });

    await user.type(screen.getByRole("textbox", { name: "Discussion question" }),
      "Which documents drove the figure?");
    await user.click(screen.getByRole("button", { name: "Send" }));

    expect(await screen.findByText("The paystub occurrence group A.")).toBeTruthy();
    const posts = callsTo(`/api/ai/admin/lab/runs/${RUN_ID}/messages`, "POST");
    expect(posts).toHaveLength(1);
    expect(posts[0].headers.get("Idempotency-Key")).toBe("key-1");
    expect(JSON.parse(posts[0].body as string))
      .toEqual({ question: "Which documents drove the figure?" });
  });

  it("says the discussion is pinned to the selected run while corpus and model stay live",
    async () => {
      const user = userEvent.setup();
      stubFetch(happyRoutes());
      await openHistory(user);
      await user.click(await screen.findByRole("button", { name: `Open run ${RUN_ID.slice(0, 8)}` }));

      const note = await screen.findByTestId("discussion-pinning");
      expect(note.textContent).toContain(RUN_ID);
      expect(note.textContent).toContain("CORPUS_CONTENTS");
    });

  it("renders a 409 in-progress exchange from its code alone", async () => {
    const user = userEvent.setup();
    stubFetch(happyRoutes([["POST /api/ai/admin/lab/runs/", () => json({
      code: "DISCUSSION_EXCHANGE_IN_PROGRESS", correlationId: "corr-409", counts: { pending: 1 },
    }, 409)]]));
    await openHistory(user);
    await user.click(await screen.findByRole("button", { name: `Open run ${RUN_ID.slice(0, 8)}` }));
    await user.type(screen.getByRole("textbox", { name: "Discussion question" }), "hi");
    await user.click(screen.getByRole("button", { name: "Send" }));

    const failure = await screen.findByTestId("lab-failure");
    expect(within(failure).getByText("DISCUSSION_EXCHANGE_IN_PROGRESS")).toBeTruthy();
    expect(within(failure).getByText(/corr-409/)).toBeTruthy();
  });

  it("requires a confirmation before purging, then removes the run only after the call succeeds",
    async () => {
      const user = userEvent.setup();
      stubFetch(happyRoutes());
      await openHistory(user);
      await screen.findByRole("button", { name: `Open run ${RUN_ID.slice(0, 8)}` });

      await user.click(screen.getByRole("button", { name: `Delete run ${RUN_ID.slice(0, 8)}` }));
      // Still there: one click arms, it does not delete.
      expect(callsTo(`/api/ai/admin/lab/runs/${RUN_ID}`, "DELETE")).toHaveLength(0);
      expect(screen.getByRole("button", { name: `Open run ${RUN_ID.slice(0, 8)}` })).toBeTruthy();

      const confirm = screen.getByRole("button", { name: `Confirm delete ${RUN_ID.slice(0, 8)}` });
      expect(confirm.textContent).toBeTruthy();
      await user.click(confirm);

      await waitFor(() => expect(
        screen.queryByRole("button", { name: `Open run ${RUN_ID.slice(0, 8)}` })).toBeNull());
      expect(callsTo(`/api/ai/admin/lab/runs/${RUN_ID}`, "DELETE")).toHaveLength(1);
      expect(screen.getByTestId("purge-note").textContent)
        .toContain("Document Engine package");
    });

  it("keeps the run listed when the purge call fails", async () => {
    const user = userEvent.setup();
    stubFetch(happyRoutes([["DELETE /api/ai/admin/lab/runs/", () => json({
      code: "RUN_IN_PROGRESS", correlationId: "corr-409", counts: {},
    }, 409)]]));
    await openHistory(user);
    await screen.findByRole("button", { name: `Delete run ${RUN_ID.slice(0, 8)}` });

    await user.click(screen.getByRole("button", { name: `Delete run ${RUN_ID.slice(0, 8)}` }));
    await user.click(screen.getByRole("button", { name: `Confirm delete ${RUN_ID.slice(0, 8)}` }));

    expect(await screen.findByText("RUN_IN_PROGRESS")).toBeTruthy();
    expect(screen.getByRole("button", { name: `Open run ${RUN_ID.slice(0, 8)}` })).toBeTruthy();
  });
});

// ================================================================ keyboard and credentials

describe("Income lab safety", () => {
  it("offers a keyboard-operable file input alongside the drop zone", async () => {
    const user = userEvent.setup();
    stubFetch(happyRoutes());
    await openLab();

    const input = await screen.findByLabelText("Document file") as HTMLInputElement;
    expect(input.tagName).toBe("INPUT");
    expect(input.type).toBe("file");
    expect(input.multiple).toBe(false);
    expect(input.disabled).toBe(false);
    // Every panel is reachable and activated by the keyboard, not only by pointer.
    screen.getByRole("button", { name: "Parse output" }).focus();
    await user.keyboard("{Enter}");
    expect(await screen.findByTestId("parse-panel")).toBeTruthy();
  });

  it("never renders a credential or engine address, even if a response carried one", async () => {
    const user = userEvent.setup();
    // A deliberately polluted response: the real DTOs have no such members. The screen reads
    // named members only, so a stray one must not reach the DOM.
    stubFetch(happyRoutes([[REGISTER, () => json({
      ...REGISTRATION,
      engineBaseUrl: "http://host.docker.internal:9090",
      engineApiKey: "engine-secret-canary",
      filename: "private-borrower-paystub.pdf",
    }, 201)]]));
    await openLab();

    await user.upload(screen.getByLabelText("Document file"), pdf());
    await user.click(screen.getByRole("button", { name: "Register document" }));
    await screen.findByText("COMPLETED");

    const rendered = document.body.textContent ?? "";
    for (const canary of ["host.docker.internal", "9090", "engine-secret-canary",
                          "private-borrower-paystub.pdf", ADMIN_KEY]) {
      expect(rendered).not.toContain(canary);
    }
  });
});
