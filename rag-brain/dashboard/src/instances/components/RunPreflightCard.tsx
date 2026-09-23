import type { PreflightView } from "../types";

/**
 * What a run would cost, before anything is spent.
 *
 * Preflight creates nothing and calls no provider, which is what makes it safe to show
 * automatically rather than behind a button somebody has to know to press.
 *
 * Everything here is a range, and it is labelled as one. A single number would read as a price;
 * these are bounds derived from a token estimate, and when no provider tokenizer was available the
 * estimate is a conservative character-based range rather than an exact count. The estimate
 * quality says which, because an operator deciding whether to run fifty of these needs to know
 * whether the ceiling is real.
 */
export default function RunPreflightCard(
  { preflight, loading }: { preflight: PreflightView | null; loading: boolean },
) {
  if (loading) return <section className="card workbench-card"><p className="muted">Estimating…</p></section>;
  if (!preflight) return null;

  const member = preflight.members[0] ?? null;
  const remaining = preflight.dailyBudgetUsd > 0
    ? preflight.dailyBudgetUsd - preflight.committedTodayUsd - preflight.alreadyReservedUsd
    : null;

  return (
    <section className="card workbench-card">
      <h2>Estimate</h2>

      {member && (
        <dl className="parsed-facts">
          <div>
            <dt>Input tokens</dt>
            <dd>
              {member.inputTokensMin.toLocaleString()}–{member.inputTokensMax.toLocaleString()}
              <span className="qualifier"> Estimated</span>
            </dd>
          </div>
          <div>
            <dt>Output tokens</dt>
            <dd>
              {member.outputTokensMin.toLocaleString()}–{member.outputTokensMax.toLocaleString()}
              <span className="qualifier"> Estimated</span>
            </dd>
          </div>
          <div>
            <dt>Cost</dt>
            <dd>
              ${member.costUsdMin} – ${member.costUsdMax}
              <span className="qualifier"> Estimated</span>
            </dd>
          </div>
          <div><dt>Model</dt><dd>{member.provider} / {member.model}</dd></div>
          <div>
            <dt>Estimate quality</dt>
            {/* EXACT means a provider tokenizer priced it. Anything else is a conservative
                range, and the ceiling is the number to plan against. */}
            <dd>{member.estimateQuality}</dd>
          </div>
          <div>
            <dt>Pricing version</dt>
            <dd className="mono" title={member.pricingVersionId}>
              {member.pricingVersionId.slice(0, 8)}…
            </dd>
          </div>
        </dl>
      )}

      <p className="budget-line">
        Reserves ${preflight.reservedMaximumUsd} of this brain's daily budget.
        {remaining !== null && <> ${remaining.toFixed(2)} would remain.</>}
        {preflight.dailyBudgetUsd <= 0 && <span className="muted"> No daily cap is set.</span>}
      </p>

      {!preflight.acceptable && (
        <div className="error-note" role="alert">
          <p>This run would be refused:</p>
          <ul className="warn-list">
            {preflight.blockingCodes.map((code) => (
              <li key={code}><span className="mono">{code}</span></li>
            ))}
          </ul>
        </div>
      )}
    </section>
  );
}
