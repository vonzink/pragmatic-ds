import type { CatalogModelView, CollectionSummary, Violation, WizardOptions } from "../types";
import type { StepId, WizardDraft, WizardIssue } from "./WizardState";

/**
 * What every step is handed.
 *
 * Steps never fetch. Options are loaded once by the shell and passed down, so a step cannot be
 * rendered against a catalog that a different step is still waiting for, and so the whole wizard
 * fails closed together when the deployment cannot say what it supports.
 */
export interface StepProps {
  draft: WizardDraft;
  onEdit: (patch: Partial<WizardDraft>) => void;
  /** Server refusals filed to this step. Codes, rendered as codes. */
  violations: Violation[];
  /** Which step this is, so a control can name the id of the message it is described by. */
  stepId: StepId;
  /** Local refusals for this step. The shell renders them; steps point their controls at them. */
  issues: WizardIssue[];
  options: WizardOptions | null;
  collections: CollectionSummary[];
  models: CatalogModelView[];
}

/**
 * The DOM id of the message explaining why a field is refused.
 *
 * Minted here because this is the one place that renders the messages, so the id a control points
 * at and the id an element carries cannot drift apart.
 */
export function fieldMessageId(stepId: StepId, field: string): string {
  return `wizard-issue-${stepId}-${field}`;
}

/** What `aria-*` a control carries, given the issues that name it. Empty when none do. */
export interface FieldErrorProps {
  "aria-invalid"?: true;
  "aria-describedby"?: string;
}

/**
 * Wires one control to the local refusals that name it.
 *
 * A control may pass more than one field where it owns several, and two controls may pass the same
 * field where one message is about both — a system prompt and a task prompt are refused together,
 * and both textareas describe the single message rather than one of them carrying a sentence that
 * is equally about the other.
 *
 * Nothing here can route a {@link Violation}. Those are a section and a code, with no field, so
 * attaching one to an input would mean guessing which box inside the section the server meant.
 * Guessed wrong, that points a screen-reader user at a control that is not the problem, which is
 * worse than the strip they would otherwise have read.
 */
export function fieldErrorProps(
  stepId: StepId,
  issues: readonly WizardIssue[],
  ...fields: readonly string[]
): FieldErrorProps {
  const described = fields
    .filter((field) => issues.some((issue) => issue.field === field))
    .map((field) => fieldMessageId(stepId, field));
  if (described.length === 0) return {};
  return { "aria-invalid": true, "aria-describedby": described.join(" ") };
}

/**
 * The step's local refusals, each carrying the id the control it names points back at.
 *
 * Rendered as one strip, unchanged in what it says and in what it lists: every message is here,
 * including the ones no single control owns. The ids are the only addition, and they are what
 * turns a strip that reads correctly with a screen into one that also reads correctly without one.
 */
export function StepIssues({ stepId, issues }: { stepId: StepId; issues: WizardIssue[] }) {
  if (issues.length === 0) return null;
  return (
    <div className="warn-note" role="status">
      <ul className="warn-list">
        {issues.map((issue, index) => (
          <li
            key={`${issue.field ?? "step"}-${index}`}
            id={issue.field ? fieldMessageId(stepId, issue.field) : undefined}
          >
            {issue.message}
          </li>
        ))}
      </ul>
    </div>
  );
}

/** Server refusals for one step. Always the code; the sentence beside it is ours, not the server's. */
export function StepViolations({ violations }: { violations: Violation[] }) {
  if (violations.length === 0) return null;
  return (
    <div className="error-note" role="alert">
      <p>The server refused this section:</p>
      <ul className="warn-list">
        {violations.map((v) => (
          <li key={`${v.section}:${v.code}`}>
            <span className="mono">{v.code}</span>
            {VIOLATION_HELP[v.code] && <span className="muted"> — {VIOLATION_HELP[v.code]}</span>}
          </li>
        ))}
      </ul>
    </div>
  );
}

/**
 * Readings of the refusals a form can actually cause.
 *
 * Only where the code alone would leave someone stuck. A stale collection version and a withdrawn
 * model both mean "the thing you picked moved while you were typing", which is not obvious from
 * either code and is fixed by re-picking rather than by editing anything.
 */
const VIOLATION_HELP: Record<string, string> = {
  MODEL_NOT_CONFIGURED:
    "this deployment has no credentials for that model, so nothing could dispatch to it",
  CORPUS_COLLECTION_VERSION_STALE:
    "the collection changed since it was chosen; choose it again to pin the current version",
  CORPUS_COLLECTION_DISABLED: "that collection is disabled and cannot be pinned",
  CORPUS_COLLECTION_NOT_FOUND: "that collection no longer exists in this brain",
  PARSER_ENVELOPE_VERSION_UNSUPPORTED:
    "this build cannot read that envelope version, so every parse would be refused",
  PARSER_REQUIRED_TYPE_NOT_ALLOWED:
    "a required type must also be allowed, or no document could ever satisfy it",
  OUTPUT_SCHEMA_DIGEST_MISMATCH:
    "the schema changed since this option was loaded; reload the page to pick up the new digest",
  LIMIT_MAX_COST_EXCEEDS_BUDGET:
    "one run could cost more than this brain's whole daily budget, so none could ever start",
  EVALUATION_SCENARIO_SET_UNKNOWN: "this build does not ship that scenario set",
};
