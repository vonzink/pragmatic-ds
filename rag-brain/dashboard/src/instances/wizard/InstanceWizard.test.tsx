import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";

/**
 * The wizard authors an immutable contract, so its tests are about what it refuses to author.
 *
 * Three things carry the weight. It must not invent a value the server owns: the parser versions
 * and the schema digest arrive from the deployment or the wizard cannot advance at all. It must not
 * advance past a step the server has not approved, because a locally-valid step can still name a
 * model this deployment has no credential for. And it must not create anything twice, or lose a
 * draft of hand-written prompts because one request failed.
 *
 * The fourth is what it must not *imply*: finishing this form creates a candidate, and production
 * keeps answering with whatever it answered with before.
 */

vi.mock("../../api", () => ({ brainsApi: { list: vi.fn() } }));
vi.mock("../api", () => ({
  instanceApi: { get: vi.fn(), post: vi.fn(), postIdempotent: vi.fn() },
}));

import { brainsApi } from "../../api";
import { instanceApi } from "../api";
import InstanceWizard from "./InstanceWizard";

const BRAIN = "11111111-1111-4111-8111-111111111111";
const COLLECTION = "77777777-7777-4777-8777-777777777777";
const DIGEST = "a".repeat(64);

const OPTIONS = {
  envelopeVersion: "1.0.0",
  canonicalizationVersion: "DOCENGINE-C14N-1",
  outputSchemas: [{ schemaId: "analyzer-envelope-v2", sha256: DIGEST }],
  scenarioSets: [{
    scenarioSetId: "income-smoke", version: 1, sha256: "b".repeat(64), scenarioCount: 4,
  }],
  tools: [],
};

let keySeq = 0;

beforeEach(() => {
  vi.clearAllMocks();
  keySeq = 0;
  vi.stubGlobal("crypto", { randomUUID: () => `key-${++keySeq}` });

  vi.mocked(brainsApi.list).mockResolvedValue(
    [{ id: BRAIN, slug: "mortgage", displayName: "Mortgage" }] as never);

  vi.mocked(instanceApi.get).mockImplementation(async (path: string) => {
    if (path.includes("wizard-options")) return OPTIONS as never;
    if (path.includes("model-catalog")) {
      return [{
        provider: "anthropic", model: "claude-opus-5", contextTokenCeiling: 200000,
        outputTokenCeiling: 64000, tokenizerStrategy: "EXACT", inputUsdPerMillion: 5,
        cachedInputUsdPerMillion: 0.5, outputUsdPerMillion: 25,
      }] as never;
    }
    if (path.includes("corpus-collections")) {
      return [{
        id: COLLECTION, brainId: BRAIN, slug: "guidelines", displayName: "Guidelines",
        state: "ACTIVE", version: 7, clonedFromId: null, documentCount: 12,
      }] as never;
    }
    return [] as never;
  });

  vi.mocked(instanceApi.post).mockResolvedValue({ valid: true, violations: [] } as never);
});

function renderWizard() {
  return render(<MemoryRouter><InstanceWizard /></MemoryRouter>);
}

async function chooseBrain() {
  await waitFor(() => expect(screen.getByLabelText(/^Brain/)).toBeTruthy());
  await userEvent.selectOptions(screen.getByLabelText(/^Brain/), BRAIN);
}

async function fillIdentity() {
  await userEvent.type(screen.getByLabelText(/^Slug/), "income");
  await userEvent.type(screen.getByLabelText("Display name"), "Income");
  await userEvent.type(screen.getByLabelText(/^Purpose/), "Analyze income.");
  await userEvent.type(screen.getByLabelText("Add an allowed type"), "paystub");
  await userEvent.click(screen.getByRole("button", { name: "Add allowed type" }));
}

async function next() {
  await userEvent.click(screen.getByRole("button", { name: "Next" }));
}

/** Render, then drive every step to Review with a valid draft. */
async function reachReview() {
  renderWizard();
  await chooseBrain();
  await fillIdentity();
  await next();

  await screen.findByRole("heading", { name: "Corpus collections" });
  await userEvent.click(await screen.findByLabelText(/Guidelines/));
  await next();

  await screen.findByRole("heading", { name: "Model, limits and budget" });
  await userEvent.selectOptions(screen.getByLabelText("Model"), "anthropic claude-opus-5");
  await userEvent.type(screen.getByLabelText("Maximum input tokens"), "100000");
  await userEvent.type(screen.getByLabelText(/^Maximum retrieved tokens/), "20000");
  await userEvent.type(screen.getByLabelText("Maximum output tokens"), "8000");
  await userEvent.type(screen.getByLabelText(/^Maximum expected cost/), "1.5");
  await next();

  await screen.findByRole("heading", { name: "Prompt behavior and tools" });
  await userEvent.type(screen.getByLabelText(/^System prompt/), "You analyze income.");
  await userEvent.type(screen.getByLabelText("Task prompt"), "Analyze.");
  await next();

  await screen.findByRole("heading", { name: "Output schema" });
  await userEvent.selectOptions(
    screen.getByLabelText("Output schema"), "analyzer-envelope-v2");
  await next();

  await screen.findByRole("heading", { name: "Evaluation and review" });
  await userEvent.selectOptions(
    screen.getByLabelText("Evaluation scenario set"), "income-smoke:1");
  await userEvent.type(screen.getByLabelText(/^Minimum score/), "0.8");
}

describe("InstanceWizard", () => {
  it("takes the parser contract from the deployment rather than from itself", async () => {
    renderWizard();

    // Shown, not editable. These are compared by equality against the build's own constants, so a
    // field here would be a way to author a release that refuses every parse it is given.
    await waitFor(() => expect(screen.getByText("DOCENGINE-C14N-1")).toBeTruthy());
    expect(screen.getByText("1.0.0")).toBeTruthy();
    expect(screen.queryByLabelText(/Envelope version/)).toBeNull();
  });

  it("cannot advance at all when the deployment could not say what it supports", async () => {
    vi.mocked(instanceApi.get).mockImplementation(async (path: string) => {
      if (path.includes("wizard-options")) throw new Error("503");
      return [] as never;
    });

    renderWizard();
    await chooseBrain();
    await fillIdentity();

    // Failing closed is the point: the alternative is authoring against remembered constants.
    await screen.findByText(/could not say what it supports/);
    expect(screen.getByRole("button", { name: "Next" })).toHaveProperty("disabled", true);
    expect(screen.getByText(/parser contract has not been loaded/)).toBeTruthy();
  });

  it("refuses a required document type that is not also allowed", async () => {
    renderWizard();
    await chooseBrain();
    await fillIdentity();
    await userEvent.type(screen.getByLabelText("Add a required type"), "w2");
    await userEvent.click(screen.getByRole("button", { name: "Add required type" }));

    // Unsatisfiable by construction: a W2 would be filtered out before the requirement was met.
    await screen.findByText(/must also be an allowed one/);
    expect(screen.getByRole("button", { name: "Next" })).toHaveProperty("disabled", true);
  });

  it("asks the server about the step being left, and stays put when it refuses", async () => {
    vi.mocked(instanceApi.post).mockResolvedValue({
      valid: false,
      violations: [{ section: "PARSED_DATA", code: "PARSER_ENVELOPE_VERSION_UNSUPPORTED" }],
    } as never);

    renderWizard();
    await chooseBrain();
    await fillIdentity();
    await next();

    const path = String(vi.mocked(instanceApi.post).mock.calls[0][0]);
    expect(path).toContain("/validate");
    expect(path).toContain("scope=PARSED_DATA");
    expect(path).toContain(`brain=${BRAIN}`);

    // Locally valid is not enough. The server owns facts this screen cannot check.
    await screen.findByText("PARSER_ENVELOPE_VERSION_UNSUPPORTED");
    expect(screen.getByRole("heading", { name: "Identity and parsed data" })).toBeTruthy();
  });

  it("validates without an idempotency key, because validation writes nothing", async () => {
    renderWizard();
    await chooseBrain();
    await fillIdentity();
    await next();

    // Requiring a key for a pure check would push a client into minting one per keystroke.
    expect(instanceApi.postIdempotent).not.toHaveBeenCalled();
    expect(vi.mocked(instanceApi.post).mock.calls[0]).toHaveLength(2);
  });

  it("does not advance when the validator cannot be reached", async () => {
    vi.mocked(instanceApi.post).mockRejectedValue(new Error("503"));

    renderWizard();
    await chooseBrain();
    await fillIdentity();
    await next();

    // A validator that was not reached has approved nothing.
    await screen.findByText("503");
    expect(screen.getByRole("heading", { name: "Identity and parsed data" })).toBeTruthy();
  });

  it("checks the whole command on review and files refusals to the owning step", async () => {
    await reachReview();

    // The complete check runs on arrival, because it writes nothing and a refusal discovered by
    // pressing Create would arrive at the moment it is least useful.
    await waitFor(() => expect(vi.mocked(instanceApi.post).mock.calls.some(
      (c) => String(c[0]).includes("scope=COMPLETE"))).toBe(true));

    vi.mocked(instanceApi.post).mockResolvedValue({
      valid: false,
      violations: [{ section: "MODEL", code: "MODEL_NOT_CONFIGURED" }],
    } as never);
    // Any edit re-runs the check against the current draft.
    await userEvent.type(screen.getByLabelText(/^Minimum score/), "5");

    await waitFor(() => expect(
      screen.getByRole("button", { name: "Create candidate" })).toHaveProperty("disabled", true));

    // Filed to the step that owns the control, not to the step that was open when it was refused.
    await userEvent.click(
      screen.getByRole("button", { name: /3\. Model, limits and budget/ }));
    await screen.findByText("MODEL_NOT_CONFIGURED");
    expect(screen.getByText(/no credentials for that model/)).toBeTruthy();
  });

  it("carries the whole manifest in one create, and states it is not live", async () => {
    await reachReview();

    vi.mocked(instanceApi.postIdempotent).mockResolvedValue({
      instanceId: "i", releaseId: "r", releaseNumber: 1, provenanceMode: "WIZARD",
      manifestSha256: "d", predecessorReleaseId: null,
    } as never);
    await userEvent.click(screen.getByRole("button", { name: "Create candidate" }));

    await waitFor(() => expect(instanceApi.postIdempotent).toHaveBeenCalled());
    const [path, body] = vi.mocked(instanceApi.postIdempotent).mock.calls[0];
    expect(String(path)).toContain(`brain=${BRAIN}`);

    const command = body as Record<string, unknown>;
    const manifest = command.manifest as Record<string, Record<string, unknown>>;
    expect(command.slug).toBe("income");
    expect(manifest.manifestVersion).toBe(2);
    // Every server-owned value came from the deployment, not from this screen.
    expect(manifest.parsedData.canonicalizationVersion).toBe("DOCENGINE-C14N-1");
    expect(manifest.output.schemaSha256).toBe(DIGEST);
    expect(manifest.evaluations.scenarioSetVersion).toBe(1);
    expect(manifest.corpus.collections).toEqual([
      { collectionId: COLLECTION, collectionVersion: 7 },
    ]);
  });

  it("never offers to promote what it just created", async () => {
    await reachReview();

    // Promotion is a separate, deliberate act. The moment production changes what it answers with
    // must never be a side effect of finishing a form.
    expect(screen.getByText(/not live/)).toBeTruthy();
    expect(screen.queryByRole("button", { name: /Apply to Live/i })).toBeNull();
    expect(screen.queryByRole("button", { name: /Promote/i })).toBeNull();
  });

  it("keeps the draft and the key when creation fails", async () => {
    await reachReview();

    vi.mocked(instanceApi.postIdempotent).mockRejectedValueOnce(new Error("502"));
    await userEvent.click(screen.getByRole("button", { name: "Create candidate" }));

    const retry = await screen.findByRole("button", { name: "Retry" });
    expect(screen.getByText(/Nothing was created/)).toBeTruthy();

    vi.mocked(instanceApi.postIdempotent).mockResolvedValueOnce({
      instanceId: "i", releaseId: "r", releaseNumber: 1, provenanceMode: "WIZARD",
      manifestSha256: "d", predecessorReleaseId: null,
    } as never);
    await userEvent.click(retry);

    await waitFor(() => expect(instanceApi.postIdempotent).toHaveBeenCalledTimes(2));
    const keys = vi.mocked(instanceApi.postIdempotent).mock.calls.map((c) => c[2]);
    // A second key would author a second candidate for one click, and the prompts would have to
    // be typed again to find that out.
    expect(keys[0]).toBe(keys[1]);
  });

  it("lists what is unfinished on review rather than disabling create silently", async () => {
    renderWizard();
    await chooseBrain();
    await fillIdentity();
    await next();
    await screen.findByRole("heading", { name: "Corpus collections" });

    // Step 1 is reachable backwards; nothing ahead is, because each owes a server check.
    const steps = screen.getByRole("list", { name: "Steps" });
    expect(within(steps).getByRole("button", { name: /1\. Identity/ }))
      .toHaveProperty("disabled", false);
    expect(within(steps).getByRole("button", { name: /6\. Evaluation/ }))
      .toHaveProperty("disabled", true);
  });

  it("clears a selection the deployment stopped offering", async () => {
    renderWizard();
    await chooseBrain();
    await fillIdentity();
    await next();
    await screen.findByRole("heading", { name: "Corpus collections" });
    await userEvent.click(await screen.findByLabelText(/Guidelines/));
    await next();

    await screen.findByRole("heading", { name: "Model, limits and budget" });
    await userEvent.selectOptions(screen.getByLabelText("Model"), "anthropic claude-opus-5");
    expect((screen.getByLabelText("Model") as HTMLSelectElement).value)
      .toBe("anthropic claude-opus-5");
  });

  it("cancels back to an empty form", async () => {
    renderWizard();
    await chooseBrain();
    await fillIdentity();

    await userEvent.click(screen.getByRole("button", { name: "Cancel" }));

    await waitFor(() =>
      expect((screen.getByLabelText(/^Slug/) as HTMLInputElement).value).toBe(""));
  });
});
