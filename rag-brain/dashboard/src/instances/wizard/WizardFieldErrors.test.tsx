import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";

/**
 * Whether a refusal reaches the person who has to act on it.
 *
 * Every step already listed its refusals in one strip. That is readable with a screen — the eye
 * pairs a message with the box it names — and silent without one: someone tabbing the form hears
 * "Slug, edit, blank" and nothing about why Next will not move. So each message that a single
 * control owns is given an id, and that control points at it with `aria-describedby` and marks
 * itself `aria-invalid`. The strip is unchanged and still carries every message, including the
 * ones no one field owns.
 *
 * **The rule these tests pin is which messages may be routed at all.** A refusal that no single
 * control can fix, and every refusal that came from the server, stay in the strip. A `Violation` is
 * a section and a code — the server does not say which field inside the section it refused — so
 * attaching one to an input would point a screen-reader user at the wrong box, which is worse than
 * the strip they already had.
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

const OPTIONS = {
  envelopeVersion: "1.0.0",
  canonicalizationVersion: "DOCENGINE-C14N-1",
  outputSchemas: [{ schemaId: "analyzer-envelope-v2", sha256: "a".repeat(64) }],
  scenarioSets: [{
    scenarioSetId: "income-smoke", version: 1, sha256: "b".repeat(64), scenarioCount: 4,
  }],
  tools: [],
};

beforeEach(() => {
  vi.clearAllMocks();
  vi.stubGlobal("crypto", { randomUUID: () => "key-1" });
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

async function renderOnIdentity() {
  render(<MemoryRouter><InstanceWizard /></MemoryRouter>);
  await waitFor(() => expect(screen.getByLabelText(/^Brain/)).toBeTruthy());
  await userEvent.selectOptions(screen.getByLabelText(/^Brain/), BRAIN);
}

/** What a screen reader would read out for a control, from the ids it points at. */
function describedText(control: HTMLElement): string {
  const ids = (control.getAttribute("aria-describedby") ?? "").split(/\s+/).filter(Boolean);
  return ids
    .map((id) => document.getElementById(id)?.textContent ?? "")
    .join(" ");
}

describe("wizard field errors", () => {
  it("says on the field itself why the form will not advance", async () => {
    await renderOnIdentity();

    const slug = screen.getByLabelText(/^Slug/);
    await userEvent.type(slug, "Not A Slug");

    await waitFor(() => expect(slug.getAttribute("aria-invalid")).toBe("true"));
    // The message, not merely a flag. "Invalid" without the rule is a dead end for anyone who
    // cannot see the strip at the bottom of the card.
    expect(describedText(slug)).toContain("lower-case letters");
  });

  it("stops naming a field once the field is fixed", async () => {
    await renderOnIdentity();

    const name = screen.getByLabelText("Display name");
    // Absent to begin with is not proof: assert it appears because of an edit and then goes.
    await userEvent.type(name, "Income");
    await waitFor(() => expect(name.getAttribute("aria-invalid")).toBeNull());

    await userEvent.clear(name);
    await waitFor(() => expect(name.getAttribute("aria-invalid")).toBe("true"));
    expect(describedText(name)).toContain("display name");

    await userEvent.type(name, "Income");
    await waitFor(() => expect(name.getAttribute("aria-invalid")).toBeNull());
    expect(name.getAttribute("aria-describedby")).toBeNull();
  });

  it("describes one message from both fields it names", async () => {
    await renderOnIdentity();
    await userEvent.type(screen.getByLabelText(/^Slug/), "income");
    await userEvent.type(screen.getByLabelText("Display name"), "Income");
    await userEvent.type(screen.getByLabelText(/^Purpose/), "Analyze income.");
    await userEvent.type(screen.getByLabelText("Add an allowed type"), "paystub");
    await userEvent.click(screen.getByRole("button", { name: "Add allowed type" }));
    await userEvent.click(screen.getByRole("button", { name: "Next" }));

    await screen.findByRole("heading", { name: "Corpus collections" });
    await userEvent.click(await screen.findByLabelText(/Guidelines/));
    await userEvent.click(screen.getByRole("button", { name: "Next" }));

    await screen.findByRole("heading", { name: "Model, limits and budget" });
    await userEvent.selectOptions(screen.getByLabelText("Model"), "anthropic claude-opus-5");
    await userEvent.type(screen.getByLabelText("Maximum input tokens"), "100000");
    await userEvent.type(screen.getByLabelText(/^Maximum retrieved tokens/), "20000");
    await userEvent.type(screen.getByLabelText("Maximum output tokens"), "8000");
    await userEvent.type(screen.getByLabelText(/^Maximum expected cost/), "1.5");
    await userEvent.click(screen.getByRole("button", { name: "Next" }));

    await screen.findByRole("heading", { name: "Prompt behavior and tools" });
    const system = screen.getByLabelText(/^System prompt/);
    const task = screen.getByLabelText("Task prompt");
    // One refusal covers both prompts, so both controls point at it rather than one of them
    // carrying a message that is equally about the other.
    await waitFor(() => expect(system.getAttribute("aria-invalid")).toBe("true"));
    expect(task.getAttribute("aria-invalid")).toBe("true");
    expect(describedText(system)).toContain("system prompt");
    expect(describedText(task)).toContain("task prompt");
  });

  it("leaves a refusal no single control owns in the strip and off every field", async () => {
    await renderOnIdentity();
    await userEvent.type(screen.getByLabelText(/^Slug/), "income");
    await userEvent.type(screen.getByLabelText("Display name"), "Income");
    await userEvent.type(screen.getByLabelText(/^Purpose/), "Analyze income.");
    await userEvent.type(screen.getByLabelText("Add an allowed type"), "paystub");
    await userEvent.click(screen.getByRole("button", { name: "Add allowed type" }));
    await userEvent.click(screen.getByRole("button", { name: "Next" }));

    await screen.findByRole("heading", { name: "Corpus collections" });

    // Nothing is chosen, so the step refuses — but the refusal is about a list of checkboxes and
    // not about any one of them. Marking an arbitrary checkbox invalid would send someone to a
    // control that is not the problem.
    await screen.findByText(/Choose at least one corpus collection/);
    const invalid = document.querySelectorAll('[aria-invalid="true"]');
    expect(invalid.length).toBe(0);
  });

  it("never points a server refusal at a field", async () => {
    vi.mocked(instanceApi.post).mockResolvedValue({
      valid: false,
      violations: [{ section: "IDENTITY", code: "INSTANCE_SLUG_TAKEN" }],
    } as never);

    await renderOnIdentity();
    await userEvent.type(screen.getByLabelText(/^Slug/), "income");
    await userEvent.type(screen.getByLabelText("Display name"), "Income");
    await userEvent.type(screen.getByLabelText(/^Purpose/), "Analyze income.");
    await userEvent.type(screen.getByLabelText("Add an allowed type"), "paystub");
    await userEvent.click(screen.getByRole("button", { name: "Add allowed type" }));
    await userEvent.click(screen.getByRole("button", { name: "Next" }));

    // The code is a section and a code. Guessing "slug" from INSTANCE_SLUG_TAKEN happens to be
    // right; guessing from the next code will not be, so nothing guesses.
    await screen.findByText("INSTANCE_SLUG_TAKEN");
    expect(screen.getByLabelText(/^Slug/).getAttribute("aria-invalid")).toBeNull();
    expect(document.querySelectorAll('[aria-invalid="true"]').length).toBe(0);
  });
});
