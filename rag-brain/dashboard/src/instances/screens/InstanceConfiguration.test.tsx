import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";

/**
 * The whole screen is built around one sentence: a release is immutable, so nothing here edits one.
 *
 * The expensive misunderstanding this product could invite is that changing a select changes
 * production. So the tests check that the controls say what they actually do, that a model change
 * authors a candidate rather than mutating anything, and that a collection which moved since the
 * release pinned it is called out rather than silently re-pinned.
 *
 * They also check the cost of never serving prompts is stated up front: authoring a candidate from
 * an existing release means writing its prompts again, and finding that out after five wizard
 * steps would be nobody's idea of a good time.
 */

vi.mock("../api", () => ({
  instanceApi: { get: vi.fn(), post: vi.fn(), postIdempotent: vi.fn() },
}));

const navigate = vi.fn();
vi.mock("react-router-dom", async () => ({
  ...(await vi.importActual<typeof import("react-router-dom")>("react-router-dom")),
  useNavigate: () => navigate,
}));

import { instanceApi } from "../api";
import InstanceConfiguration from "./InstanceConfiguration";

const BRAIN = "11111111-1111-4111-8111-111111111111";
const LIVE = "22222222-2222-4222-8222-222222222222";
const COLLECTION = "44444444-4444-4444-8444-444444444444";

function configuration(over: Partial<Record<string, unknown>> = {}) {
  return {
    releaseId: LIVE, releaseNumber: 3, live: true, manifestVersion: 2,
    parsedData: {
      envelopeVersion: "1.0.0", canonicalizationVersion: "DOCENGINE-C14N-1",
      allowedDocumentTypes: ["PAYSTUB"], requireAnyDocumentTypes: ["PAYSTUB"],
      minimumSupportedDocuments: 1, reviewRequired: "WARN", missingFields: "PRESERVE",
    },
    model: { provider: "anthropic", model: "claude-opus-5", fallbackPolicy: "NONE" },
    corpus: [{ collectionId: COLLECTION, collectionVersion: 7 }],
    tools: [],
    output: { schemaId: "analyzer-envelope-v2", sha256: "a".repeat(64) },
    limits: {
      maximumInputTokens: 100000, maximumRetrievedTokens: 20000, maximumOutputTokens: 8000,
      maximumDiscussionTokens: 0, maximumConcurrentRuns: 2, maximumExpectedCostUsd: 1.5,
    },
    evaluations: { scenarioSetId: "income-smoke", scenarioSetVersion: 1, minimumScore: 0.8 },
    behaviorPresent: true,
    ...over,
  } as never;
}

function instance() {
  return {
    brainId: BRAIN, slug: "income", displayName: "Income", purpose: "Analyze income.",
    state: "ACTIVE", liveReleaseNumber: 3, candidateCount: 0, manifestVersion: 2,
    provider: "anthropic", model: "claude-opus-5", collectionCount: 1,
    hasCandidateRelease: false, limitationCode: null, limitationFlags: [],
    createdAt: "2026-08-01T00:00:00Z", updatedAt: "2026-08-01T00:00:00Z", liveRelease: null,
  } as never;
}

let collectionVersion = 7;

beforeEach(() => {
  vi.clearAllMocks();
  collectionVersion = 7;
  vi.stubGlobal("crypto", { randomUUID: () => "clone-key" });

  vi.mocked(instanceApi.get).mockImplementation(async (path: string) => {
    if (path.includes("/configuration")) return configuration();
    if (path.includes("/pointer")) {
      return { liveReleaseId: LIVE, pointerVersion: 4, events: [] } as never;
    }
    if (path.includes("/releases")) {
      return [{
        releaseId: LIVE, releaseNumber: 3, provenance: "WIZARD", live: true, manifestVersion: 2,
        provider: "anthropic", model: "claude-opus-5", collectionCount: 1,
        limitationCode: null, limitationFlags: [], createdAt: "2026-08-01T00:00:00Z",
      }] as never;
    }
    if (path.includes("corpus-collections")) {
      return [{
        id: COLLECTION, brainId: BRAIN, slug: "guidelines", displayName: "Guidelines",
        state: "ACTIVE", version: collectionVersion, clonedFromId: null, documentCount: 12,
      }] as never;
    }
    if (path.includes("model-catalog")) {
      return [
        {
          provider: "anthropic", model: "claude-opus-5", contextTokenCeiling: 200000,
          outputTokenCeiling: 64000, tokenizerStrategy: "EXACT", inputUsdPerMillion: 5,
          cachedInputUsdPerMillion: 0.5, outputUsdPerMillion: 25,
        },
        {
          provider: "anthropic", model: "claude-sonnet-5", contextTokenCeiling: 200000,
          outputTokenCeiling: 64000, tokenizerStrategy: "EXACT", inputUsdPerMillion: 3,
          cachedInputUsdPerMillion: 0.3, outputUsdPerMillion: 15,
        },
      ] as never;
    }
    return [] as never;
  });
});

function renderConfiguration() {
  return render(
    <MemoryRouter>
      <InstanceConfiguration brainId={BRAIN} instanceSlug="income" instance={instance()} />
    </MemoryRouter>);
}

describe("InstanceConfiguration", () => {
  it("reads the live release by default", async () => {
    renderConfiguration();

    // "What is this configured to do" almost always means what production is doing, not what
    // somebody drafted and left.
    await waitFor(() => expect(vi.mocked(instanceApi.get).mock.calls.some(
      (c) => String(c[0]).includes(`/releases/${LIVE}/configuration`))).toBe(true));
    expect(await screen.findByText("DOCENGINE-C14N-1")).toBeTruthy();
  });

  it("says plainly that nothing here edits a release", async () => {
    renderConfiguration();

    await screen.findByText(/Releases are immutable/);
    expect(screen.getByText(/authoring a new candidate/)).toBeTruthy();
  });

  it("authors a candidate rather than mutating anything when the model changes", async () => {
    renderConfiguration();

    await userEvent.selectOptions(
      await screen.findByLabelText(/^Change the model to/), "anthropic claude-sonnet-5");
    await userEvent.click(
      screen.getByRole("button", { name: "Author a candidate with this model" }));

    // No write of any kind from this screen: the candidate is authored where manifests are
    // authored, which is the only place that can carry prompts.
    expect(instanceApi.postIdempotent).not.toHaveBeenCalled();
    expect(navigate).toHaveBeenCalledWith("/instances/new");
  });

  it("states up front that prompts have to be written again", async () => {
    renderConfiguration();

    // The cost of never serving prompts, said before someone fills in five wizard steps.
    await waitFor(() => expect(
      screen.getAllByText(/prompts again/i).length).toBeGreaterThan(0));
    expect(await screen.findByText("Set, and not readable")).toBeTruthy();
  });

  it("shows the pinned collection version, not just the collection", async () => {
    renderConfiguration();

    const pinned = (await screen.findByText("Pinned version")).closest("div")!;
    // The pin is what makes a release reproducible; naming the collection alone would hide it.
    expect(pinned.textContent).toContain("v7");
  });

  it("calls out a collection that moved since the release pinned it", async () => {
    collectionVersion = 9;

    renderConfiguration();

    await screen.findByText(/still retrieves v7/);
    expect(screen.getByText("Moved on")).toBeTruthy();
  });

  it("clones a collection by reference rather than editing a shared one", async () => {
    renderConfiguration();

    await userEvent.click(await screen.findByRole("button", { name: /^Clone Guidelines/ }));
    await userEvent.type(screen.getByLabelText("New collection slug"), "guidelines-income");
    await userEvent.type(screen.getByLabelText("New collection name"), "Guidelines (income)");
    // Referenced, not duplicated: no document rows are copied and the original is untouched.
    // Asserted while the form is open, which is when somebody is deciding whether to press it.
    expect(screen.getByText(/references the same documents/)).toBeTruthy();

    await userEvent.click(screen.getByRole("button", { name: "Clone by reference" }));

    await waitFor(() => expect(instanceApi.postIdempotent).toHaveBeenCalled());
    const [path, body] = vi.mocked(instanceApi.postIdempotent).mock.calls[0];
    expect(String(path)).toContain(`/corpus-collections/${COLLECTION}/clone`);
    expect(body).toEqual({ slug: "guidelines-income", displayName: "Guidelines (income)" });
  });

  it("reports a configuration it could not read instead of showing a blank form", async () => {
    vi.mocked(instanceApi.get).mockImplementation(async (path: string) => {
      if (path.includes("/configuration")) throw new Error("RELEASE_NOT_FOUND");
      if (path.includes("/pointer")) {
        return { liveReleaseId: LIVE, pointerVersion: 4, events: [] } as never;
      }
      if (path.includes("/releases")) {
        return [{
          releaseId: LIVE, releaseNumber: 3, provenance: "WIZARD", live: true, manifestVersion: 2,
          provider: "anthropic", model: "claude-opus-5", collectionCount: 1,
          limitationCode: null, limitationFlags: [], createdAt: "2026-08-01T00:00:00Z",
        }] as never;
      }
      return [] as never;
    });

    renderConfiguration();

    await screen.findByRole("alert");
    expect(screen.queryByText("DOCENGINE-C14N-1")).toBeNull();
  });
});
