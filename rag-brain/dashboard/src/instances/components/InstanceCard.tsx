import { Link } from "react-router-dom";
import type { InstanceWithBrain } from "../hooks/useInstanceCatalog";

/**
 * One instance, as the thing you click.
 *
 * The link carries the brain UUID and the slug in its own path, which is the whole scoping rule of
 * this phase expressed in markup: the workspace it opens knows what it is showing without reading
 * the sidebar. The brain's display name appears as secondary text, because an operator picks
 * "Income", not "the income instance of the mortgage brain".
 *
 * Status is rendered from what the server actually said. A disabled instance says disabled; an
 * instance with no live release says so rather than showing a blank where a release number goes,
 * since "no release has been promoted" and "the release number failed to load" must not look alike.
 */
export default function InstanceCard({ instance }: { instance: InstanceWithBrain }) {
  const disabled = instance.state === "DISABLED";
  const live = instance.liveReleaseNumber !== null;

  return (
    <li className="instance-card">
      <Link
        to={`/instances/${encodeURIComponent(instance.brainId)}/${encodeURIComponent(instance.slug)}/workbench`}
        className="instance-card-link"
      >
        <span className="instance-card-name">{instance.displayName}</span>
        <span className="instance-card-brain">{instance.brainDisplayName}</span>
      </Link>

      <p className="instance-card-status">
        {disabled ? (
          <span className="badge badge-idle">Disabled</span>
        ) : live ? (
          <span className="badge badge-live">Live r{instance.liveReleaseNumber}</span>
        ) : (
          <span className="badge badge-idle">No live release</span>
        )}

        {/* A candidate that is not live is the state most worth surfacing: it means somebody has
            built something that production is not yet answering with. */}
        {instance.hasCandidateRelease && !disabled && (
          <span className="badge badge-candidate">
            {instance.candidateCount} candidate{instance.candidateCount === 1 ? "" : "s"} pending
          </span>
        )}
      </p>

      <dl className="instance-card-facts">
        <div>
          <dt>Model</dt>
          {/* Null means no live release pinned one, not that the model is unknown-and-broken. */}
          <dd>{instance.provider && instance.model
            ? `${instance.provider} / ${instance.model}`
            : "Not pinned"}</dd>
        </div>
        <div>
          <dt>Collections</dt>
          <dd>{instance.collectionCount ?? "Not pinned"}</dd>
        </div>
      </dl>

      {/* Limitations are codes from the release manifest, rendered verbatim. Translating them into
          prose here would put words in the manifest's mouth. */}
      {instance.limitationFlags.length > 0 && (
        <p className="instance-card-limits">
          <span className="muted">Limitations:</span>{" "}
          {instance.limitationFlags.join(", ")}
        </p>
      )}
    </li>
  );
}
