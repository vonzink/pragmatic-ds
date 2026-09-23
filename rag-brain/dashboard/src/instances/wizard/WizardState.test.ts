import { describe, expect, it } from "vitest";

/**
 * The reducer holds the rules that would otherwise be spread across six forms and impossible to
 * check: what a step requires before you may leave it, where a server refusal sends you, what
 * survives a failed submission, and what must not survive a catalog that moved.
 *
 * Two of these protect against silent damage rather than against a wrong pixel. A draft discarded
 * because a request timed out loses prompts somebody wrote by hand. A selection kept after the
 * server stopped offering it produces a submission refused for a reason the form never showed.
 */

import {
  STEPS,
  incompleteSteps,
  initialState,
  localErrors,
  pruneToCatalog,
  stepForViolation,
  toCommand,
  wizardReducer,
} from "./WizardState";
import type { WizardDraft, WizardState } from "./WizardState";

const COLLECTION = "77777777-7777-4777-8777-777777777777";

function complete(over: Partial<WizardDraft> = {}): WizardDraft {
  return {
    slug: "income", displayName: "Income", purpose: "Analyze income.",
    envelopeVersion: "1.0.0", canonicalizationVersion: "DOCENGINE-C14N-1",
    allowedDocumentTypes: ["PAYSTUB"], requireAnyDocumentTypes: ["PAYSTUB"],
    minimumSupportedDocuments: "1", reviewRequired: "WARN", missingFields: "PRESERVE",
    collections: [{ collectionId: COLLECTION, collectionVersion: 7 }],
    provider: "anthropic", model: "claude-opus-5", fallbackPolicy: "NONE",
    maximumInputTokens: "100000", maximumRetrievedTokens: "20000", maximumOutputTokens: "8000",
    maximumDiscussionTokens: "0", maximumConcurrentRuns: "2", maximumExpectedCostUsd: "1.50",
    systemPrompt: "You analyze income.", taskPrompt: "Analyze.", retrievalQuery: "income",
    temperature: "0", tools: [],
    schemaId: "analyzer-envelope-v2", schemaSha256: "a".repeat(64),
    scenarioSetId: "income-smoke", scenarioSetVersion: "1", minimumScore: "0.8",
    ...over,
  };
}

function at(step: WizardState["step"], draft = complete()): WizardState {
  return { ...initialState(), step, draft };
}

describe("localErrors", () => {
  it("accepts a complete draft on every step", () => {
    for (const step of STEPS) {
      expect(localErrors(step, complete())).toEqual([]);
    }
  });

  it("refuses a required document type the release does not allow", () => {
    // Unsatisfiable by construction: the document is filtered out before the requirement can be met.
    const errors = localErrors("identity", complete({
      allowedDocumentTypes: ["PAYSTUB"], requireAnyDocumentTypes: ["W2"],
    }));
    expect(errors.join(" ")).toContain("must also be an allowed one");
  });

  it("refuses a parser contract that has not been loaded", () => {
    // Empty rather than guessed, so this is what a wizard that never reached the server looks
    // like. Filling these in from memory would author a release this build cannot read.
    expect(localErrors("identity", complete({ envelopeVersion: "" })).join(" "))
      .toContain("parser contract has not been loaded");
  });

  it("treats a half-typed number as invalid rather than as a zero", () => {
    for (const value of ["", "abc", "0", "-5", "1.5"]) {
      expect(localErrors("model", complete({ maximumInputTokens: value })).length)
        .toBeGreaterThan(0);
    }
    expect(localErrors("model", complete({ maximumInputTokens: "1" }))).toEqual([]);
  });

  it("allows a zero discussion ceiling but not a zero output ceiling", () => {
    // Zero discussion tokens means follow-up questions are off, which is a choice. Zero output
    // tokens means the run can produce nothing, which is not.
    expect(localErrors("model", complete({ maximumDiscussionTokens: "0" }))).toEqual([]);
    expect(localErrors("model", complete({ maximumOutputTokens: "0" })).length).toBeGreaterThan(0);
  });

  it("bounds temperature and minimum score to the ranges the server accepts", () => {
    expect(localErrors("behavior", complete({ temperature: "1" }))).toEqual([]);
    expect(localErrors("behavior", complete({ temperature: "1.1" })).length).toBeGreaterThan(0);
    expect(localErrors("review", complete({ minimumScore: "1" }))).toEqual([]);
    expect(localErrors("review", complete({ minimumScore: "2" })).length).toBeGreaterThan(0);
  });

  it("refuses the same collection twice", () => {
    expect(localErrors("corpus", complete({
      collections: [
        { collectionId: COLLECTION, collectionVersion: 7 },
        { collectionId: COLLECTION, collectionVersion: 7 },
      ],
    })).length).toBeGreaterThan(0);
  });
});

describe("wizardReducer", () => {
  it("never mutates the state it was given", () => {
    const before = at("identity");
    const snapshot = JSON.parse(JSON.stringify(before));

    const after = wizardReducer(before, { type: "edit", patch: { slug: "assets" } });

    expect(before).toEqual(snapshot);
    expect(after).not.toBe(before);
    expect(after.draft).not.toBe(before.draft);
  });

  it("refuses to advance past a step that is not locally valid", () => {
    const invalid = at("identity", complete({ slug: "" }));

    // The guard lives in the reducer, not only in the button: a reducer that advances on request
    // is one keyboard shortcut away from stepping over an invalid form.
    expect(wizardReducer(invalid, { type: "next" }).step).toBe("identity");
    expect(wizardReducer(at("identity"), { type: "next" }).step).toBe("corpus");
  });

  it("goes back without validating, because back is navigation and not an answer", () => {
    const invalid = at("corpus", complete({ collections: [] }));
    expect(wizardReducer(invalid, { type: "back" }).step).toBe("identity");
  });

  it("stops at both ends rather than walking off the step list", () => {
    expect(wizardReducer(at("identity"), { type: "back" }).step).toBe("identity");
    expect(wizardReducer(at("review"), { type: "next" }).step).toBe("review");
  });

  it("files each server refusal under the step that owns the field", () => {
    const state = wizardReducer(at("review"), {
      type: "violations",
      violations: [
        { section: "MODEL", code: "MODEL_NOT_CONFIGURED" },
        { section: "CORPUS", code: "CORPUS_COLLECTION_VERSION_STALE" },
        // The server files this under LIMITS, where it is checked. The control is on Review.
        { section: "LIMITS", code: "EVALUATION_SCENARIO_SET_UNKNOWN" },
      ],
    });

    expect(state.violations.model.map((v) => v.code)).toEqual(["MODEL_NOT_CONFIGURED"]);
    expect(state.violations.corpus.map((v) => v.code)).toEqual(["CORPUS_COLLECTION_VERSION_STALE"]);
    expect(state.violations.review.map((v) => v.code)).toEqual(["EVALUATION_SCENARIO_SET_UNKNOWN"]);
    expect(state.validating).toBe(false);
  });

  it("sends a limits refusal to the step that has the limits", () => {
    expect(stepForViolation({ section: "LIMITS", code: "LIMIT_MAX_COST_EXCEEDS_BUDGET" }))
      .toBe("model");
  });

  it("clears this step's refusals when the field behind them is edited", () => {
    const refused = wizardReducer(at("model"), {
      type: "violations",
      violations: [{ section: "MODEL", code: "MODEL_NOT_CONFIGURED" }],
    });
    expect(refused.violations.model).toHaveLength(1);

    const edited = wizardReducer(refused, { type: "edit", patch: { model: "claude-sonnet-5" } });
    // A stale refusal sitting beside a changed field reads as a fresh verdict on the new value.
    expect(edited.violations.model).toEqual([]);
  });

  it("keeps everything the user typed when a submission fails", () => {
    const submitting = wizardReducer(at("review"), { type: "submitting" });
    const failed = wizardReducer(submitting, { type: "submitFailed", message: "503" });

    // These are hand-written prompts. Discarding them because a request timed out would be the
    // worst available response to a recoverable error.
    expect(failed.draft.systemPrompt).toBe("You analyze income.");
    expect(failed.submitError).toBe("503");
    expect(failed.submitting).toBe(false);
  });

  it("spends the draft only on success", () => {
    const created = wizardReducer(at("review"), {
      type: "created",
      release: {
        instanceId: "i", releaseId: "r", releaseNumber: 1,
        provenanceMode: "WIZARD", manifestSha256: "d", predecessorReleaseId: null,
      },
    });

    expect(created.created?.releaseNumber).toBe(1);
    expect(created.draft.systemPrompt).toBe("");
  });

  it("clears everything on an explicit cancel", () => {
    expect(wizardReducer(at("review"), { type: "reset" })).toEqual(initialState());
  });
});

describe("pruneToCatalog", () => {
  const models = [{
    provider: "anthropic", model: "claude-opus-5", contextTokenCeiling: 1, outputTokenCeiling: 1,
    tokenizerStrategy: "EXACT", inputUsdPerMillion: 1, cachedInputUsdPerMillion: 1,
    outputUsdPerMillion: 1,
  }];
  const collections = [{
    id: COLLECTION, brainId: "b", slug: "g", displayName: "Guidelines", state: "ACTIVE",
    version: 7, clonedFromId: null, documentCount: 3,
  }];

  it("returns the same object when nothing went stale", () => {
    const draft = complete();
    // Identity, not equality: a periodic refresh must not churn state or re-render the form.
    expect(pruneToCatalog(draft, models, collections)).toBe(draft);
  });

  it("drops a model the deployment stopped offering", () => {
    const pruned = pruneToCatalog(complete(), [], collections);
    // Keeping it would submit a contract refused with MODEL_NOT_CONFIGURED for a reason the form
    // never showed. Clearing it puts the choice back where it can still be made.
    expect(pruned.provider).toBe("");
    expect(pruned.model).toBe("");
  });

  it("drops a collection whose version moved under the draft", () => {
    const pruned = pruneToCatalog(complete(), models,
      [{ ...collections[0], version: 8 }]);
    // The pin is what makes a release reproducible; a stale pin is refused, never re-pinned.
    expect(pruned.collections).toEqual([]);
  });

  it("drops a collection that was disabled", () => {
    expect(pruneToCatalog(complete(), models,
      [{ ...collections[0], state: "DISABLED" }]).collections).toEqual([]);
  });

  it("leaves an untouched model selection alone", () => {
    const draft = complete({ provider: "", model: "" });
    expect(pruneToCatalog(draft, [], collections)).toBe(draft);
  });
});

describe("toCommand", () => {
  it("refuses to build a command from a partial draft", () => {
    // There is no partial form on the server and there is none here: a half-finished draft cannot
    // be submitted by a stray click or by a retry of a request that should not have been made.
    expect(toCommand(complete({ systemPrompt: "" }))).toBeNull();
    expect(incompleteSteps(complete({ systemPrompt: "" }))).toEqual(["behavior"]);
  });

  it("names every step that is still incomplete, not just the first", () => {
    expect(incompleteSteps(complete({ slug: "", collections: [], schemaId: "" })))
      .toEqual(["identity", "corpus", "output"]);
  });

  it("parses the numeric fields exactly once, at the boundary", () => {
    const command = toCommand(complete()) as Record<string, never>;
    const manifest = command.manifest as unknown as Record<string, Record<string, unknown>>;

    expect(manifest.limits.maximumInputTokens).toBe(100000);
    expect(manifest.limits.maximumExpectedCostUsd).toBe(1.5);
    expect(manifest.behavior.temperature).toBe(0);
    expect(manifest.evaluations.scenarioSetVersion).toBe(1);
    expect(manifest.manifestVersion).toBe(2);
  });

  it("carries no field the server does not define", () => {
    const command = toCommand(complete()) as unknown as Record<string, unknown>;
    expect(Object.keys(command).sort()).toEqual(["displayName", "manifest", "purpose", "slug"]);
    expect(Object.keys(command.manifest as object).sort()).toEqual(
      ["behavior", "corpus", "evaluations", "limits", "manifestVersion", "model", "output",
        "parsedData", "tools"]);
  });
});
