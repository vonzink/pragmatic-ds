import { useInstanceReleases } from "../hooks/useInstanceReleases";
import type { InstanceSummary, ReleaseSummary } from "../types";

/**
 * One row of the member list, before anything has been priced or run.
 *
 * `provider` and `model` are carried alongside the release id rather than looked up again later.
 * They are what the `Model` dimension is varied on, and the editor is the only place that has
 * already resolved them — re-fetching every instance's releases at validation time to recover two
 * strings it just had would be a second read for no new information.
 */
export interface MemberDraft {
  /** Stable across re-orders and removals, so React keys survive editing. */
  key: string;
  instanceSlug: string;
  releaseId: string | null;
  provider: string | null;
  model: string | null;
}

export function describeRelease(release: ReleaseSummary): string {
  const model = release.provider && release.model
    ? `${release.provider} / ${release.model}` : "model unavailable";
  return `r${release.releaseNumber} · ${model}${release.live ? " · Live" : ""}`;
}

/**
 * One comparison member.
 *
 * Every member names an instance and a release explicitly, and both stay on screen after they are
 * chosen. That is not decoration: a comparison whose members you cannot tell apart at a glance is
 * a comparison whose result you cannot attribute, and the single most common way to misread one is
 * to forget that the release you are looking at is a candidate production never answers with.
 *
 * The instance selector appears only when the instance is what is being varied. Under the `Model`
 * and `Release` dimensions the server requires every member to share one instance, so offering the
 * choice would be offering a way to fail preflight.
 */
export default function ComparisonMemberEditor(
  { index, member, dimension, brainId, instances, onChange, onRemove, canRemove }: {
    index: number;
    member: MemberDraft;
    dimension: "MODEL" | "RELEASE" | "INSTANCE";
    brainId: string;
    instances: InstanceSummary[];
    onChange: (next: MemberDraft) => void;
    onRemove: () => void;
    canRemove: boolean;
  },
) {
  const { releases, error } = useInstanceReleases(brainId, member.instanceSlug);
  const chosen = releases?.find((r) => r.releaseId === member.releaseId) ?? null;
  const label = `Member ${index + 1}`;

  return (
    <li className="card member-editor">
      <div className="member-head">
        <h3>{label}</h3>
        {canRemove && (
          <button type="button" className="link-button" onClick={onRemove}>
            Remove {label}
          </button>
        )}
      </div>

      {dimension === "INSTANCE" && (
        <label>
          {`${label} instance`}
          <select
            value={member.instanceSlug}
            onChange={(e) => onChange(
              // The release belonged to the old instance. Keeping it would name a release from
              // somewhere else, which resolves to `RELEASE_NOT_FOUND` rather than to anything.
              { ...member, instanceSlug: e.target.value,
                releaseId: null, provider: null, model: null })}
          >
            <option value="">Choose an instance</option>
            {instances.map((i) => (
              <option key={i.slug} value={i.slug}>{i.displayName}</option>
            ))}
          </select>
        </label>
      )}

      <label>
        {`${label} release`}
        <select
          value={member.releaseId ?? ""}
          disabled={!member.instanceSlug || releases === null}
          onChange={(e) => {
            const picked = releases?.find((r) => r.releaseId === e.target.value) ?? null;
            onChange({
              ...member,
              releaseId: picked?.releaseId ?? null,
              provider: picked?.provider ?? null,
              model: picked?.model ?? null,
            });
          }}
        >
          <option value="">Choose a release</option>
          {(releases ?? []).map((r) => (
            <option key={r.releaseId} value={r.releaseId}>{describeRelease(r)}</option>
          ))}
        </select>
      </label>

      {error && <p className="error-note" role="alert">{error}</p>}

      {releases !== null && releases.length === 0 && member.instanceSlug && !error && (
        <p className="muted">This instance has no releases yet.</p>
      )}

      {chosen && (
        <p className="member-summary">
          <span className={`badge ${chosen.live ? "badge-live" : "badge-candidate"}`}>
            {chosen.live ? "Live" : "Candidate"}
          </span>
          <span className="muted"> {member.instanceSlug} · r{chosen.releaseNumber} · </span>
          {chosen.provider && chosen.model
            ? `${chosen.provider} / ${chosen.model}`
            : <span className="qualifier">model unavailable</span>}
        </p>
      )}
    </li>
  );
}
