import { CostValue, EstimatedRange } from "./TokenCostBreakdown";
import type { PreflightView } from "../types";

/**
 * What every member of a comparison would cost, before any of them runs.
 *
 * Side by side and in member order, because the reason to price a comparison rather than each run
 * separately is to see the difference — that one member is four times another is a fact you act on
 * before spending, not after.
 *
 * The table degrades to labelled rows on a narrow screen rather than dropping columns. Hiding cost
 * fields on a phone would make the phone the one place a batch can be approved without seeing what
 * it costs.
 */
export default function ComparisonPreflightTable({ preflight }: { preflight: PreflightView }) {
  const remaining = preflight.dailyBudgetUsd > 0
    ? preflight.dailyBudgetUsd - preflight.committedTodayUsd - preflight.alreadyReservedUsd
    : null;

  return (
    <section className="card workbench-card">
      <h2>Estimate</h2>

      <div className="table-scroll">
        <table className="data-table responsive-table">
          <caption className="sr-only">Expected tokens and cost for each comparison member</caption>
          <thead>
            <tr>
              <th scope="col">Member</th>
              <th scope="col">Instance / release</th>
              <th scope="col">Provider / model</th>
              <th scope="col">Expected input</th>
              <th scope="col">Expected output</th>
              <th scope="col">Expected cost</th>
              <th scope="col">Quality</th>
            </tr>
          </thead>
          <tbody>
            {preflight.members.map((m) => (
              <tr key={m.memberIndex}>
                <th scope="row" data-label="Member">{m.memberIndex + 1}</th>
                <td data-label="Instance / release">
                  {m.instanceSlug}
                  <span className="mono muted"> {m.releaseId.slice(0, 8)}…</span>
                </td>
                <td data-label="Provider / model">{m.provider} / {m.model}</td>
                <td data-label="Expected input" className="num">
                  <EstimatedRange min={m.inputTokensMin} max={m.inputTokensMax} />
                </td>
                <td data-label="Expected output" className="num">
                  <EstimatedRange min={m.outputTokensMin} max={m.outputTokensMax} />
                </td>
                <td data-label="Expected cost" className="num">
                  <EstimatedRange min={m.costUsdMin} max={m.costUsdMax} money />
                </td>
                {/* EXACT means a provider tokenizer priced it; anything else is a conservative
                    range whose ceiling is the number to plan against. */}
                <td data-label="Quality">{m.estimateQuality}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <p className="budget-line">
        Reserves <CostValue value={preflight.reservedMaximumUsd} quality="REPORTED" /> of this
        brain's daily budget across all members.
        {remaining !== null && <> ${remaining.toFixed(2)} would remain.</>}
        {preflight.dailyBudgetUsd <= 0 && <span className="muted"> No daily cap is set.</span>}
      </p>

      {!preflight.acceptable && (
        <div className="error-note" role="alert">
          <p>This comparison would be refused:</p>
          <ul className="warn-list">
            {preflight.blockingCodes.map((code) => (
              <li key={code}>
                <span className="mono">{code}</span>
                {EXPLANATIONS[code] && <span className="muted"> — {EXPLANATIONS[code]}</span>}
              </li>
            ))}
          </ul>
        </div>
      )}
    </section>
  );
}

/**
 * Plain-language readings of the two refusals a comparison earns on its own.
 *
 * Both are easy to hit and impossible to diagnose from the code alone. The basis mismatch in
 * particular usually means two releases of one instance differ in prompts or tools as well as in
 * model — which the dashboard cannot see, because release manifests carry prompt text and are
 * deliberately never served to it.
 */
const EXPLANATIONS: Record<string, string> = {
  COMPARISON_BASIS_MISMATCH:
    "the members differ in something this comparison is supposed to hold equal, so a difference "
    + "in their answers could not be attributed to the dimension you chose",
  COMPARISON_DIMENSION_NOT_VARIED:
    "two members are identical in the dimension being compared, so there is nothing to compare",
  CORPUS_SNAPSHOT_REQUIRED: "a member has no frozen corpus to cite",
  BUDGET_EXCEEDED: "this would reserve more than the brain's remaining daily budget",
};
