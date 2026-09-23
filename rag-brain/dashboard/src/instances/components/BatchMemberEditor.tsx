import { useEffect, useState } from "react";
import { instanceApi } from "../api";
import { asRows } from "../rows";
import RunInputsSection from "./RunInputsSection";
import { useInstanceReleases } from "../hooks/useInstanceReleases";
import { useRunInputs } from "../hooks/useRunInputs";
import { describeRelease } from "./ComparisonMemberEditor";
import type { BrainAdminDto } from "../../types";
import type { InstanceSummary } from "../types";

/** One member of a batch. Nothing is shared with its siblings, including which brain it is in. */
export interface BatchMember {
  key: string;
  brainId: string;
  instanceSlug: string;
  releaseId: string | null;
  registrationId: string | null;
  corpusSnapshotId: string | null;
}

/**
 * One row of an independent batch, complete in itself.
 *
 * Every member carries its own brain, instance, release, parse and corpus. That is the whole
 * difference from a comparison: nothing is held equal, so nothing is chosen once at the top, and a
 * row cannot borrow context from the row above it or from a sidebar selection.
 *
 * The consequence is that each row owns its own {@link useRunInputs} — its own pin, upload and
 * freeze, with its own idempotency keys. Two rows uploading the same document to two brains are
 * two uploads, and neither key means anything to the other.
 */
export default function BatchMemberEditor(
  { index, member, brains, onChange, onRemove, canRemove }: {
    index: number;
    member: BatchMember;
    brains: BrainAdminDto[];
    onChange: (next: BatchMember) => void;
    onRemove: () => void;
    canRemove: boolean;
  },
) {
  const [instances, setInstances] = useState<InstanceSummary[]>([]);
  const { releases } = useInstanceReleases(member.brainId, member.instanceSlug);
  const inputs = useRunInputs(member.brainId, member.instanceSlug);
  const label = `Member ${index + 1}`;

  useEffect(() => {
    if (!member.brainId) { setInstances([]); return; }
    let cancelled = false;
    instanceApi
      .get<InstanceSummary[]>(
        `/api/ai/admin/instances?brain=${encodeURIComponent(member.brainId)}`)
      .then((rows) => { if (!cancelled) setInstances(asRows(rows)); })
      .catch(() => { if (!cancelled) setInstances([]); });
    return () => { cancelled = true; };
  }, [member.brainId]);

  // The row pins and freezes on its own; the parent only needs the two resulting ids. Depending on
  // the ids rather than on the objects keeps this from firing on every unrelated render.
  const registrationId = inputs.parsed?.compatibility.compatible
    ? inputs.parsed.registrationId : null;
  const corpusSnapshotId = inputs.snapshot?.snapshotId ?? null;
  useEffect(() => {
    if (registrationId === member.registrationId && corpusSnapshotId === member.corpusSnapshotId) {
      return;
    }
    onChange({ ...member, registrationId, corpusSnapshotId });
    // `onChange` and `member` are intentionally absent: this reports a change, it does not react
    // to the parent's re-rendering with the value it was just given.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [registrationId, corpusSnapshotId]);

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

      <div className="member-scope">
        <label>
          {`${label} brain`}
          <select
            value={member.brainId}
            onChange={(e) => onChange({
              // Everything below the brain belonged to the old one. A release, a registration or a
              // snapshot from another brain resolves to nothing rather than to a usable default.
              ...member, brainId: e.target.value, instanceSlug: "",
              releaseId: null, registrationId: null, corpusSnapshotId: null,
            })}
          >
            <option value="">Choose a brain</option>
            {brains.map((b) => (
              <option key={b.id} value={b.id}>{b.displayName}</option>
            ))}
          </select>
        </label>

        <label>
          {`${label} instance`}
          <select
            value={member.instanceSlug}
            disabled={!member.brainId}
            onChange={(e) => onChange({
              ...member, instanceSlug: e.target.value,
              releaseId: null, registrationId: null, corpusSnapshotId: null,
            })}
          >
            <option value="">Choose an instance</option>
            {instances.map((i) => (
              <option key={i.slug} value={i.slug}>{i.displayName}</option>
            ))}
          </select>
        </label>

        <label>
          {`${label} release`}
          <select
            value={member.releaseId ?? ""}
            disabled={!member.instanceSlug || releases === null}
            onChange={(e) => onChange({ ...member, releaseId: e.target.value || null })}
          >
            <option value="">Choose a release</option>
            {(releases ?? []).map((r) => (
              <option key={r.releaseId} value={r.releaseId}>{describeRelease(r)}</option>
            ))}
          </select>
        </label>
      </div>

      {member.instanceSlug && (
        <RunInputsSection brainId={member.brainId} inputs={inputs} />
      )}
    </li>
  );
}
