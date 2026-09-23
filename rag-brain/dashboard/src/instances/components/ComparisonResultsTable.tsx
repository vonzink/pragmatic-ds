import { CostValue, EstimatedRange, UsageValue } from "./TokenCostBreakdown";
import type { MemberDetailView, RunGroupDetailView } from "../types";

/**
 * How the members actually differed.
 *
 * The expected columns stay on screen beside the actual ones. Dropping them at completion would
 * hide the result most worth having — an actual that landed outside the range the run was approved
 * against — and would leave the estimate unfalsifiable in exactly the case that matters.
 *
 * ### Partial groups
 *
 * A member that failed does not invalidate its siblings, and this table refuses to collapse the
 * group into a single error. Each row carries its own status and its own sanitized failure code,
 * and the group's `PARTIAL` says plainly that some members produced usable answers and some did
 * not. An operator who ran four members and got three answers has three answers.
 */
export default function ComparisonResultsTable({ detail }: { detail: RunGroupDetailView }) {
  const partial = detail.group.status === "PARTIAL";

  return (
    <section className="card workbench-card">
      <div className="member-head">
        <h2>Results</h2>
        <span className={`badge badge-${detail.group.status.toLowerCase()}`}>
          {detail.group.status}
        </span>
      </div>

      {partial && (
        <p className="warn-note" role="status">
          Some members finished and some did not. Every successful member's answer is below and is
          unaffected by its siblings; each failure carries its own code.
        </p>
      )}

      <div className="table-scroll">
        <table className="data-table responsive-table">
          <caption className="sr-only">
            Result, expected and actual tokens, and cost for each comparison member
          </caption>
          <thead>
            <tr>
              <th scope="col">Member</th>
              <th scope="col">Instance / release</th>
              <th scope="col">Provider / model</th>
              <th scope="col">Result</th>
              <th scope="col">Citations</th>
              <th scope="col">Latency</th>
              <th scope="col">Expected input</th>
              <th scope="col">Expected output</th>
              <th scope="col">Expected cost</th>
              <th scope="col">Actual input</th>
              <th scope="col">Cached input</th>
              <th scope="col">Actual output</th>
              <th scope="col">Actual total</th>
              <th scope="col">Actual est. cost</th>
              <th scope="col">Quality</th>
            </tr>
          </thead>
          <tbody>
            {detail.members.map((m) => (
              <tr key={m.runId}>
                <th scope="row" data-label="Member">{m.memberIndex + 1}</th>
                <td data-label="Instance / release">
                  {m.instanceSlug}
                  <span className="mono muted"> {m.releaseId.slice(0, 8)}…</span>
                </td>
                <td data-label="Provider / model">
                  {m.provider && m.model
                    ? `${m.provider} / ${m.model}`
                    : <span className="qualifier">Unavailable</span>}
                </td>
                <td data-label="Result">
                  <span className={`badge badge-${m.status.toLowerCase()}`}>{m.status}</span>
                  {m.failureCode && <span className="mono"> {m.failureCode}</span>}
                </td>
                <td data-label="Citations" className="num">{citationCount(m)}</td>
                <td data-label="Latency" className="num">
                  {latency(m) ?? <span className="qualifier">Still running</span>}
                </td>
                <td data-label="Expected input" className="num">
                  <EstimatedRange min={m.expectedInputMin} max={m.expectedInputMax} />
                </td>
                <td data-label="Expected output" className="num">
                  <EstimatedRange min={m.expectedOutputMin} max={m.expectedOutputMax} />
                </td>
                <td data-label="Expected cost" className="num">
                  <EstimatedRange min={m.expectedCostUsdMin} max={m.expectedCostUsdMax} money />
                </td>
                <td data-label="Actual input" className="num">
                  <UsageValue value={m.actualInputTokens} quality={m.usageQuality} />
                </td>
                <td data-label="Cached input" className="num">
                  <UsageValue value={m.actualCachedTokens} quality={m.usageQuality} />
                </td>
                <td data-label="Actual output" className="num">
                  <UsageValue value={m.actualOutputTokens} quality={m.usageQuality} />
                </td>
                <td data-label="Actual total" className="num">
                  <UsageValue value={m.actualTotalTokens} quality={m.usageQuality} />
                </td>
                <td data-label="Actual est. cost" className="num">
                  <CostValue value={m.actualCostUsd} quality={m.usageQuality} />
                </td>
                <td data-label="Quality">
                  {m.usageQuality ?? <span className="qualifier">Unavailable</span>}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {detail.members.filter((m) => m.result).map((m) => (
        <details key={m.runId} className="member-output">
          <summary>Member {m.memberIndex + 1} output</summary>
          <pre className="result-body">{JSON.stringify(m.result, null, 2)}</pre>
        </details>
      ))}
    </section>
  );
}

/**
 * How many sources the answer cited, when the output says so.
 *
 * There is no citations field on the wire — the output schema belongs to the instance, so whether
 * citations exist and what they are called is the instance's business. Reading a `citations` array
 * if one is there is worth doing; inventing a zero when it is not, is not.
 */
function citationCount(member: MemberDetailView) {
  const cited = (member.result as { citations?: unknown } | null)?.citations;
  if (!Array.isArray(cited)) return <span className="qualifier">Unavailable</span>;
  return cited.length;
}

/** Wall time from submission to a terminal status. Queue wait included: it is time that passed. */
function latency(member: MemberDetailView): string | null {
  if (!member.terminalAt) return null;
  const ms = Date.parse(member.terminalAt) - Date.parse(member.createdAt);
  if (!Number.isFinite(ms) || ms < 0) return null;
  return ms < 1000 ? `${ms} ms` : `${(ms / 1000).toFixed(1)} s`;
}
