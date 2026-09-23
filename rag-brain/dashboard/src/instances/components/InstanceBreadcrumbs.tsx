import { Link } from "react-router-dom";
import type { InstanceDetail } from "../types";

/**
 * Where you are, and what production is actually answering with.
 *
 * The trail itself is two levels and could have been a back link. It carries the release summary
 * because that is the fact an operator most needs before touching anything: which release is live,
 * what model it pins, and — the one worth a badge — whether a candidate exists that production is
 * *not* using. Someone who has just built a candidate and forgotten to promote it will otherwise
 * read a run's output as the live behaviour.
 */
export default function InstanceBreadcrumbs(
  { instance, loading }: { instance: InstanceDetail | null; loading: boolean },
) {
  return (
    <nav className="crumbs" aria-label="Breadcrumb">
      <ol>
        <li><Link to="/instances">Instances</Link></li>
        <li aria-current="page">
          {loading ? "…" : instance?.displayName ?? "Unknown instance"}
        </li>
      </ol>

      {instance && (
        <p className="crumbs-summary">
          {instance.state === "DISABLED" ? (
            <span className="badge badge-idle">Disabled</span>
          ) : instance.liveReleaseNumber !== null ? (
            <span className="badge badge-live">Live r{instance.liveReleaseNumber}</span>
          ) : (
            <span className="badge badge-idle">No live release</span>
          )}

          {instance.hasCandidateRelease && (
            <span className="badge badge-candidate">Candidate not live</span>
          )}

          <span className="muted">
            {instance.provider && instance.model
              ? `${instance.provider} / ${instance.model}`
              : "No model pinned"}
          </span>
        </p>
      )}
    </nav>
  );
}
