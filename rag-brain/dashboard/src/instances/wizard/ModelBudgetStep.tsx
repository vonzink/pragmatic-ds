import { StepViolations, fieldErrorProps } from "./StepProps";
import type { StepProps } from "./StepProps";

/**
 * The model, and the ceilings that keep one run from spending a day's budget.
 *
 * The model comes from the deployment's catalog and nowhere else. There is no free-text field here
 * by design: a model this deployment has no credential for is one that fails at dispatch, long
 * after the release was authored and promoted, and a select cannot express that mistake.
 *
 * The maximum expected cost is compared against the brain's daily budget by the server. An instance
 * whose single run could exceed the whole day's budget can never run at all, so it is refused while
 * its author is still looking at the form rather than at its first dispatch.
 */
export default function ModelBudgetStep(
  { draft, onEdit, violations, models, stepId, issues }: StepProps,
) {
  const selected = models.find(
    (m) => m.provider === draft.provider && m.model === draft.model) ?? null;

  return (
    <div className="wizard-step">
      <StepViolations violations={violations} />

      <label>
        Model
        <select
          {...fieldErrorProps(stepId, issues, "model")}
          value={selected ? `${draft.provider} ${draft.model}` : ""}
          onChange={(e) => {
            const [provider, model] = e.target.value.split(" ");
            onEdit({ provider: provider ?? "", model: model ?? "" });
          }}
        >
          <option value="">Choose a configured model</option>
          {models.map((m) => (
            <option key={`${m.provider}/${m.model}`} value={`${m.provider} ${m.model}`}>
              {m.provider} / {m.model}
            </option>
          ))}
        </select>
        {models.length === 0 && (
          <span className="field-hint">
            No models are configured for this deployment, so no instance can be created yet.
          </span>
        )}
      </label>

      {selected && (
        <dl className="parsed-facts">
          <div>
            <dt>Context ceiling</dt>
            <dd>{selected.contextTokenCeiling.toLocaleString()}</dd>
          </div>
          <div>
            <dt>Output ceiling</dt>
            <dd>{selected.outputTokenCeiling.toLocaleString()}</dd>
          </div>
          <div>
            {/* EXACT means estimates for this model are priced by a real tokenizer rather than by
                a conservative character count. */}
            <dt>Tokenizer</dt>
            <dd>{selected.tokenizerStrategy}</dd>
          </div>
          <div>
            <dt>Rates per million</dt>
            <dd>
              ${selected.inputUsdPerMillion} in, ${selected.cachedInputUsdPerMillion} cached,{" "}
              ${selected.outputUsdPerMillion} out
            </dd>
          </div>
        </dl>
      )}

      <label>
        If the model is unavailable
        <select
          value={draft.fallbackPolicy}
          onChange={(e) => onEdit({ fallbackPolicy: e.target.value as "NONE" | "CONFIGURED" })}
        >
          <option value="NONE">Fail the run</option>
          <option value="CONFIGURED">Use the deployment's configured fallback</option>
        </select>
        <span className="field-hint">
          Failing is the safer default: a fallback answers with a model the release was never
          evaluated against, and the result would carry that model's name and this release's number.
        </span>
      </label>

      <h3>Limits</h3>
      {LIMIT_FIELDS.map(({ field, label, hint }) => (
        <label key={field}>
          {label}
          <input
            type="text"
            {...fieldErrorProps(stepId, issues, field)}
            value={draft[field]}
            onChange={(e) => onEdit({ [field]: e.target.value } as never)}
          />
          {hint && <span className="field-hint">{hint}</span>}
        </label>
      ))}
    </div>
  );
}

const LIMIT_FIELDS: {
  field: "maximumInputTokens" | "maximumRetrievedTokens" | "maximumOutputTokens"
  | "maximumDiscussionTokens" | "maximumConcurrentRuns" | "maximumExpectedCostUsd";
  label: string;
  hint?: string;
}[] = [
  { field: "maximumInputTokens", label: "Maximum input tokens" },
  {
    field: "maximumRetrievedTokens",
    label: "Maximum retrieved tokens",
    hint: "How much corpus may be pulled into one prompt.",
  },
  { field: "maximumOutputTokens", label: "Maximum output tokens" },
  {
    field: "maximumDiscussionTokens",
    label: "Maximum discussion tokens",
    hint: "Zero turns follow-up questions off entirely.",
  },
  { field: "maximumConcurrentRuns", label: "Maximum concurrent runs" },
  {
    field: "maximumExpectedCostUsd",
    label: "Maximum expected cost (USD)",
    hint: "Per run. Refused if it exceeds this brain's whole daily budget.",
  },
];
