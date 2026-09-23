import TokenCostBreakdown from "./TokenCostBreakdown";
import type { MemberDetailView } from "../types";

/**
 * What the run produced, and how much of what it says about itself is measured.
 *
 * The usage figures are rendered by {@link TokenCostBreakdown}, which owns the rule that a missing
 * number is never shown as zero. This panel adds what is specific to a single run: the output
 * itself, how it ended, how long it took, and the four ids that make it reproducible.
 */
/**
 * How long the run took, from the two timestamps the member carries.
 *
 * There is no latency field on the wire, and inventing one would mean guessing which clock it
 * belonged to. This is the wall time between the member being created and reaching a terminal
 * status — queue wait included, because that is what the operator waited.
 */
function duration(createdAt: string, terminalAt: string | null): string | null {
  if (!terminalAt) return null;
  const ms = Date.parse(terminalAt) - Date.parse(createdAt);
  if (!Number.isFinite(ms) || ms < 0) return null;
  return ms < 1000 ? `${ms} ms` : `${(ms / 1000).toFixed(1)} s`;
}

export default function RunResultPanel({ member }: { member: MemberDetailView }) {
  const succeeded = member.status === "SUCCEEDED";

  return (
    <section className="card workbench-card">
      <h2>Result</h2>

      <p className="result-status">
        <span className={`badge badge-${member.status.toLowerCase()}`}>{member.status}</span>
        {member.failureCode && (
          // A stable code, rendered verbatim. The taxonomy is value-free by design: a failure
          // message can quote a request URI or a provider's response body.
          <span className="mono"> {member.failureCode}</span>
        )}
      </p>

      {succeeded && member.result && (
        <pre className="result-body">{JSON.stringify(member.result, null, 2)}</pre>
      )}

      <h3>Usage</h3>
      <TokenCostBreakdown estimate={null} member={member} />

      <h3>Provenance</h3>
      {/* What makes the run reproducible. Every one of these was pinned at submission, so the
          same four values re-run give the same analysis of the same bytes. */}
      <dl className="parsed-facts">
        <div><dt>Model</dt><dd>{member.provider && member.model
          ? `${member.provider} / ${member.model}` : "Unavailable"}</dd></div>
        <div><dt>Release</dt><dd className="mono" title={member.releaseId}>
          {member.releaseId.slice(0, 8)}…</dd></div>
        <div><dt>Parsed revision</dt><dd className="mono" title={member.registrationId}>
          {member.registrationId.slice(0, 8)}…</dd></div>
        <div><dt>Corpus snapshot</dt><dd className="mono" title={member.corpusSnapshotId}>
          {member.corpusSnapshotId.slice(0, 8)}…</dd></div>
        <div><dt>Run</dt><dd className="mono" title={member.runId}>
          {member.runId.slice(0, 8)}…</dd></div>
        <div>
          <dt>Elapsed</dt>
          <dd>{duration(member.createdAt, member.terminalAt)
            ?? <span className="qualifier">Still running</span>}</dd>
        </div>
        <div><dt>Pricing version</dt><dd className="mono" title={member.pricingVersionId ?? ""}>
          {member.pricingVersionId ? `${member.pricingVersionId.slice(0, 8)}…` : "Unavailable"}</dd></div>
      </dl>
    </section>
  );
}
