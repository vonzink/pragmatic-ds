import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

/**
 * The workbench spends money, so its tests are mostly about refusing to.
 *
 * Three of them are the load-bearing ones. A parse this release cannot analyze must not be
 * runnable at all. An estimate the server calls unacceptable must not be overridable from here.
 * And a retried action must carry the key its failed attempt carried, because the failure mode
 * that matters is not an error message — it is two provider calls billed for one click, with
 * nothing on screen to say it happened.
 *
 * Polling is mocked out. It has its own tests, and driving real timers through this screen would
 * make every assertion here depend on them.
 */

vi.mock("../api", () => ({
  instanceApi: {
    get: vi.fn(),
    post: vi.fn(),
    postIdempotent: vi.fn(),
    uploadIdempotent: vi.fn(),
  },
}));
vi.mock("../hooks/useRunGroupPolling", () => ({ useRunGroupPolling: vi.fn() }));

import { instanceApi } from "../api";
import { useRunGroupPolling } from "../hooks/useRunGroupPolling";
import InstanceWorkbench from "./InstanceWorkbench";

const BRAIN = "11111111-1111-4111-8111-111111111111";
const RELEASE = "33333333-3333-4333-8333-333333333333";
const PACKAGE = "44444444-4444-4444-8444-444444444444";
const REGISTRATION = "55555555-5555-4555-8555-555555555555";
const SNAPSHOT = "66666666-6666-4666-8666-666666666666";
const COLLECTION = "77777777-7777-4777-8777-777777777777";
const GROUP = "88888888-8888-4888-8888-888888888888";
const RUN = "99999999-9999-4999-8999-999999999999";

function instance(over: Partial<Record<string, unknown>> = {}) {
  return {
    brainId: BRAIN,
    slug: "income",
    displayName: "Income",
    purpose: "Analyze income.",
    state: "ACTIVE",
    liveReleaseNumber: 3,
    candidateCount: 0,
    manifestVersion: 2,
    provider: "anthropic",
    model: "claude-opus-5",
    collectionCount: 1,
    hasCandidateRelease: false,
    limitationCode: null,
    limitationFlags: [],
    createdAt: "2026-08-01T00:00:00Z",
    updatedAt: "2026-08-01T00:00:00Z",
    liveRelease: {
      releaseId: RELEASE,
      releaseNumber: 3,
      provenance: "WIZARD",
      live: true,
      manifestVersion: 2,
      provider: "anthropic",
      model: "claude-opus-5",
      collectionCount: 1,
      limitationCode: null,
      limitationFlags: [],
      createdAt: "2026-08-01T00:00:00Z",
    },
    ...over,
  } as never;
}

function pinned(over: Partial<Record<string, unknown>> = {}) {
  return {
    registrationId: REGISTRATION,
    brainId: BRAIN,
    instanceSlug: "income",
    packageId: PACKAGE,
    revision: 4,
    processingJobId: "job",
    parseGeneration: 1,
    envelopeVersion: "DOCENGINE-C14N-1",
    canonicalizationVersion: "1",
    envelopeSha256: "a".repeat(64),
    envelopeSizeBytes: 1024,
    sourceSetSha256: "b".repeat(64),
    selectedSourceIds: ["s1", "s2"],
    compatibility: {
      compatible: true, rejection: null, supportedDocumentCount: 2, warnings: [],
    },
    ...over,
  } as never;
}

function preflight(over: Partial<Record<string, unknown>> = {}) {
  return {
    requestSha256: "c".repeat(64),
    comparisonBasisSha256: null,
    members: [{
      memberIndex: 0,
      instanceSlug: "income",
      releaseId: RELEASE,
      registrationId: REGISTRATION,
      corpusSnapshotId: SNAPSHOT,
      provider: "anthropic",
      model: "claude-opus-5",
      pricingVersionId: "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
      inputTokensMin: 1000,
      inputTokensMax: 1400,
      outputTokensMin: 200,
      outputTokensMax: 400,
      costUsdMin: 0.01,
      costUsdMax: 0.03,
      estimateQuality: "EXACT",
    }],
    reservedMaximumUsd: 0.03,
    committedTodayUsd: 0,
    alreadyReservedUsd: 0,
    dailyBudgetUsd: 10,
    withinBudget: true,
    acceptable: true,
    blockingCodes: [],
    ...over,
  } as never;
}

function memberDetail(over: Partial<Record<string, unknown>> = {}) {
  return {
    memberIndex: 0,
    runId: RUN,
    instanceSlug: "income",
    releaseId: RELEASE,
    registrationId: REGISTRATION,
    corpusSnapshotId: SNAPSHOT,
    status: "SUCCEEDED",
    failureCode: null,
    provider: "anthropic",
    model: "claude-opus-5",
    pricingVersionId: "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
    expectedInputMin: 1000,
    expectedInputMax: 1400,
    expectedOutputMin: 200,
    expectedOutputMax: 400,
    expectedCostUsdMin: 0.01,
    expectedCostUsdMax: 0.03,
    estimateQuality: "EXACT",
    actualInputTokens: 1200,
    actualCachedTokens: null,
    actualOutputTokens: 300,
    actualTotalTokens: 1500,
    actualCostUsd: 0.02,
    usageQuality: "REPORTED",
    createdAt: "2026-08-20T10:00:00Z",
    terminalAt: "2026-08-20T10:01:00Z",
    result: { monthlyIncome: 5000 },
    ...over,
  };
}

function polled(members: unknown[], status = "SUCCEEDED") {
  return {
    detail: {
      group: {
        groupId: GROUP, brainId: BRAIN, mode: "INDEPENDENT", comparisonDimension: null,
        status, memberCount: members.length, createdAt: "2026-08-20T10:00:00Z",
        terminalAt: null, cancellationRequestedAt: null,
      },
      members,
    },
    loading: false,
    transientError: null,
    settled: true,
  } as never;
}

let keySeq = 0;

beforeEach(() => {
  vi.clearAllMocks();
  keySeq = 0;
  vi.stubGlobal("crypto", { randomUUID: () => `key-${++keySeq}` });
  vi.mocked(useRunGroupPolling).mockReturnValue(
    { detail: null, loading: false, transientError: null, settled: false });
  vi.mocked(instanceApi.get).mockImplementation(async (path: string) => {
    if (path.includes("corpus-collections")) {
      return [{
        id: COLLECTION, brainId: BRAIN, slug: "guidelines", displayName: "Guidelines",
        state: "ACTIVE", version: 7, clonedFromId: null, documentCount: 12,
      }] as never;
    }
    return [] as never;
  });
});

function renderBench(over: Partial<Record<string, unknown>> = {}) {
  return render(
    <InstanceWorkbench brainId={BRAIN} instanceSlug="income" instance={instance(over)} />);
}

/** Pin a parse and freeze a corpus, which is everything an estimate needs. */
async function prepare(parse = pinned()) {
  vi.mocked(instanceApi.postIdempotent).mockResolvedValueOnce(parse);
  await userEvent.type(screen.getByLabelText("Package ID"), PACKAGE);
  await userEvent.click(screen.getByRole("button", { name: "Verify parse" }));
  await screen.findByText("Selected parse");

  vi.mocked(instanceApi.postIdempotent).mockResolvedValueOnce({
    snapshotId: SNAPSHOT, brainId: BRAIN, manifestSha256: "e".repeat(64),
    collections: [{ collectionId: COLLECTION, collectionVersion: 7 }], documents: [],
  } as never);
  await userEvent.click(await screen.findByRole("button", { name: "Freeze corpus" }));
  await screen.findByText(/Frozen\./);
}

describe("InstanceWorkbench", () => {
  it("pins an existing parse and reports what was pinned", async () => {
    renderBench();
    vi.mocked(instanceApi.postIdempotent).mockResolvedValueOnce(pinned());

    await userEvent.type(screen.getByLabelText("Package ID"), PACKAGE);
    await userEvent.type(screen.getByLabelText(/^Revision/), "4");
    await userEvent.click(screen.getByRole("button", { name: "Verify parse" }));

    await waitFor(() => expect(instanceApi.postIdempotent).toHaveBeenCalled());
    const [path, body] = vi.mocked(instanceApi.postIdempotent).mock.calls[0];
    expect(path).toContain("/instances/income/parsed-inputs");
    expect(path).toContain(`brain=${BRAIN}`);
    expect(body).toEqual({ packageId: PACKAGE, revision: 4, selectedSourceIds: [] });

    const summary = (await screen.findByText("Selected parse")).closest("section")!;
    expect(summary.textContent).toContain(PACKAGE);
    // Two source ids were pinned; the count is what makes the selection checkable at a glance.
    expect(within(summary).getByText("Sources").closest("div")!.textContent).toContain("2");
  });

  it("uploads as a fallback and then pins the newest revision the engine produced", async () => {
    renderBench();
    vi.mocked(instanceApi.uploadIdempotent).mockResolvedValue({
      registrationId: "r", packageId: PACKAGE, processingJobId: "j", engineSourceId: "s",
      sourceCount: 1, duplicateShaPrefixes: [], created: true,
    } as never);
    vi.mocked(instanceApi.postIdempotent).mockResolvedValue(pinned());

    await userEvent.click(screen.getByRole("button", { name: /Upload and parse one/ }));
    await userEvent.upload(
      screen.getByLabelText(/^Document/), new File(["x"], "paystub.pdf"));
    await userEvent.click(screen.getByRole("button", { name: "Upload and parse" }));

    await waitFor(() => expect(instanceApi.uploadIdempotent).toHaveBeenCalled());
    const form = vi.mocked(instanceApi.uploadIdempotent).mock.calls[0][1] as FormData;
    // The endpoint takes exactly one part named `file` and rejects anything else.
    expect(form.getAll("file")).toHaveLength(1);

    // An upload has a package but no chosen revision, so pinning the newest one is a follow-on.
    await waitFor(() => expect(instanceApi.postIdempotent).toHaveBeenCalled());
    expect(vi.mocked(instanceApi.postIdempotent).mock.calls[0][1])
      .toEqual({ packageId: PACKAGE, revision: null, selectedSourceIds: [] });
  });

  it("carries one key through a retried upload rather than parsing twice", async () => {
    renderBench();
    vi.mocked(instanceApi.uploadIdempotent)
      .mockRejectedValueOnce(new Error("504"))
      .mockResolvedValue({
        registrationId: "r", packageId: PACKAGE, processingJobId: "j", engineSourceId: "s",
        sourceCount: 1, duplicateShaPrefixes: [], created: false,
      } as never);
    vi.mocked(instanceApi.postIdempotent).mockResolvedValue(pinned());

    await userEvent.click(screen.getByRole("button", { name: /Upload and parse one/ }));
    await userEvent.upload(
      screen.getByLabelText(/^Document/), new File(["x"], "paystub.pdf"));
    await userEvent.click(screen.getByRole("button", { name: "Upload and parse" }));
    await screen.findByRole("alert");
    await userEvent.click(screen.getByRole("button", { name: "Upload and parse" }));

    await waitFor(() => expect(instanceApi.uploadIdempotent).toHaveBeenCalledTimes(2));
    const keys = vi.mocked(instanceApi.uploadIdempotent).mock.calls.map((c) => c[2]);
    // A key per attempt would leave the engine holding two packages for one document, and the
    // second is indistinguishable afterwards from a deliberate re-parse.
    expect(keys[0]).toBe(keys[1]);
  });

  it("says the engine is still parsing, and rechecks under the same key", async () => {
    renderBench();
    vi.mocked(instanceApi.postIdempotent)
      .mockRejectedValueOnce(Object.assign(
        new Error("PARSE_REVISION_NOT_FOUND"), { code: "PARSE_REVISION_NOT_FOUND" }))
      .mockResolvedValue(pinned());

    await userEvent.type(screen.getByLabelText("Package ID"), PACKAGE);
    await userEvent.click(screen.getByRole("button", { name: "Verify parse" }));

    // "Not yet" is a different answer from "no", and only this code means the first one.
    await screen.findByText(/has not produced a revision yet/);
    await userEvent.click(screen.getByRole("button", { name: "Check again" }));

    await waitFor(() => expect(instanceApi.postIdempotent).toHaveBeenCalledTimes(2));
    const keys = vi.mocked(instanceApi.postIdempotent).mock.calls.map((c) => c[2]);
    expect(keys[0]).toBe(keys[1]);
  });

  it("refuses a parse this release cannot analyze", async () => {
    renderBench();
    vi.mocked(instanceApi.postIdempotent).mockResolvedValueOnce(pinned({
      compatibility: {
        compatible: false, rejection: "PARSE_DOCUMENT_TYPE_NOT_ALLOWED",
        supportedDocumentCount: 0, warnings: [],
      },
    }));

    await userEvent.type(screen.getByLabelText("Package ID"), PACKAGE);
    await userEvent.click(screen.getByRole("button", { name: "Verify parse" }));

    await screen.findByText(/PARSE_DOCUMENT_TYPE_NOT_ALLOWED/);
    // No corpus to freeze and nothing to estimate: the sequence stops rather than continuing to
    // a Run button that would be refused by the server anyway.
    expect(screen.queryByRole("button", { name: "Freeze corpus" })).toBeNull();
    expect(screen.getByRole("button", { name: "Run" })).toHaveProperty("disabled", true);
    expect(instanceApi.post).not.toHaveBeenCalled();
  });

  it("runs a parse that carries warnings, and names them", async () => {
    renderBench();
    vi.mocked(instanceApi.post).mockResolvedValue(preflight());
    await prepare(pinned({
      compatibility: {
        compatible: true, rejection: null, supportedDocumentCount: 2,
        warnings: [{
          code: "FIELD_UNRECOGNISED", documentOrdinal: 1, documentTypeCode: "PAYSTUB",
          fieldName: "employerPhone", groupKey: null,
        }],
      },
    }));

    expect(screen.getByText(/1 warning/)).toBeTruthy();
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "Run" })).toHaveProperty("disabled", false));
  });

  it("will not run until the server's own estimate says the submission is acceptable", async () => {
    renderBench();
    vi.mocked(instanceApi.post).mockResolvedValue(
      preflight({ acceptable: false, withinBudget: false, blockingCodes: ["BUDGET_EXCEEDED"] }));

    await prepare();

    await screen.findByText("BUDGET_EXCEEDED");
    expect(screen.getByRole("button", { name: "Run" })).toHaveProperty("disabled", true);
  });

  it("submits one INDEPENDENT member pinning release, parse and snapshot", async () => {
    renderBench();
    vi.mocked(instanceApi.post).mockResolvedValue(preflight());
    await prepare();

    await waitFor(() =>
      expect(screen.getByRole("button", { name: "Run" })).toHaveProperty("disabled", false));
    vi.mocked(instanceApi.postIdempotent).mockResolvedValueOnce(
      { groupId: GROUP, created: true, memberRunIds: [RUN] } as never);
    await userEvent.click(screen.getByRole("button", { name: "Run" }));

    await waitFor(() => expect(vi.mocked(instanceApi.postIdempotent).mock.calls.some(
      (c) => String(c[0]).includes("/run-groups?"))).toBe(true));
    const call = vi.mocked(instanceApi.postIdempotent).mock.calls
      .find((c) => String(c[0]).includes("/run-groups?"))!;
    expect(call[1]).toEqual({
      mode: "INDEPENDENT",
      comparisonDimension: null,
      // Every one of the four is explicit. None is inferred from a sidebar or from "the latest".
      members: [{
        instanceSlug: "income",
        releaseId: RELEASE,
        registrationId: REGISTRATION,
        corpusSnapshotId: SNAPSHOT,
      }],
    });
  });

  it("carries one key through a retried run rather than billing two", async () => {
    renderBench();
    vi.mocked(instanceApi.post).mockResolvedValue(preflight());
    await prepare();

    await waitFor(() =>
      expect(screen.getByRole("button", { name: "Run" })).toHaveProperty("disabled", false));
    vi.mocked(instanceApi.postIdempotent).mockRejectedValueOnce(new Error("502"));
    await userEvent.click(screen.getByRole("button", { name: "Run" }));

    const retry = await screen.findByRole("button", { name: "Retry run" });
    vi.mocked(instanceApi.postIdempotent).mockResolvedValueOnce(
      { groupId: GROUP, created: false, memberRunIds: [RUN] } as never);
    await userEvent.click(retry);

    const runCalls = vi.mocked(instanceApi.postIdempotent).mock.calls
      .filter((c) => String(c[0]).includes("/run-groups?"));
    expect(runCalls).toHaveLength(2);
    // The whole point of the header: a lost response resolves to the group the first attempt
    // already created instead of starting a second one.
    expect(runCalls[0][2]).toBe(runCalls[1][2]);
  });

  it("carries one key through a retried cancellation", async () => {
    renderBench();
    vi.mocked(instanceApi.post).mockResolvedValue(preflight());
    await prepare();

    await waitFor(() =>
      expect(screen.getByRole("button", { name: "Run" })).toHaveProperty("disabled", false));
    vi.mocked(instanceApi.postIdempotent).mockResolvedValueOnce(
      { groupId: GROUP, created: true, memberRunIds: [RUN] } as never);
    await userEvent.click(screen.getByRole("button", { name: "Run" }));

    const cancelButton = await screen.findByRole("button", { name: "Cancel run" });
    vi.mocked(instanceApi.postIdempotent).mockRejectedValueOnce(new Error("504"));
    await userEvent.click(cancelButton);
    await screen.findByText("504");

    vi.mocked(instanceApi.postIdempotent).mockResolvedValueOnce(
      { cancelledMembers: 1, stillProcessingMembers: 0 } as never);
    await userEvent.click(cancelButton);

    const cancelCalls = vi.mocked(instanceApi.postIdempotent).mock.calls
      .filter((c) => String(c[0]).includes("/cancel?"));
    expect(cancelCalls).toHaveLength(2);
    // A second key would send a second cancel command, and its honest answer — nothing left to
    // cancel — would read on screen as the cancellation having failed.
    expect(cancelCalls[0][2]).toBe(cancelCalls[1][2]);
  });

  it("says a member already past stopping will finish, rather than calling it cancelled", async () => {
    renderBench();
    vi.mocked(instanceApi.post).mockResolvedValue(preflight());
    await prepare();

    await waitFor(() =>
      expect(screen.getByRole("button", { name: "Run" })).toHaveProperty("disabled", false));
    vi.mocked(instanceApi.postIdempotent).mockResolvedValueOnce(
      { groupId: GROUP, created: true, memberRunIds: [RUN] } as never);
    await userEvent.click(screen.getByRole("button", { name: "Run" }));

    vi.mocked(instanceApi.postIdempotent).mockResolvedValueOnce(
      { cancelledMembers: 0, stillProcessingMembers: 1 } as never);
    await userEvent.click(await screen.findByRole("button", { name: "Cancel run" }));

    // A member inside a provider call cannot be recalled, and the bill will say so.
    await screen.findByText(/already past the point of being stopped/);
  });

  it("shows an unreported usage category as unavailable, never as zero", async () => {
    vi.mocked(useRunGroupPolling).mockReturnValue(polled([memberDetail({
      actualCachedTokens: null, actualCostUsd: null, usageQuality: "UNAVAILABLE",
    })]));

    renderBench();

    const usage = (await screen.findByText("Cached input")).closest("div")!;
    expect(usage.textContent).toContain("Unavailable");
    expect(usage.textContent).not.toContain("0");
  });

  it("opens a discussion only once a run has actually succeeded", async () => {
    vi.mocked(useRunGroupPolling).mockReturnValue(
      polled([memberDetail({ status: "FAILED", failureCode: "PROVIDER_FAILED", result: null })],
        "FAILED"));

    const { rerender } = renderBench();
    await screen.findByText("Result");
    expect(screen.queryByText("Discussion")).toBeNull();

    vi.mocked(useRunGroupPolling).mockReturnValue(polled([memberDetail()]));
    rerender(
      <InstanceWorkbench brainId={BRAIN} instanceSlug="income" instance={instance()} />);

    await screen.findByText("Discussion");
    // A turn answers from what the run saved. Saying so is the difference between a follow-up and
    // an operator believing the instance itself now behaves this way.
    expect(screen.getByText(/Nothing is reparsed/)).toBeTruthy();
  });

  it("says an instance with no live release has nothing to run", async () => {
    renderBench({ liveRelease: null, liveReleaseNumber: null });

    expect(screen.getByRole("alert").textContent).toContain("no live release");
    expect(screen.getByRole("button", { name: "Run" })).toHaveProperty("disabled", true);
  });
});
