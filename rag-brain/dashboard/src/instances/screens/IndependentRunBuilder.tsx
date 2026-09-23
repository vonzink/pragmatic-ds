import { useEffect, useRef, useState } from "react";
import { brainsApi } from "../../api";
import { instanceApi } from "../api";
import { asRows } from "../rows";
import BatchGroupProgress from "../components/BatchGroupProgress";
import BatchMemberEditor from "../components/BatchMemberEditor";
import ComparisonPreflightTable from "../components/ComparisonPreflightTable";
import type { BatchMember } from "../components/BatchMemberEditor";
import type { BrainAdminDto } from "../../types";
import type { CreateRunGroupRequest, CreatedRunGroupView, PreflightView } from "../types";

/**
 * A batch of unrelated runs, submitted together.
 *
 * Nothing here is held equal. Each member names its own brain, instance, release, parse and
 * corpus, which is exactly what makes this not a comparison: there is no basis, no varied
 * dimension, and no claim that the results are comparable with each other.
 *
 * ### One group per brain
 *
 * A run group belongs to one brain — the route takes a brain UUID and the command carries a single
 * brain id — so a batch spanning three brains is three groups. The dashboard does the partitioning
 * and presents the result as one batch, but it never invents a global "active brain" to make the
 * submission look like one call. Each group is priced against its own brain's budget and carries
 * its own idempotency key, minted once and reused through retries of that group.
 *
 * ### Failure is contained
 *
 * A group that fails to submit leaves its siblings alone: the ones that succeeded keep their group
 * ids, keep polling and keep their results, and retrying reuses the failed brain's key rather than
 * re-submitting the ones that already landed. The same holds after submission — a failed member
 * inside a group never removes or cancels a successful sibling, which is what the group's
 * `PARTIAL` status is for.
 */

let memberSeq = 0;
function blank(): BatchMember {
  return {
    key: `b${++memberSeq}`,
    brainId: "",
    instanceSlug: "",
    releaseId: null,
    registrationId: null,
    corpusSnapshotId: null,
  };
}

interface Partition {
  brainId: string;
  brainName: string;
  members: BatchMember[];
}

/** Groups members by brain, preserving the order members were added in. */
export function partitionByBrain(
  members: BatchMember[], brains: BrainAdminDto[],
): Partition[] {
  const byBrain = new Map<string, Partition>();
  for (const member of members) {
    if (!ready(member)) continue;
    let partition = byBrain.get(member.brainId);
    if (!partition) {
      partition = {
        brainId: member.brainId,
        brainName: brains.find((b) => b.id === member.brainId)?.displayName ?? member.brainId,
        members: [],
      };
      byBrain.set(member.brainId, partition);
    }
    partition.members.push(member);
  }
  return [...byBrain.values()];
}

/** A member the server could actually resolve. Everything below is required, none of it defaulted. */
export function ready(member: BatchMember): boolean {
  return Boolean(member.brainId && member.instanceSlug && member.releaseId
    && member.registrationId && member.corpusSnapshotId);
}

function requestFor(partition: Partition): CreateRunGroupRequest {
  return {
    mode: "INDEPENDENT",
    comparisonDimension: null,
    members: partition.members.map((m) => ({
      instanceSlug: m.instanceSlug,
      releaseId: m.releaseId,
      registrationId: m.registrationId!,
      corpusSnapshotId: m.corpusSnapshotId,
    })),
  };
}

interface Submitted {
  brainId: string;
  brainName: string;
  groupId: string;
}

export default function IndependentRunBuilder() {
  const [brains, setBrains] = useState<BrainAdminDto[]>([]);
  const [members, setMembers] = useState<BatchMember[]>(() => [blank()]);
  const [estimates, setEstimates] = useState<Record<string, PreflightView>>({});
  const [estimating, setEstimating] = useState(false);
  const [estimateErrors, setEstimateErrors] = useState<Record<string, string>>({});

  const [submitted, setSubmitted] = useState<Submitted[]>([]);
  const [submitErrors, setSubmitErrors] = useState<Record<string, string>>({});
  const [submitting, setSubmitting] = useState(false);

  // One key per brain, held across retries. A key is dropped only when that brain's group has
  // actually been created, so retrying a partially-failed batch cannot re-submit what landed.
  const groupKeys = useRef<Map<string, string>>(new Map());

  useEffect(() => {
    let cancelled = false;
    brainsApi.list()
      .then((rows) => { if (!cancelled) setBrains(asRows(rows)); })
      .catch(() => { if (!cancelled) setBrains([]); });
    return () => { cancelled = true; };
  }, []);

  const partitions = partitionByBrain(members, brains);
  const partitionKey = JSON.stringify(partitions.map((p) => [p.brainId, requestFor(p)]));

  // Every partition priced against its own brain, and re-priced whenever the batch changes, so
  // Run cannot be enabled by an estimate of a batch that has since been edited.
  useEffect(() => {
    const current = (JSON.parse(partitionKey) as [string, CreateRunGroupRequest][])
      .map(([brainId, request]) => ({ brainId, request }));
    if (current.length === 0) { setEstimates({}); setEstimateErrors({}); return; }

    let cancelled = false;
    setEstimates({});
    setEstimateErrors({});
    setEstimating(true);

    Promise.allSettled(current.map((p) =>
      instanceApi
        .post<PreflightView>(
          `/api/ai/admin/instances/run-groups/preflight?brain=${encodeURIComponent(p.brainId)}`,
          p.request)
        .then((view) => ({ brainId: p.brainId, view }))))
      .then((results) => {
        if (cancelled) return;
        const priced: Record<string, PreflightView> = {};
        const failed: Record<string, string> = {};
        results.forEach((result, index) => {
          if (result.status === "fulfilled") priced[result.value.brainId] = result.value.view;
          // One brain failing to price must not hide the others' prices.
          else failed[current[index].brainId] = (result.reason as Error).message;
        });
        setEstimates(priced);
        setEstimateErrors(failed);
        setEstimating(false);
      });

    return () => { cancelled = true; };
  }, [partitionKey]);

  const incomplete = members.filter((m) => !ready(m)).length;
  // An unfinished row blocks the whole batch rather than dropping out of it. Submitting four of
  // five members because the fifth was half-filled spends real money on a batch nobody asked for,
  // and there is no undo — so the warning is a gate, not a note.
  const priced = partitions.length > 0
    && incomplete === 0
    && partitions.every((p) => estimates[p.brainId]?.acceptable === true);
  const outstanding = partitions.filter(
    (p) => !submitted.some((s) => s.brainId === p.brainId));

  async function submit() {
    if (!priced || submitting) return;
    setSubmitting(true);
    setSubmitErrors({});

    for (const partition of outstanding) {
      if (!groupKeys.current.has(partition.brainId)) {
        groupKeys.current.set(partition.brainId, crypto.randomUUID());
      }
    }

    const results = await Promise.allSettled(outstanding.map((partition) =>
      instanceApi
        .postIdempotent<CreatedRunGroupView>(
          `/api/ai/admin/instances/run-groups?brain=${encodeURIComponent(partition.brainId)}`,
          requestFor(partition), groupKeys.current.get(partition.brainId)!)
        .then((created) => ({ partition, created }))));

    const created: Submitted[] = [];
    const failed: Record<string, string> = {};
    results.forEach((result, index) => {
      if (result.status === "fulfilled") {
        created.push({
          brainId: result.value.partition.brainId,
          brainName: result.value.partition.brainName,
          groupId: result.value.created.groupId,
        });
        // Landed, so this brain is done and its key is spent.
        groupKeys.current.delete(result.value.partition.brainId);
      } else {
        // Key retained: retrying this brain resolves to the group its first attempt may have
        // created rather than starting a second one.
        failed[outstanding[index].brainId] = (result.reason as Error).message;
      }
    });

    // Appended, never replaced. A brain that failed on this attempt must not remove the groups
    // other brains already have running.
    setSubmitted((prev) => [...prev, ...created]);
    setSubmitErrors(failed);
    setSubmitting(false);
  }

  return (
    <div className="screen workbench">
      <h1>Run independently</h1>
      <p className="screen-intro">
        Each member runs on its own — its own brain, instance, release, parse and corpus. Nothing
        is held equal and the results are not comparable with each other; use Compare for that.
      </p>

      <section className="card workbench-card">
        <div className="member-head">
          <h2>Members</h2>
          <button type="button" className="btn"
                  onClick={() => setMembers((prev) => [...prev, blank()])}>
            Add member
          </button>
        </div>

        <ul className="member-list">
          {members.map((m, index) => (
            <BatchMemberEditor
              key={m.key}
              index={index}
              member={m}
              brains={brains}
              canRemove={members.length > 1}
              onChange={(next) => setMembers(
                (prev) => prev.map((p) => (p.key === m.key ? next : p)))}
              onRemove={() => setMembers((prev) => prev.filter((p) => p.key !== m.key))}
            />
          ))}
        </ul>

        {incomplete > 0 && (
          <p className="warn-note" role="status">
            {incomplete === 1
              ? "1 member still needs a brain, instance, release, parsed revision and frozen "
                + "corpus. The batch cannot start until it has them, or until it is removed."
              : `${incomplete} members still need a brain, instance, release, parsed revision `
                + "and frozen corpus. The batch cannot start until they have them, or until they "
                + "are removed."}
          </p>
        )}
      </section>

      {partitions.length > 1 && (
        <p className="muted">
          This batch spans {partitions.length} brains, so it becomes {partitions.length} run
          groups — one per brain, each priced against that brain's own budget.
        </p>
      )}

      {estimating && (
        <section className="card workbench-card"><p className="muted">Estimating…</p></section>
      )}

      {partitions.map((partition) => (
        <section key={partition.brainId} className="batch-partition">
          <h2 className="partition-head">{partition.brainName}</h2>
          {estimateErrors[partition.brainId] && (
            <div className="error-note" role="alert">
              {partition.brainName} could not be estimated, so it will not be started.{" "}
              {estimateErrors[partition.brainId]}
            </div>
          )}
          {estimates[partition.brainId] && (
            <ComparisonPreflightTable preflight={estimates[partition.brainId]} />
          )}
          {submitErrors[partition.brainId] && (
            <div className="error-note" role="alert">
              {partition.brainName} could not be started ({submitErrors[partition.brainId]}).
              Other brains in this batch are unaffected; retrying starts only this one.
            </div>
          )}
        </section>
      ))}

      {outstanding.length > 0 && (
        <div className="workbench-actions">
          <button
            type="button"
            className="btn-primary"
            disabled={!priced || submitting}
            onClick={() => void submit()}
          >
            {submitting
              ? "Starting…"
              : Object.keys(submitErrors).length > 0
                ? `Retry ${outstanding.length} group${outstanding.length === 1 ? "" : "s"}`
                : `Run ${outstanding.length} group${outstanding.length === 1 ? "" : "s"}`}
          </button>
        </div>
      )}

      {submitted.map((group) => (
        <BatchGroupProgress
          key={group.groupId}
          brainId={group.brainId}
          brainName={group.brainName}
          groupId={group.groupId}
        />
      ))}
    </div>
  );
}
