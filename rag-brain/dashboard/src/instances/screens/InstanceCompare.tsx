import { useEffect, useRef, useState } from "react";
import { instanceApi } from "../api";
import { asRows } from "../rows";
import ComparisonMemberEditor from "../components/ComparisonMemberEditor";
import ComparisonPreflightTable from "../components/ComparisonPreflightTable";
import ComparisonResultsTable from "../components/ComparisonResultsTable";
import RunInputsSection from "../components/RunInputsSection";
import { useRunGroupPolling } from "../hooks/useRunGroupPolling";
import { useRunInputs } from "../hooks/useRunInputs";
import type { MemberDraft } from "../components/ComparisonMemberEditor";
import type {
  CreateRunGroupRequest,
  CreatedRunGroupView,
  InstanceDetail,
  InstanceSummary,
  PreflightView,
} from "../types";

/**
 * Two or more runs over one parse, differing in exactly one declared thing.
 *
 * The dimension is chosen first and everything else follows from it, because the server's rule is
 * that a comparison must hold equal whatever it is not varying — and each rule is the widest one
 * that leaves the result attributable:
 *
 * - **Model** holds everything equal but the model: same parse, corpus, prompts, tools, schema.
 * - **Release** holds the parse and the instance equal. Comparing two releases *is* comparing
 *   their prompts, tools, corpus and model, so those are free to differ.
 * - **Instance** holds only the parse equal. Two instances are different products; the document
 *   they were handed is the only common ground.
 *
 * The parse and the corpus are chosen once, above the member list, which is what makes the basis
 * something this screen satisfies by construction rather than by hoping. What it cannot check
 * locally is whether two releases differ in prompts or tools — release manifests carry prompt text
 * and are deliberately never served to the dashboard — so that refusal arrives from preflight and
 * is explained there rather than guessed at here.
 *
 * ### There is no model override
 *
 * Varying the model means naming a release that carries it. Selecting a provider and model for one
 * run would be a transient override of a validated contract, and no such thing exists on the
 * server: a member references an immutable release id or it does not run. Building a release with
 * a different model is the wizard's job, which is where the manifest that would carry it is
 * authored.
 */

type Dimension = "MODEL" | "RELEASE" | "INSTANCE";

const DIMENSION_RULES: Record<Dimension, string> = {
  MODEL: "Every member must use the same instance, parse, corpus, prompts, tools and output "
    + "schema, and a different model. Choose releases that differ only in their model.",
  RELEASE: "Every member must use the same instance and parse, and a different release. "
    + "Prompts, tools, corpus and model are free to differ — that is what you are comparing.",
  INSTANCE: "Every member must use the same parse and a different instance. Nothing else is held "
    + "equal, because two instances are different products.",
};

let draftSeq = 0;
function draft(instanceSlug: string): MemberDraft {
  return { key: `m${++draftSeq}`, instanceSlug, releaseId: null, provider: null, model: null };
}

export default function InstanceCompare(
  { brainId, instanceSlug, instance }: {
    brainId: string;
    instanceSlug: string;
    instance: InstanceDetail | null;
  },
) {
  const [dimension, setDimension] = useState<Dimension>("RELEASE");
  const [members, setMembers] = useState<MemberDraft[]>(
    () => [draft(instanceSlug), draft(instanceSlug)]);
  const [instances, setInstances] = useState<InstanceSummary[]>([]);

  const [preflight, setPreflight] = useState<PreflightView | null>(null);
  const [preflightLoading, setPreflightLoading] = useState(false);
  const [preflightError, setPreflightError] = useState<string | null>(null);

  const [groupId, setGroupId] = useState<string | null>(null);
  const [runBusy, setRunBusy] = useState(false);
  const [runError, setRunError] = useState<string | null>(null);
  const runKey = useRef<string | null>(null);

  const inputs = useRunInputs(brainId, instanceSlug);
  const polling = useRunGroupPolling(brainId, groupId);

  // Needed only by the Instance dimension, but loaded once rather than on each switch: it is one
  // small read, and fetching it at the moment of switching would make the selector arrive empty.
  useEffect(() => {
    let cancelled = false;
    instanceApi
      .get<InstanceSummary[]>(`/api/ai/admin/instances?brain=${encodeURIComponent(brainId)}`)
      .then((rows) => { if (!cancelled) setInstances(asRows(rows)); })
      .catch(() => { if (!cancelled) setInstances([]); });
    return () => { cancelled = true; };
  }, [brainId]);

  function chooseDimension(next: Dimension) {
    setDimension(next);
    // Members carried over from another dimension would silently violate the new one — two
    // members of different instances are correct under Instance and refused under Release.
    setMembers([draft(next === "INSTANCE" ? "" : instanceSlug),
      draft(next === "INSTANCE" ? "" : instanceSlug)]);
    setGroupId(null);
    runKey.current = null;
  }

  const localBlockers = localComparisonBlockers(dimension, members);
  const ready = inputs.parsed?.compatibility.compatible === true
    && inputs.snapshot !== null
    && localBlockers.length === 0;

  const request: CreateRunGroupRequest | null = ready
    ? {
      mode: "COMPARISON",
      comparisonDimension: dimension,
      members: members.map((m) => ({
        instanceSlug: m.instanceSlug,
        releaseId: m.releaseId,
        registrationId: inputs.parsed!.registrationId,
        corpusSnapshotId: inputs.snapshot!.snapshotId,
      })),
    }
    : null;

  // Keyed to the exact editor state. Any edit changes the key, which clears the estimate and
  // refetches — so Create group can never be enabled by an estimate of a member list that has
  // since been changed.
  const requestKey = request ? JSON.stringify(request) : null;
  useEffect(() => {
    if (!requestKey) { setPreflight(null); return; }
    let cancelled = false;
    setPreflight(null);
    setPreflightLoading(true);
    setPreflightError(null);

    instanceApi
      .post<PreflightView>(
        `/api/ai/admin/instances/run-groups/preflight?brain=${encodeURIComponent(brainId)}`,
        JSON.parse(requestKey))
      .then((view) => { if (!cancelled) { setPreflight(view); setPreflightLoading(false); } })
      .catch((e) => {
        if (cancelled) return;
        setPreflightError((e as Error).message);
        setPreflightLoading(false);
      });

    return () => { cancelled = true; };
  }, [requestKey, brainId]);

  async function createGroup() {
    if (!request || runBusy) return;
    if (runKey.current === null) runKey.current = crypto.randomUUID();
    setRunBusy(true);
    setRunError(null);
    try {
      const created = await instanceApi.postIdempotent<CreatedRunGroupView>(
        `/api/ai/admin/instances/run-groups?brain=${encodeURIComponent(brainId)}`,
        request, runKey.current);
      setGroupId(created.groupId);
      runKey.current = null;
    } catch (e) {
      // Key retained: a lost response resolves to the group the first attempt already created
      // rather than starting a second comparison at twice the price.
      setRunError((e as Error).message);
    } finally {
      setRunBusy(false);
    }
  }

  return (
    <div className="workbench">
      <h1>Compare</h1>

      <section className="card workbench-card">
        <h2>What is being compared</h2>
        <label>
          Dimension
          <select value={dimension} onChange={(e) => chooseDimension(e.target.value as Dimension)}>
            <option value="MODEL">Model</option>
            <option value="RELEASE">Release</option>
            <option value="INSTANCE">Instance</option>
          </select>
        </label>
        <p className="muted">{DIMENSION_RULES[dimension]}</p>
        {dimension === "MODEL" && (
          <p className="muted">
            To compare a model this instance has never used, build a candidate release carrying it
            first. A run cannot override a release's model — it references the release.
          </p>
        )}
      </section>

      <RunInputsSection brainId={brainId} inputs={inputs} />

      {inputs.parsed?.compatibility.compatible && (
        <section className="card workbench-card">
          <div className="member-head">
            <h2>Members</h2>
            <button
              type="button"
              className="btn"
              onClick={() => setMembers((prev) => [...prev,
                draft(dimension === "INSTANCE" ? "" : instanceSlug)])}
            >
              Add member
            </button>
          </div>

          <ul className="member-list">
            {members.map((m, index) => (
              <ComparisonMemberEditor
                key={m.key}
                index={index}
                member={m}
                dimension={dimension}
                brainId={brainId}
                instances={instances}
                canRemove={members.length > 2}
                onChange={(next) => setMembers(
                  (prev) => prev.map((p) => (p.key === m.key ? next : p)))}
                onRemove={() => setMembers((prev) => prev.filter((p) => p.key !== m.key))}
              />
            ))}
          </ul>

          {localBlockers.length > 0 && (
            <div className="warn-note" role="status">
              <ul className="warn-list">
                {localBlockers.map((b) => <li key={b}>{b}</li>)}
              </ul>
            </div>
          )}
        </section>
      )}

      {preflightLoading && (
        <section className="card workbench-card"><p className="muted">Estimating…</p></section>
      )}

      {preflightError && (
        <div className="error-note" role="alert">
          This comparison could not be estimated, so it cannot be started. {preflightError}
        </div>
      )}

      {preflight && <ComparisonPreflightTable preflight={preflight} />}

      {!groupId && (
        <div className="workbench-actions">
          <button
            type="button"
            className="btn-primary"
            // Gated on the server's verdict about the current editor state, never on this
            // screen's opinion of it and never on a stale estimate.
            disabled={!preflight?.acceptable || runBusy}
            onClick={() => void createGroup()}
          >
            {runBusy ? "Starting…" : runError ? "Retry" : "Create group"}
          </button>
          {runError && <span className="error-note" role="alert">{runError}</span>}
        </div>
      )}

      {groupId && polling.transientError && (
        <p className="warn-note" role="status">
          Could not refresh just now ({polling.transientError}). Still watching.
        </p>
      )}

      {polling.detail && <ComparisonResultsTable detail={polling.detail} />}

      {instance?.hasCandidateRelease && (
        <p className="muted">
          This instance has a candidate release that production is not answering with. A comparison
          against it does not promote it.
        </p>
      )}
    </div>
  );
}

/**
 * What this screen can refuse before spending a request on preflight.
 *
 * Deliberately only the rules that are decidable from what the dashboard can see. The server's
 * basis check compares digests over prompts, tools and output schemas, which are never served
 * here — so a duplicate-model check is a necessary condition, not a sufficient one, and the
 * authoritative refusal still arrives from preflight.
 */
export function localComparisonBlockers(
  dimension: Dimension, members: MemberDraft[],
): string[] {
  const problems: string[] = [];
  if (members.length < 2) {
    problems.push("A comparison needs at least two members.");
  }
  if (members.some((m) => !m.instanceSlug || !m.releaseId)) {
    problems.push("Every member needs an instance and a release.");
    // Nothing below can be judged until they do, and guessing would produce contradictory advice.
    return problems;
  }

  if (dimension === "INSTANCE") {
    if (new Set(members.map((m) => m.instanceSlug)).size !== members.length) {
      problems.push("Comparing instances means every member must name a different instance.");
    }
  } else if (new Set(members.map((m) => m.releaseId)).size !== members.length) {
    problems.push(dimension === "MODEL"
      ? "Comparing models means every member must name a different release, each carrying a "
        + "different model."
      : "Comparing releases means every member must name a different release.");
  } else if (dimension === "MODEL"
      && new Set(members.map((m) => `${m.provider}/${m.model}`)).size !== members.length) {
    // Distinct releases are not enough. Two releases of one instance can carry the same model and
    // differ only in prompts, which under this dimension is nothing varied at all.
    problems.push("Two members use the same provider and model, so there is no model difference "
      + "to attribute a result to.");
  }

  return problems;
}
