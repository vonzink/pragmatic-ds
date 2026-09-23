import ComparisonResultsTable from "./ComparisonResultsTable";
import { useRunGroupPolling } from "../hooks/useRunGroupPolling";

/**
 * One brain's group inside a batch, watched on its own.
 *
 * A batch spanning three brains is three groups, and this is one of them. Each polls its own brain
 * with its own explicit UUID, which is what lets the batch be presented together without any
 * screen anywhere deciding which brain is "the" active one.
 *
 * Failure is contained here too. A group that fails, or a brain that stops answering, affects this
 * panel and nothing else — its siblings keep their groups, keep polling and keep their results.
 */
export default function BatchGroupProgress(
  { brainId, brainName, groupId }: {
    brainId: string;
    brainName: string;
    groupId: string;
  },
) {
  const polling = useRunGroupPolling(brainId, groupId);

  return (
    <section className="batch-group">
      <div className="member-head">
        <h3>{brainName}</h3>
        <span className={`badge badge-${(polling.detail?.group.status ?? "queued").toLowerCase()}`}>
          {polling.detail?.group.status ?? "Submitted"}
        </span>
      </div>

      {polling.transientError && (
        // About the asking, not about the runs. The group is still there.
        <p className="warn-note" role="status">
          Could not refresh {brainName} just now ({polling.transientError}). Still watching.
        </p>
      )}

      {polling.detail && <ComparisonResultsTable detail={polling.detail} />}
    </section>
  );
}
