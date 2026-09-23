import type { CandidateReleaseView, CatalogModelView, CollectionSummary, Violation } from "../types";

/**
 * The wizard's state, as data.
 *
 * All of it lives here rather than in the screen because the interesting rules are about
 * transitions, not about rendering: what a step requires before you may leave it, what a server
 * refusal means for where you are sent, what survives a failed submission, and what must not
 * survive a catalog that changed underneath you. Those are testable in isolation and nearly
 * impossible to test through six mounted forms.
 *
 * ### Why the draft is mostly strings
 *
 * A form holds text, including text that is not yet a number. Storing `maximumInputTokens` as a
 * number would make "", "0" and "abc" indistinguishable at the moment the user is halfway through
 * typing, and would force the reducer to decide what a partially-typed field means. Parsing happens
 * once, at the boundary where a command is built, and a field that will not parse is a validation
 * failure rather than a silent zero.
 *
 * ### What is never free-form
 *
 * Provider, model, collections, tools, output schema and scenario set are all chosen from
 * server-supplied options. The draft holds the identifiers; it never holds a path, a URL or a
 * hand-typed model name. {@link pruneToCatalog} exists to keep that true over time: an option that
 * stops being offered must stop being selected, or the wizard will submit a contract the server
 * has already stopped honouring.
 */

export type StepId = "identity" | "corpus" | "model" | "behavior" | "output" | "review";

export const STEPS: StepId[] = [
  "identity", "corpus", "model", "behavior", "output", "review",
];

export const STEP_TITLES: Record<StepId, string> = {
  identity: "Identity and parsed data",
  corpus: "Corpus collections",
  model: "Model, limits and budget",
  behavior: "Prompt behavior and tools",
  output: "Output schema",
  review: "Evaluation and review",
};

/** The server-side scope a section is validated under. */
export type ValidationScope =
  | "PARSED_DATA" | "CORPUS" | "MODEL" | "BEHAVIOR" | "OUTPUT" | "LIMITS" | "COMPLETE";

/** Which step a validation scope is edited on, so a refusal can send the user to the field. */
const SCOPE_STEP: Record<ValidationScope, StepId> = {
  PARSED_DATA: "identity",
  CORPUS: "corpus",
  MODEL: "model",
  BEHAVIOR: "behavior",
  OUTPUT: "output",
  LIMITS: "model",
  COMPLETE: "identity",
};

/**
 * Codes whose owning step differs from their scope's.
 *
 * The server files evaluation violations under `LIMITS`, which is where they are checked. But the
 * scenario set is chosen on Review, and sending someone to the limits step to fix a scenario set
 * would send them to a step that does not contain the field. The scope is the server's filing;
 * this is where the control actually is.
 */
const CODE_STEP: Record<string, StepId> = {
  EVALUATION_CONTRACT_INVALID: "review",
  EVALUATION_SCENARIO_SET_UNKNOWN: "review",
  INSTANCE_IDENTITY_INVALID: "identity",
  INSTANCE_COMMAND_INCOMPLETE: "identity",
};

export function stepForViolation(violation: Violation): StepId {
  return CODE_STEP[violation.code]
    ?? SCOPE_STEP[violation.section as ValidationScope]
    ?? "identity";
}

export interface ToolDraft {
  name: string;
  version: string;
  inputSchemaSha256: string;
  outputSchemaSha256: string;
}

export interface WizardDraft {
  slug: string;
  displayName: string;
  purpose: string;

  envelopeVersion: string;
  canonicalizationVersion: string;
  allowedDocumentTypes: string[];
  requireAnyDocumentTypes: string[];
  minimumSupportedDocuments: string;
  reviewRequired: "WARN" | "REJECT";
  missingFields: "PRESERVE" | "REJECT";

  collections: { collectionId: string; collectionVersion: number }[];

  provider: string;
  model: string;
  fallbackPolicy: "NONE" | "CONFIGURED";

  maximumInputTokens: string;
  maximumRetrievedTokens: string;
  maximumOutputTokens: string;
  maximumDiscussionTokens: string;
  maximumConcurrentRuns: string;
  maximumExpectedCostUsd: string;

  systemPrompt: string;
  taskPrompt: string;
  retrievalQuery: string;
  temperature: string;
  tools: ToolDraft[];

  schemaId: string;
  schemaSha256: string;

  scenarioSetId: string;
  scenarioSetVersion: string;
  minimumScore: string;
}

export interface WizardState {
  step: StepId;
  draft: WizardDraft;
  /** Server refusals, filed under the step that owns the field. */
  violations: Record<StepId, Violation[]>;
  validating: boolean;
  submitting: boolean;
  /** A refusal the user can act on. Never clears the draft. */
  submitError: string | null;
  created: CandidateReleaseView | null;
}

export type WizardAction =
  | { type: "edit"; patch: Partial<WizardDraft> }
  | { type: "back" }
  | { type: "next" }
  | { type: "goto"; step: StepId }
  | { type: "validating"; value: boolean }
  | { type: "violations"; violations: Violation[] }
  | { type: "submitting" }
  | { type: "submitFailed"; message: string }
  | { type: "created"; release: CandidateReleaseView }
  | { type: "catalog"; models: CatalogModelView[]; collections: CollectionSummary[] }
  | { type: "reset" };

function noViolations(): Record<StepId, Violation[]> {
  return {
    identity: [], corpus: [], model: [], behavior: [], output: [], review: [],
  };
}

export function initialDraft(): WizardDraft {
  return {
    slug: "", displayName: "", purpose: "",
    // Deliberately empty rather than guessed. These are the parser's own version constants, and a
    // dashboard that filled them in from memory would author a release this build cannot read the
    // moment either one changes.
    envelopeVersion: "", canonicalizationVersion: "",
    allowedDocumentTypes: [], requireAnyDocumentTypes: [], minimumSupportedDocuments: "1",
    reviewRequired: "WARN", missingFields: "PRESERVE",
    collections: [],
    provider: "", model: "", fallbackPolicy: "NONE",
    maximumInputTokens: "", maximumRetrievedTokens: "", maximumOutputTokens: "",
    maximumDiscussionTokens: "0", maximumConcurrentRuns: "1", maximumExpectedCostUsd: "",
    systemPrompt: "", taskPrompt: "", retrievalQuery: "", temperature: "0",
    tools: [],
    schemaId: "", schemaSha256: "",
    scenarioSetId: "", scenarioSetVersion: "", minimumScore: "",
  };
}

export function initialState(): WizardState {
  return {
    step: "identity",
    draft: initialDraft(),
    violations: noViolations(),
    validating: false,
    submitting: false,
    submitError: null,
    created: null,
  };
}

export function wizardReducer(state: WizardState, action: WizardAction): WizardState {
  switch (action.type) {
    case "edit":
      return {
        ...state,
        draft: { ...state.draft, ...action.patch },
        // An edit answers whatever the server objected to on this step, whether or not it fixed
        // it. Leaving a stale refusal beside a changed field reads as a fresh verdict on it.
        violations: { ...state.violations, [state.step]: [] },
        submitError: null,
      };

    case "back": {
      const index = STEPS.indexOf(state.step);
      // Going back never validates and never clears: it is navigation, not an answer.
      return index <= 0 ? state : { ...state, step: STEPS[index - 1] };
    }

    case "next": {
      const index = STEPS.indexOf(state.step);
      // The guard belongs here, not only in the button's `disabled`: a reducer that advances on
      // request is one keyboard shortcut away from stepping past an invalid form.
      if (index < 0 || index >= STEPS.length - 1) return state;
      if (localIssues(state.step, state.draft).length > 0) return state;
      return { ...state, step: STEPS[index + 1] };
    }

    case "goto":
      return STEPS.includes(action.step) ? { ...state, step: action.step } : state;

    case "validating":
      return { ...state, validating: action.value };

    case "violations": {
      const filed = noViolations();
      for (const violation of action.violations) {
        filed[stepForViolation(violation)].push(violation);
      }
      return { ...state, violations: filed, validating: false };
    }

    case "submitting":
      return { ...state, submitting: true, submitError: null };

    case "submitFailed":
      // The draft survives. Everything in it is non-secret and was typed by hand; discarding it
      // because a request timed out would be the worst possible response to a recoverable error.
      return { ...state, submitting: false, submitError: action.message };

    case "created":
      // Success is the one place the draft is spent. It now exists as an immutable release.
      return {
        ...initialState(), created: action.release, step: "review",
      };

    case "catalog": {
      const draft = pruneToCatalog(state.draft, action.models, action.collections);
      return draft === state.draft ? state : { ...state, draft };
    }

    case "reset":
      return initialState();

    default:
      return state;
  }
}

/**
 * Drops selections the server no longer offers.
 *
 * A model withdrawn from the catalog, or a collection disabled or edited since it was picked, is
 * not a selection any more — it is a submission that will be refused with `MODEL_NOT_CONFIGURED`
 * or `CORPUS_COLLECTION_VERSION_STALE`. Clearing it puts the choice back in front of the person
 * making it, at the point they can still make it differently.
 *
 * Returns the original draft unchanged when nothing was stale, so a periodic refresh does not
 * churn state or re-render the form.
 */
export function pruneToCatalog(
  draft: WizardDraft, models: CatalogModelView[], collections: CollectionSummary[],
): WizardDraft {
  const modelOffered = draft.provider === "" && draft.model === ""
    ? true
    : models.some((m) => m.provider === draft.provider && m.model === draft.model);

  const byId = new Map(collections.map((c) => [c.id, c]));
  const keptCollections = draft.collections.filter((ref) => {
    const current = byId.get(ref.collectionId);
    // A moved version is as unusable as a missing collection: the pin is what makes the release
    // reproducible, and a stale pin is refused rather than quietly re-pinned.
    return current !== undefined
      && current.state === "ACTIVE"
      && current.version === ref.collectionVersion;
  });

  if (modelOffered && keptCollections.length === draft.collections.length) return draft;

  return {
    ...draft,
    provider: modelOffered ? draft.provider : "",
    model: modelOffered ? draft.model : "",
    collections: keptCollections,
  };
}

/**
 * One local refusal, and the control it is about.
 *
 * `field` is a draft key, or null where no single control owns the message. Null is the honest
 * answer more often than it looks: "choose at least one corpus collection" is about a list of
 * checkboxes and not about any one of them, and marking an arbitrary one invalid would send
 * someone to a control that is not the problem. Only a message a single control can fix carries a
 * field, and only those get attached to an input.
 */
export interface WizardIssue {
  field: string | null;
  message: string;
}

/**
 * What one step requires before you may leave it, each refusal paired with the control it names.
 *
 * Deliberately only what is decidable here. Whether a model is configured, a collection version is
 * current, a schema digest matches or a scenario set exists are all server facts, and guessing at
 * them would mean either blocking a valid form or waving through an invalid one. Those arrive from
 * the stateless validator — and they arrive as a section and a code, with no field, which is why
 * they are never routed to an input the way these are.
 */
export function localIssues(step: StepId, draft: WizardDraft): WizardIssue[] {
  const problems: WizardIssue[] = [];
  const positive = (value: string) => /^\d+$/.test(value.trim()) && Number(value) > 0;
  const nonNegative = (value: string) => /^\d+$/.test(value.trim());

  switch (step) {
    case "identity":
      if (!/^[a-z0-9][a-z0-9-]{0,62}$/.test(draft.slug)) {
        problems.push({ field: "slug",
          message: "A slug is lower-case letters, digits and hyphens, starting with a letter or digit." });
      }
      if (!draft.displayName.trim()) {
        problems.push({ field: "displayName", message: "A display name is required." });
      }
      if (!draft.purpose.trim()) {
        problems.push({ field: "purpose", message: "A purpose is required." });
      }
      if (!draft.envelopeVersion || !draft.canonicalizationVersion) {
        // No field: the versions are shown, not edited, so there is no control to point at.
        problems.push({ field: null, message: "The parser contract has not been loaded." });
      }
      if (draft.allowedDocumentTypes.length === 0) {
        problems.push({ field: "allowedDocumentTypes",
          message: "Choose at least one document type this instance can analyze." });
      }
      if (!draft.requireAnyDocumentTypes.every((t) => draft.allowedDocumentTypes.includes(t))) {
        // Unsatisfiable by construction: the document would be filtered out before the
        // requirement could ever be met.
        problems.push({ field: "requireAnyDocumentTypes",
          message: "A required document type must also be an allowed one." });
      }
      if (!positive(draft.minimumSupportedDocuments)) {
        problems.push({ field: "minimumSupportedDocuments",
          message: "At least one supported document must be required." });
      }
      break;

    case "corpus":
      if (draft.collections.length === 0) {
        problems.push({ field: null, message: "Choose at least one corpus collection." });
      }
      if (new Set(draft.collections.map((c) => c.collectionId)).size
        !== draft.collections.length) {
        problems.push({ field: null, message: "A collection can only be included once." });
      }
      break;

    case "model":
      if (!draft.provider || !draft.model) {
        problems.push({ field: "model", message: "Choose a configured model." });
      }
      for (const [label, field, value] of [
        ["input", "maximumInputTokens", draft.maximumInputTokens],
        ["retrieved", "maximumRetrievedTokens", draft.maximumRetrievedTokens],
        ["output", "maximumOutputTokens", draft.maximumOutputTokens],
      ] as const) {
        if (!positive(value)) {
          problems.push({ field,
            message: `The maximum ${label} tokens must be a positive whole number.` });
        }
      }
      if (!nonNegative(draft.maximumDiscussionTokens)) {
        problems.push({ field: "maximumDiscussionTokens",
          message: "The discussion token ceiling must be zero or a positive whole number." });
      }
      if (!positive(draft.maximumConcurrentRuns)) {
        problems.push({ field: "maximumConcurrentRuns",
          message: "At least one concurrent run must be allowed." });
      }
      if (!(Number(draft.maximumExpectedCostUsd) > 0)
        || !/^\d+(\.\d+)?$/.test(draft.maximumExpectedCostUsd.trim())) {
        problems.push({ field: "maximumExpectedCostUsd",
          message: "The maximum expected cost must be greater than zero." });
      }
      break;

    case "behavior": {
      if (!draft.systemPrompt.trim() || !draft.taskPrompt.trim()) {
        // One message about two controls, so it gets one field of its own and both textareas
        // point at it. Filing it twice would read it out twice; filing it under either prompt
        // alone would leave the other unmarked while being equally about it.
        problems.push({ field: "prompts",
          message: "A system prompt and a task prompt are both required." });
      }
      const temperature = Number(draft.temperature);
      if (!/^\d+(\.\d+)?$/.test(draft.temperature.trim())
        || !(temperature >= 0 && temperature <= 1)) {
        problems.push({ field: "temperature", message: "Temperature must be between 0 and 1." });
      }
      break;
    }

    case "output":
      if (!draft.schemaId || !draft.schemaSha256) {
        problems.push({ field: "schemaId", message: "Choose a server-provided output schema." });
      }
      break;

    case "review":
      if (!draft.scenarioSetId || !positive(draft.scenarioSetVersion)) {
        problems.push({ field: "scenarioSetId", message: "Choose an evaluation scenario set." });
      }
      if (!/^\d+(\.\d+)?$/.test(draft.minimumScore.trim())
        || !(Number(draft.minimumScore) >= 0 && Number(draft.minimumScore) <= 1)) {
        problems.push({ field: "minimumScore",
          message: "The minimum score must be between 0 and 1." });
      }
      break;
  }

  return problems;
}

/**
 * The same refusals as prose, for callers that only want to say what is wrong.
 *
 * Kept as the projection of {@link localIssues} rather than a second list, so a message can never
 * be shown on a field and worded differently in a summary.
 */
export function localErrors(step: StepId, draft: WizardDraft): string[] {
  return localIssues(step, draft).map((issue) => issue.message);
}

/** Every step that is not yet locally complete. Review lists these rather than the user finding out. */
export function incompleteSteps(draft: WizardDraft): StepId[] {
  return STEPS.filter((step) => localIssues(step, draft).length > 0);
}

/**
 * The complete command, or nothing.
 *
 * There is no partial form on the server and there is none here either: this returns null unless
 * every step is locally complete, so a draft that is halfway through cannot be submitted by a
 * stray click, a keyboard shortcut, or a retry of a request that should never have been made.
 */
export function toCommand(draft: WizardDraft): unknown | null {
  if (incompleteSteps(draft).length > 0) return null;

  return {
    slug: draft.slug,
    displayName: draft.displayName.trim(),
    purpose: draft.purpose.trim(),
    manifest: {
      manifestVersion: 2,
      parsedData: {
        envelopeVersion: draft.envelopeVersion,
        canonicalizationVersion: draft.canonicalizationVersion,
        allowedDocumentTypes: draft.allowedDocumentTypes,
        requireAnyDocumentTypes: draft.requireAnyDocumentTypes,
        minimumSupportedDocuments: Number(draft.minimumSupportedDocuments),
        reviewRequired: draft.reviewRequired,
        missingFields: draft.missingFields,
      },
      model: {
        provider: draft.provider,
        model: draft.model,
        fallbackPolicy: draft.fallbackPolicy,
      },
      corpus: { collections: draft.collections },
      behavior: {
        systemPrompt: draft.systemPrompt,
        taskPrompt: draft.taskPrompt,
        retrievalQuery: draft.retrievalQuery,
        temperature: Number(draft.temperature),
      },
      tools: draft.tools,
      output: { schemaId: draft.schemaId, schemaSha256: draft.schemaSha256 },
      limits: {
        maximumInputTokens: Number(draft.maximumInputTokens),
        maximumRetrievedTokens: Number(draft.maximumRetrievedTokens),
        maximumOutputTokens: Number(draft.maximumOutputTokens),
        maximumDiscussionTokens: Number(draft.maximumDiscussionTokens),
        maximumConcurrentRuns: Number(draft.maximumConcurrentRuns),
        maximumExpectedCostUsd: Number(draft.maximumExpectedCostUsd),
      },
      evaluations: {
        scenarioSetId: draft.scenarioSetId,
        scenarioSetVersion: Number(draft.scenarioSetVersion),
        minimumScore: Number(draft.minimumScore),
      },
    },
  };
}

/** Zero rather than NaN, so a half-typed field reaches the server as a value it can refuse. */
function numberOrZero(value: string): number {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : 0;
}

/**
 * A structurally complete body for the stateless validator, however incomplete the draft is.
 *
 * The server's manifest record requires every section to be present — a missing one is a
 * deserialization failure, which comes back as a bad request rather than as the violations the
 * wizard is asking for. So this always emits every section, filling unanswered fields with empty
 * strings and zeros, which are exactly the values each section's own rules already reject.
 *
 * The result is that asking "is my corpus valid yet?" on step two returns violations about the
 * corpus rather than a 400 about the shape of the request. It persists nothing and needs no
 * idempotency key, because validation writes nothing at all.
 */
export function toValidationBody(draft: WizardDraft): unknown {
  return {
    slug: draft.slug,
    displayName: draft.displayName.trim(),
    purpose: draft.purpose.trim(),
    manifest: {
      manifestVersion: 2,
      parsedData: {
        envelopeVersion: draft.envelopeVersion,
        canonicalizationVersion: draft.canonicalizationVersion,
        allowedDocumentTypes: draft.allowedDocumentTypes,
        requireAnyDocumentTypes: draft.requireAnyDocumentTypes,
        minimumSupportedDocuments: numberOrZero(draft.minimumSupportedDocuments),
        reviewRequired: draft.reviewRequired,
        missingFields: draft.missingFields,
      },
      model: {
        provider: draft.provider,
        model: draft.model,
        fallbackPolicy: draft.fallbackPolicy,
      },
      corpus: { collections: draft.collections },
      behavior: {
        systemPrompt: draft.systemPrompt,
        taskPrompt: draft.taskPrompt,
        retrievalQuery: draft.retrievalQuery,
        temperature: numberOrZero(draft.temperature),
      },
      tools: draft.tools,
      output: { schemaId: draft.schemaId, schemaSha256: draft.schemaSha256 },
      limits: {
        maximumInputTokens: numberOrZero(draft.maximumInputTokens),
        maximumRetrievedTokens: numberOrZero(draft.maximumRetrievedTokens),
        maximumOutputTokens: numberOrZero(draft.maximumOutputTokens),
        maximumDiscussionTokens: numberOrZero(draft.maximumDiscussionTokens),
        maximumConcurrentRuns: numberOrZero(draft.maximumConcurrentRuns),
        maximumExpectedCostUsd: numberOrZero(draft.maximumExpectedCostUsd),
      },
      evaluations: {
        scenarioSetId: draft.scenarioSetId,
        scenarioSetVersion: numberOrZero(draft.scenarioSetVersion),
        minimumScore: numberOrZero(draft.minimumScore),
      },
    },
  };
}

/** The scope each step asks the server to check. Review checks the whole command. */
export const STEP_SCOPE: Record<StepId, ValidationScope> = {
  identity: "PARSED_DATA",
  corpus: "CORPUS",
  model: "MODEL",
  behavior: "BEHAVIOR",
  output: "OUTPUT",
  review: "COMPLETE",
};
