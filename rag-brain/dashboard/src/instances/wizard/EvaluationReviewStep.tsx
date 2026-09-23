import { STEP_TITLES, incompleteSteps, localErrors } from "./WizardState";
import { StepViolations, fieldErrorProps } from "./StepProps";
import type { StepId } from "./WizardState";
import type { StepProps } from "./StepProps";

/**
 * The evaluation gate, and everything that is about to be created.
 *
 * Review does two jobs. It chooses the scenario set a promotion will later be gated on, and it
 * shows the whole definition in one place before anything is written.
 *
 * What it deliberately does not do is promote. Creating an instance creates a *candidate*, and
 * production keeps answering with whatever it answered with before. Evaluation and promotion are
 * separate, explicit acts afterwards, because the moment that changes what customers get should
 * never be a side effect of finishing a form.
 */
export default function EvaluationReviewStep(
  { draft, onEdit, violations, options, models, collections, stepId, issues, onJump }:
  StepProps & { onJump: (step: StepId) => void },
) {
  const sets = options?.scenarioSets ?? [];
  const missing = incompleteSteps(draft).filter((step) => step !== "review");
  const selectedModel = models.find(
    (m) => m.provider === draft.provider && m.model === draft.model) ?? null;

  return (
    <div className="wizard-step">
      <StepViolations violations={violations} />

      <label>
        Evaluation scenario set
        <select
          {...fieldErrorProps(stepId, issues, "scenarioSetId")}
          value={draft.scenarioSetId ? `${draft.scenarioSetId}:${draft.scenarioSetVersion}` : ""}
          onChange={(e) => {
            const picked = sets.find(
              (s) => `${s.scenarioSetId}:${s.version}` === e.target.value) ?? null;
            onEdit({
              scenarioSetId: picked?.scenarioSetId ?? "",
              scenarioSetVersion: picked ? String(picked.version) : "",
            });
          }}
        >
          <option value="">Choose a scenario set</option>
          {sets.map((set) => (
            <option key={`${set.scenarioSetId}:${set.version}`}
                    value={`${set.scenarioSetId}:${set.version}`}>
              {set.scenarioSetId} v{set.version} ({set.scenarioCount} cases)
            </option>
          ))}
        </select>
      </label>

      {sets.length === 0 && (
        <p className="muted">
          This build ships no scenario sets, so no instance can be created. Sets are added
          server-side and cannot be supplied from here.
        </p>
      )}

      <label>
        Minimum score
        <input
          type="text"
          {...fieldErrorProps(stepId, issues, "minimumScore")}
          value={draft.minimumScore}
          onChange={(e) => onEdit({ minimumScore: e.target.value })}
        />
        <span className="field-hint">
          Between 0 and 1. A release scoring below this cannot be promoted.
        </span>
      </label>

      <h3>What will be created</h3>
      <dl className="parsed-facts">
        <div><dt>Instance</dt><dd>{draft.displayName || "Unnamed"}</dd></div>
        <div><dt>Slug</dt><dd className="mono">{draft.slug || "unset"}</dd></div>
        <div>
          <dt>Model</dt>
          <dd>{selectedModel
            ? `${selectedModel.provider} / ${selectedModel.model}`
            : <span className="qualifier">Not chosen</span>}</dd>
        </div>
        <div><dt>Collections</dt><dd>{draft.collections.length}</dd></div>
        <div>
          <dt>Document types</dt>
          <dd>{draft.allowedDocumentTypes.join(", ") || <span className="qualifier">None</span>}</dd>
        </div>
        <div><dt>Tools</dt><dd>{draft.tools.length}</dd></div>
        <div>
          <dt>Output schema</dt>
          <dd>{draft.schemaId || <span className="qualifier">Not chosen</span>}</dd>
        </div>
        <div>
          <dt>Corpus</dt>
          <dd>{draft.collections.map((ref) =>
            collections.find((c) => c.id === ref.collectionId)?.displayName
            ?? ref.collectionId.slice(0, 8)).join(", ")
            || <span className="qualifier">None</span>}</dd>
        </div>
      </dl>

      {missing.length > 0 && (
        <div className="warn-note" role="status">
          <p>These steps are not finished, so nothing can be created yet:</p>
          <ul className="warn-list">
            {missing.map((step) => (
              <li key={step}>
                <button type="button" className="link-button" onClick={() => onJump(step)}>
                  {STEP_TITLES[step]}
                </button>
                <span className="muted"> {localErrors(step, draft)[0]}</span>
              </li>
            ))}
          </ul>
        </div>
      )}

      <p className="ok-note">
        Creating this makes a candidate release. It is <strong>not live</strong>: production keeps
        answering with whatever it answers with now until someone evaluates this candidate and
        promotes it, which are separate and deliberate acts.
      </p>
    </div>
  );
}
