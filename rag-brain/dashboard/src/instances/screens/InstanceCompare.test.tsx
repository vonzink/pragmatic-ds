import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

/**
 * A comparison is only worth running if its result is attributable, and the server enforces that
 * with two refusals: the members must hold the undeclared things equal, and they must actually
 * differ in the declared one. These tests are mostly about the screen making both reachable —
 * satisfying the first by construction, catching the obvious half of the second locally, and
 * surfacing the server's verdict verbatim rather than paraphrasing it into something reassuring.
 *
 * The one it must never do is offer a model override. Varying a model means naming a release that
 * carries it; a run that could override a validated contract would make every release id on screen
 * a suggestion.
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
import InstanceCompare from "./InstanceCompare";

const BRAIN = "11111111-1111-4111-8111-111111111111";
const PACKAGE = "44444444-4444-4444-8444-444444444444";
const REGISTRATION = "55555555-5555-4555-8555-555555555555";
const SNAPSHOT = "66666666-6666-4666-8666-666666666666";
const COLLECTION = "77777777-7777-4777-8777-777777777777";
const R1 = "aaaaaaaa-1111-4111-8111-111111111111";
const R2 = "aaaaaaaa-2222-4222-8222-222222222222";
const R3 = "aaaaaaaa-3333-4333-8333-333333333333";

function release(over: Partial<Record<string, unknown>> = {}) {
  return {
    releaseId: R1,
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
    ...over,
  };
}

const RELEASES = [
  release(),
  release({ releaseId: R2, releaseNumber: 4, live: false, model: "claude-sonnet-5" }),
  // Same model as r4, different release: distinct ids but nothing varied under `Model`.
  release({ releaseId: R3, releaseNumber: 5, live: false, model: "claude-sonnet-5" }),
];

function instance(over: Partial<Record<string, unknown>> = {}) {
  return {
    brainId: BRAIN, slug: "income", displayName: "Income", purpose: "Analyze income.",
    state: "ACTIVE", liveReleaseNumber: 3, candidateCount: 2, manifestVersion: 2,
    provider: "anthropic", model: "claude-opus-5", collectionCount: 1,
    hasCandidateRelease: true, limitationCode: null, limitationFlags: [],
    createdAt: "2026-08-01T00:00:00Z", updatedAt: "2026-08-01T00:00:00Z",
    liveRelease: release(), ...over,
  } as never;
}

function pinned() {
  return {
    registrationId: REGISTRATION, brainId: BRAIN, instanceSlug: "income", packageId: PACKAGE,
    revision: 4, processingJobId: "job", parseGeneration: 1,
    envelopeVersion: "DOCENGINE-C14N-1", canonicalizationVersion: "1",
    envelopeSha256: "a".repeat(64), envelopeSizeBytes: 1024, sourceSetSha256: "b".repeat(64),
    selectedSourceIds: ["s1"],
    compatibility: { compatible: true, rejection: null, supportedDocumentCount: 1, warnings: [] },
  } as never;
}

function estimate(index: number, over: Partial<Record<string, unknown>> = {}) {
  return {
    memberIndex: index, instanceSlug: "income", releaseId: index === 0 ? R1 : R2,
    registrationId: REGISTRATION, corpusSnapshotId: SNAPSHOT,
    provider: "anthropic", model: index === 0 ? "claude-opus-5" : "claude-sonnet-5",
    pricingVersionId: "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
    inputTokensMin: 1000, inputTokensMax: 1400, outputTokensMin: 200, outputTokensMax: 400,
    costUsdMin: 0.01, costUsdMax: 0.03, estimateQuality: "EXACT", ...over,
  };
}

function preflight(over: Partial<Record<string, unknown>> = {}) {
  return {
    requestSha256: "c".repeat(64), comparisonBasisSha256: "d".repeat(64),
    members: [estimate(0), estimate(1)],
    reservedMaximumUsd: 0.06, committedTodayUsd: 0, alreadyReservedUsd: 0, dailyBudgetUsd: 10,
    withinBudget: true, acceptable: true, blockingCodes: [], ...over,
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
    if (path.includes("/releases")) return RELEASES as never;
    if (path.includes("corpus-collections")) {
      return [{
        id: COLLECTION, brainId: BRAIN, slug: "guidelines", displayName: "Guidelines",
        state: "ACTIVE", version: 7, clonedFromId: null, documentCount: 12,
      }] as never;
    }
    return [{ slug: "income", displayName: "Income" },
      { slug: "assets", displayName: "Assets" }] as never;
  });
});

function renderCompare(over: Partial<Record<string, unknown>> = {}) {
  return render(
    <InstanceCompare brainId={BRAIN} instanceSlug="income" instance={instance(over)} />);
}

/** Pin one parse and freeze one corpus — the basis every member shares. */
async function prepareInputs() {
  vi.mocked(instanceApi.postIdempotent).mockResolvedValueOnce(pinned());
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

async function chooseReleases(first: string, second: string) {
  await userEvent.selectOptions(await screen.findByLabelText("Member 1 release"), first);
  await userEvent.selectOptions(screen.getByLabelText("Member 2 release"), second);
}

describe("InstanceCompare", () => {
  it("states what each dimension holds equal before anything is chosen", async () => {
    renderCompare();

    // Release is the default because it is the one that always satisfies the basis.
    expect(screen.getByText(/same instance and parse, and a different release/)).toBeTruthy();

    await userEvent.selectOptions(screen.getByLabelText("Dimension"), "INSTANCE");
    expect(screen.getByText(/same parse and a different instance/)).toBeTruthy();

    await userEvent.selectOptions(screen.getByLabelText("Dimension"), "MODEL");
    expect(screen.getByText(/same instance, parse, corpus, prompts, tools and output schema/))
      .toBeTruthy();
  });

  it("offers no way to override a model on a run", async () => {
    renderCompare();
    await userEvent.selectOptions(screen.getByLabelText("Dimension"), "MODEL");
    await prepareInputs();

    // A model is varied by naming a release that carries it. Anything else would make the
    // release id on every other screen a suggestion rather than a contract.
    await screen.findByLabelText("Member 1 release");
    expect(screen.queryByLabelText(/Member 1 model/)).toBeNull();
    expect(screen.getByText(/build a candidate release carrying it first/)).toBeTruthy();
  });

  it("refuses two members that name the same release", async () => {
    renderCompare();
    await prepareInputs();
    await chooseReleases(R1, R1);

    await screen.findByText(/every member must name a different release/);
    expect(instanceApi.post).not.toHaveBeenCalled();
  });

  it("refuses two different releases carrying the same model when comparing models", async () => {
    renderCompare();
    await userEvent.selectOptions(screen.getByLabelText("Dimension"), "MODEL");
    await prepareInputs();
    await chooseReleases(R2, R3);

    // Distinct release ids, identical model: nothing varied, so nothing to attribute.
    await screen.findByText(/same provider and model/);
    expect(instanceApi.post).not.toHaveBeenCalled();
  });

  it("prices the exact member list once it is valid", async () => {
    renderCompare();
    vi.mocked(instanceApi.post).mockResolvedValue(preflight());
    await prepareInputs();
    await chooseReleases(R1, R2);

    await waitFor(() => expect(instanceApi.post).toHaveBeenCalled());
    const body = vi.mocked(instanceApi.post).mock.calls[0][1] as Record<string, unknown>;
    expect(body.mode).toBe("COMPARISON");
    expect(body.comparisonDimension).toBe("RELEASE");
    // One parse and one corpus across every member is what makes the basis hold by construction.
    expect(body.members).toEqual([
      { instanceSlug: "income", releaseId: R1, registrationId: REGISTRATION, corpusSnapshotId: SNAPSHOT },
      { instanceSlug: "income", releaseId: R2, registrationId: REGISTRATION, corpusSnapshotId: SNAPSHOT },
    ]);
  });

  it("will not create a group from an estimate of a member list that has since changed", async () => {
    renderCompare();
    vi.mocked(instanceApi.post).mockResolvedValue(preflight());
    await prepareInputs();
    await chooseReleases(R1, R2);

    const create = await screen.findByRole("button", { name: "Create group" });
    await waitFor(() => expect(create).toHaveProperty("disabled", false));

    // Editing after pricing must re-open the gate; otherwise a priced two-member comparison could
    // be submitted as an unpriced three-member one.
    await userEvent.click(screen.getByRole("button", { name: "Add member" }));
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "Create group" })).toHaveProperty("disabled", true));
  });

  it("explains a server basis conflict rather than paraphrasing it away", async () => {
    renderCompare();
    vi.mocked(instanceApi.post).mockResolvedValue(preflight({
      acceptable: false, blockingCodes: ["COMPARISON_BASIS_MISMATCH"],
    }));
    await userEvent.selectOptions(screen.getByLabelText("Dimension"), "MODEL");
    await prepareInputs();
    await chooseReleases(R1, R2);

    // The code is authoritative and is shown verbatim; the sentence beside it is why it happened,
    // which the dashboard cannot derive because release manifests carry prompts and are not served.
    await screen.findByText("COMPARISON_BASIS_MISMATCH");
    expect(screen.getByText(/could not be attributed to the dimension you chose/)).toBeTruthy();
    expect(screen.getByRole("button", { name: "Create group" })).toHaveProperty("disabled", true);
  });

  it("submits one comparison group and reuses its key on retry", async () => {
    renderCompare();
    vi.mocked(instanceApi.post).mockResolvedValue(preflight());
    await prepareInputs();
    await chooseReleases(R1, R2);

    const create = await screen.findByRole("button", { name: "Create group" });
    await waitFor(() => expect(create).toHaveProperty("disabled", false));

    vi.mocked(instanceApi.postIdempotent).mockRejectedValueOnce(new Error("502"));
    await userEvent.click(create);
    const retry = await screen.findByRole("button", { name: "Retry" });

    vi.mocked(instanceApi.postIdempotent).mockResolvedValueOnce(
      { groupId: "g", created: false, memberRunIds: ["r"] } as never);
    await userEvent.click(retry);

    const calls = vi.mocked(instanceApi.postIdempotent).mock.calls
      .filter((c) => String(c[0]).includes("/run-groups?"));
    expect(calls).toHaveLength(2);
    // Two members at twice the price is what a fresh key would have bought.
    expect(calls[0][2]).toBe(calls[1][2]);
  });

  it("resets the member list when the dimension changes", async () => {
    renderCompare();
    await prepareInputs();
    await chooseReleases(R1, R2);

    await userEvent.selectOptions(screen.getByLabelText("Dimension"), "INSTANCE");

    // Members valid under one dimension are not valid under another: two members of one instance
    // are correct for Release and refused for Instance.
    await waitFor(() =>
      expect((screen.getByLabelText("Member 1 release") as HTMLSelectElement).value).toBe(""));
    expect(screen.getByLabelText("Member 1 instance")).toBeTruthy();
  });

  it("keeps successful members when a sibling fails", async () => {
    vi.mocked(useRunGroupPolling).mockReturnValue({
      detail: {
        group: {
          groupId: "g", brainId: BRAIN, mode: "COMPARISON", comparisonDimension: "RELEASE",
          status: "PARTIAL", memberCount: 2, createdAt: "2026-08-20T10:00:00Z",
          terminalAt: "2026-08-20T10:02:00Z", cancellationRequestedAt: null,
        },
        members: [
          member(0, { status: "SUCCEEDED", result: { citations: ["a", "b"] } }),
          member(1, {
            status: "FAILED", failureCode: "PROVIDER_FAILED", result: null,
            actualInputTokens: null, actualCachedTokens: null, actualOutputTokens: null,
            actualTotalTokens: null, actualCostUsd: null, usageQuality: "UNAVAILABLE",
          }),
        ],
      },
      loading: false, transientError: null, settled: true,
    } as never);

    renderCompare();

    const table = await screen.findByRole("table");
    // The group is partial, not failed, and the member that worked still shows its answer.
    expect(screen.getByText("PARTIAL")).toBeTruthy();
    expect(within(table).getByText("PROVIDER_FAILED")).toBeTruthy();
    expect(within(table).getByText("SUCCEEDED")).toBeTruthy();
    expect(screen.getByText("Member 1 output")).toBeTruthy();
  });

  it("keeps the expected columns beside the actual ones", async () => {
    vi.mocked(useRunGroupPolling).mockReturnValue({
      detail: {
        group: {
          groupId: "g", brainId: BRAIN, mode: "COMPARISON", comparisonDimension: "RELEASE",
          status: "SUCCEEDED", memberCount: 1, createdAt: "2026-08-20T10:00:00Z",
          terminalAt: "2026-08-20T10:02:00Z", cancellationRequestedAt: null,
        },
        members: [member(0)],
      },
      loading: false, transientError: null, settled: true,
    } as never);

    renderCompare();

    await screen.findByRole("table");
    // An actual outside its own estimate is the result worth having; dropping the estimate at
    // completion would make it unfalsifiable.
    for (const column of ["Expected cost", "Actual est. cost", "Cached input", "Latency"]) {
      expect(screen.getByRole("columnheader", { name: column })).toBeTruthy();
    }
  });
});

function member(index: number, over: Partial<Record<string, unknown>> = {}) {
  return {
    memberIndex: index, runId: `run-${index}`, instanceSlug: "income",
    releaseId: index === 0 ? R1 : R2, registrationId: REGISTRATION, corpusSnapshotId: SNAPSHOT,
    status: "SUCCEEDED", failureCode: null, provider: "anthropic",
    model: index === 0 ? "claude-opus-5" : "claude-sonnet-5",
    pricingVersionId: "p",
    expectedInputMin: 1000, expectedInputMax: 1400, expectedOutputMin: 200, expectedOutputMax: 400,
    expectedCostUsdMin: 0.01, expectedCostUsdMax: 0.03, estimateQuality: "EXACT",
    actualInputTokens: 1200, actualCachedTokens: 400, actualOutputTokens: 300,
    actualTotalTokens: 1500, actualCostUsd: 0.02, usageQuality: "REPORTED",
    createdAt: "2026-08-20T10:00:00Z", terminalAt: "2026-08-20T10:01:00Z",
    result: { monthlyIncome: 5000 },
    ...over,
  };
}
