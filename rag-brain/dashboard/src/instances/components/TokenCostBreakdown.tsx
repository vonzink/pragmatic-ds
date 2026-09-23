import type { MemberDetailView, MemberEstimateView } from "../types";

/**
 * What a run was expected to consume, and what it actually did.
 *
 * This module owns one rule for the whole dashboard: **a missing number is never rendered as
 * zero.** A provider that does not report cached tokens has not reported zero cached tokens, and
 * an operator reconciling an invoice against this screen would be misled by the difference. Every
 * actual figure is nullable all the way to the pixel, and `usageQuality` decides the label:
 *
 * - `REPORTED` — the provider said so. Shown plain.
 * - `INFERRED` — derived rather than reported, marked `Inferred`.
 * - `UNAVAILABLE` — not reported at all, shown as `Unavailable`.
 * - `PENDING` — the run has not finished reporting yet.
 *
 * Expected figures are always ranges and always marked `Estimated`, so a bound can never be read
 * as a measurement. Keeping both on screen after a run finishes is the point of the comparison:
 * an actual that lands outside its own estimate is the interesting result, and dropping the
 * expected columns at completion would hide exactly that.
 */

/** One measured figure, or an honest statement that there is no measurement. */
export function UsageValue(
  { value, quality }: { value: number | null; quality: string | null },
) {
  if (value === null || value === undefined) {
    return (
      <span className="qualifier">{quality === "PENDING" ? "Pending" : "Unavailable"}</span>
    );
  }
  return (
    <>
      {value.toLocaleString()}
      {quality === "INFERRED" && <span className="qualifier"> Inferred</span>}
    </>
  );
}

/** The same rule for money, which needs a currency mark and no thousands grouping. */
export function CostValue(
  { value, quality }: { value: number | null; quality: string | null },
) {
  if (value === null || value === undefined) {
    return (
      <span className="qualifier">{quality === "PENDING" ? "Pending" : "Unavailable"}</span>
    );
  }
  return (
    <>
      ${value}
      {quality === "INFERRED" && <span className="qualifier"> Inferred</span>}
    </>
  );
}

/** A bound, marked as one. There is deliberately no way to render an estimate as a single number. */
export function EstimatedRange(
  { min, max, money = false }: { min: number; max: number; money?: boolean },
) {
  const lo = money ? `$${min}` : min.toLocaleString();
  const hi = money ? `$${max}` : max.toLocaleString();
  return (
    <>
      {lo} – {hi}
      <span className="qualifier"> Estimated</span>
    </>
  );
}

/**
 * Both halves for one member, as a definition list.
 *
 * The list form is what the comparison falls back to below the table breakpoint: same labels,
 * same figures, nothing dropped. A narrow screen that quietly hid the cost columns would be the
 * one place an operator could approve a batch without seeing what it costs.
 */
export default function TokenCostBreakdown(
  { estimate, member }: {
    estimate: MemberEstimateView | null;
    member: MemberDetailView | null;
  },
) {
  // A member carries its own expected figures, pinned when it was submitted. Preferring them over
  // a live estimate matters after the fact: re-pricing a finished run against today's rates would
  // quietly rewrite what it was approved at.
  const expected = member
    ? {
      inputMin: member.expectedInputMin, inputMax: member.expectedInputMax,
      outputMin: member.expectedOutputMin, outputMax: member.expectedOutputMax,
      costMin: member.expectedCostUsdMin, costMax: member.expectedCostUsdMax,
      quality: member.estimateQuality,
    }
    : estimate
      ? {
        inputMin: estimate.inputTokensMin, inputMax: estimate.inputTokensMax,
        outputMin: estimate.outputTokensMin, outputMax: estimate.outputTokensMax,
        costMin: estimate.costUsdMin, costMax: estimate.costUsdMax,
        quality: estimate.estimateQuality,
      }
      : null;

  const q = member?.usageQuality ?? null;

  return (
    <dl className="parsed-facts">
      {expected && (
        <>
          <div>
            <dt>Expected input</dt>
            <dd><EstimatedRange min={expected.inputMin} max={expected.inputMax} /></dd>
          </div>
          <div>
            <dt>Expected output</dt>
            <dd><EstimatedRange min={expected.outputMin} max={expected.outputMax} /></dd>
          </div>
          <div>
            <dt>Expected cost</dt>
            <dd><EstimatedRange min={expected.costMin} max={expected.costMax} money /></dd>
          </div>
          <div>
            {/* EXACT means a provider tokenizer priced it. Anything else is a conservative range,
                and the ceiling is the number to plan against. */}
            <dt>Estimate quality</dt>
            <dd>{expected.quality ?? <span className="qualifier">Unavailable</span>}</dd>
          </div>
        </>
      )}

      {member && (
        <>
          <div>
            <dt>Actual input</dt>
            <dd><UsageValue value={member.actualInputTokens} quality={q} /></dd>
          </div>
          <div>
            <dt>Cached input</dt>
            <dd><UsageValue value={member.actualCachedTokens} quality={q} /></dd>
          </div>
          <div>
            <dt>Actual output</dt>
            <dd><UsageValue value={member.actualOutputTokens} quality={q} /></dd>
          </div>
          <div>
            <dt>Actual total</dt>
            <dd><UsageValue value={member.actualTotalTokens} quality={q} /></dd>
          </div>
          <div>
            <dt>Actual est. cost</dt>
            <dd><CostValue value={member.actualCostUsd} quality={q} /></dd>
          </div>
          <div>
            <dt>Usage quality</dt>
            <dd>{q ?? <span className="qualifier">Unavailable</span>}</dd>
          </div>
        </>
      )}
    </dl>
  );
}
