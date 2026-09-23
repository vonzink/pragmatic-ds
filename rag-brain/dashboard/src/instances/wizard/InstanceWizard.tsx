import { useEffect, useReducer, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { brainsApi } from "../../api";
import { instanceApi } from "../api";
import { asRows } from "../rows";
import BehaviorToolsStep from "./BehaviorToolsStep";
import CorpusStep from "./CorpusStep";
import EvaluationReviewStep from "./EvaluationReviewStep";
import IdentityParserStep from "./IdentityParserStep";
import ModelBudgetStep from "./ModelBudgetStep";
import OutputStep from "./OutputStep";
import { useWizardOptions } from "../hooks/useWizardOptions";
import {
  STEPS,
  STEP_SCOPE,
  STEP_TITLES,
  initialState,
  localIssues,
  toCommand,
  toValidationBody,
  wizardReducer,
} from "./WizardState";
import { StepIssues } from "./StepProps";
import type { StepProps } from "./StepProps";
import type { BrainAdminDto } from "../../types";
import type {
  CandidateReleaseView,
  CatalogModelView,
  CollectionSummary,
  ValidationView,
} from "../types";

/**
 * Six steps, one submission, and a candidate at the end of it.
 *
 * The shell owns three things the steps deliberately do not: where the options come from, when the
 * server is asked, and the single write at the end.
 *
 * ### Two validators, not one
 *
 * Each step is gated locally on what this screen can decide by itself, and then on what only the
 * server can. Advancing calls the stateless validator for that step's scope; Review validates the
 * whole command. Both write nothing and neither takes an idempotency key, which is why they can be
 * called freely — requiring a key for a pure check would be theatre and would push a client into
 * minting one per keystroke.
 *
 * A refusal is filed to the step that owns the field rather than to the step that was being left,
 * so a complete-command check on Review sends someone to the control they need to change.
 *
 * ### One write
 *
 * Creation is a single idempotent POST. The key is minted once and reused through every retry, and
 * the draft is kept through failures: it is prompts and limits somebody typed, and discarding it
 * because a request timed out would be the worst response available. Success is the only thing
 * that spends both.
 *
 * ### It does not go live
 *
 * Creating an instance creates a candidate. Nothing this screen does changes what production
 * answers with, and it says so where a reasonable person would assume otherwise.
 */
export default function InstanceWizard() {
  const [state, dispatch] = useReducer(wizardReducer, undefined, initialState);
  const [brains, setBrains] = useState<BrainAdminDto[]>([]);
  const [brainId, setBrainId] = useState("");
  const [collections, setCollections] = useState<CollectionSummary[]>([]);
  const [models, setModels] = useState<CatalogModelView[]>([]);
  const { options, loading: optionsLoading, error: optionsError } = useWizardOptions();

  const createKey = useRef<string | null>(null);
  const navigate = useNavigate();

  useEffect(() => {
    let cancelled = false;
    brainsApi.list()
      .then((rows) => { if (!cancelled) setBrains(asRows(rows)); })
      .catch(() => { if (!cancelled) setBrains([]); });
    instanceApi.get<CatalogModelView[]>("/api/ai/admin/instances/model-catalog")
      .then((rows) => { if (!cancelled) setModels(asRows(rows)); })
      .catch(() => { if (!cancelled) setModels([]); });
    return () => { cancelled = true; };
  }, []);

  useEffect(() => {
    if (!brainId) { setCollections([]); return; }
    let cancelled = false;
    instanceApi
      .get<CollectionSummary[]>(
        `/api/ai/admin/instances/corpus-collections?brain=${encodeURIComponent(brainId)}`)
      .then((rows) => { if (!cancelled) setCollections(asRows(rows)); })
      .catch(() => { if (!cancelled) setCollections([]); });
    return () => { cancelled = true; };
  }, [brainId]);

  // The parser versions are the deployment's, not this screen's. They land in the draft only once
  // the server has said what they are, so a wizard that never reached the server cannot advance.
  useEffect(() => {
    if (!options) return;
    dispatch({
      type: "edit",
      patch: {
        envelopeVersion: options.envelopeVersion,
        canonicalizationVersion: options.canonicalizationVersion,
      },
    });
  }, [options]);

  // A selection the deployment stopped offering is not a selection. Re-checked whenever either
  // catalog arrives or changes, so the choice goes back to the person making it.
  useEffect(() => {
    if (models.length === 0 && collections.length === 0) return;
    dispatch({ type: "catalog", models, collections });
  }, [models, collections]);

  const step = state.step;
  const stepIndex = STEPS.indexOf(step);
  const localProblems = localIssues(step, state.draft);
  const locallyValid = localProblems.length === 0;
  const blocked = STEPS.some((id) => state.violations[id].length > 0);

  // Review checks the whole command rather than one section, and does it on arrival rather than
  // behind a button. It writes nothing, so there is no reason to make someone ask; and a refusal
  // that only appeared after pressing Create would arrive at the moment it is least useful.
  //
  // Keyed on the serialized body, so it re-runs when the draft changes and not when its own
  // result lands.
  const completeBody = step === "review" ? JSON.stringify(toValidationBody(state.draft)) : null;
  useEffect(() => {
    if (!completeBody || !brainId) return;
    let cancelled = false;
    dispatch({ type: "validating", value: true });

    instanceApi
      .post<ValidationView>(
        `/api/ai/admin/instances/validate?brain=${encodeURIComponent(brainId)}&scope=COMPLETE`,
        JSON.parse(completeBody))
      .then((view) => {
        if (!cancelled) dispatch({ type: "violations", violations: asRows(view.violations) });
      })
      .catch((e) => {
        if (cancelled) return;
        // Unreached is not approved. Create stays shut until the server has actually agreed.
        dispatch({
          type: "violations",
          violations: [{ section: "COMPLETE", code: (e as Error).message }],
        });
      });

    return () => { cancelled = true; };
  }, [completeBody, brainId]);

  /** Asks the server about one scope, then advances only if it agreed. */
  async function advance() {
    if (!locallyValid || !brainId || state.validating) return;
    dispatch({ type: "validating", value: true });
    try {
      const view = await instanceApi.post<ValidationView>(
        `/api/ai/admin/instances/validate?brain=${encodeURIComponent(brainId)}`
        + `&scope=${STEP_SCOPE[step]}`,
        toValidationBody(state.draft));
      dispatch({ type: "violations", violations: asRows(view.violations) });
      if (view.valid) dispatch({ type: "next" });
    } catch (e) {
      // A validator that could not be reached has not approved anything, so the step does not
      // advance. Reported as a refusal on this step rather than as a silent no-op.
      dispatch({
        type: "violations",
        violations: [{ section: STEP_SCOPE[step], code: (e as Error).message }],
      });
    }
  }

  async function create() {
    const command = toCommand(state.draft);
    if (!command || !brainId || state.submitting) return;
    if (createKey.current === null) createKey.current = crypto.randomUUID();
    dispatch({ type: "submitting" });
    try {
      const release = await instanceApi.postIdempotent<CandidateReleaseView>(
        `/api/ai/admin/instances?brain=${encodeURIComponent(brainId)}`,
        command, createKey.current);
      createKey.current = null;
      dispatch({ type: "created", release });
      navigate(`/instances/${encodeURIComponent(brainId)}`
        + `/${encodeURIComponent(state.draft.slug)}/releases`);
    } catch (e) {
      // Key retained: if the response was lost rather than the request, retrying resolves to the
      // candidate the first attempt already created instead of authoring a second one.
      dispatch({ type: "submitFailed", message: (e as Error).message });
    }
  }

  const stepProps: StepProps = {
    draft: state.draft,
    onEdit: (patch) => dispatch({ type: "edit", patch }),
    violations: state.violations[step],
    stepId: step,
    issues: localProblems,
    options,
    collections,
    models,
  };

  return (
    <div className="screen wizard">
      <h1>Create an instance</h1>

      {optionsError && (
        <div className="error-note" role="alert">
          This deployment could not say what it supports, so nothing can be authored against it.
          {" "}{optionsError}
        </div>
      )}

      <ol className="wizard-steps" aria-label="Steps">
        {STEPS.map((id, index) => (
          <li key={id} aria-current={id === step ? "step" : undefined}>
            <button
              type="button"
              className={id === step ? "link-button current" : "link-button"}
              // Backwards only. Jumping forward would skip the server check the skipped step owes.
              disabled={index > stepIndex}
              onClick={() => dispatch({ type: "goto", step: id })}
            >
              {index + 1}. {STEP_TITLES[id]}
            </button>
            {state.violations[id].length > 0 && (
              <span className="badge badge-failed">{state.violations[id].length}</span>
            )}
          </li>
        ))}
      </ol>

      <label className="brain-picker">
        Brain
        <select value={brainId} disabled={stepIndex > 0}
                onChange={(e) => setBrainId(e.target.value)}>
          <option value="">Choose a brain</option>
          {brains.map((b) => <option key={b.id} value={b.id}>{b.displayName}</option>)}
        </select>
        <span className="field-hint">
          {stepIndex > 0
            ? "Fixed once the corpus is chosen: collections belong to one brain."
            : "The instance and its corpus live in this brain."}
        </span>
      </label>

      <section className="card wizard-card">
        <h2>{STEP_TITLES[step]}</h2>

        {optionsLoading && <p className="muted">Loading what this deployment supports...</p>}

        {step === "identity" && <IdentityParserStep {...stepProps} />}
        {step === "corpus" && <CorpusStep {...stepProps} />}
        {step === "model" && <ModelBudgetStep {...stepProps} />}
        {step === "behavior" && <BehaviorToolsStep {...stepProps} />}
        {step === "output" && <OutputStep {...stepProps} />}
        {step === "review" && (
          <EvaluationReviewStep {...stepProps}
                                onJump={(id) => dispatch({ type: "goto", step: id })} />
        )}

        {/* Still one strip saying everything, and still below the step. The ids it now mints are
            what the controls above point back at, so a refusal is reachable both by reading down
            the card and by tabbing onto the field it is about. */}
        <StepIssues stepId={step} issues={localProblems} />

        {state.submitError && (
          <div className="error-note" role="alert">
            Nothing was created. {state.submitError}
          </div>
        )}
      </section>

      <div className="workbench-actions">
        <button type="button" className="btn" disabled={stepIndex === 0}
                onClick={() => dispatch({ type: "back" })}>
          Back
        </button>

        {step !== "review" ? (
          <button type="button" className="btn-primary"
                  disabled={!locallyValid || !brainId || state.validating}
                  onClick={() => void advance()}>
            {state.validating ? "Checking..." : "Next"}
          </button>
        ) : (
          <button type="button" className="btn-primary"
                  disabled={toCommand(state.draft) === null || !brainId || state.submitting
                    || state.validating || blocked}
                  onClick={() => void create()}>
            {state.submitting
              ? "Creating..."
              : state.submitError ? "Retry" : "Create candidate"}
          </button>
        )}

        <button type="button" className="link-button" onClick={() => dispatch({ type: "reset" })}>
          Cancel
        </button>
      </div>
    </div>
  );
}
