import { useCallback, useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { brainsApi } from "../../api";
import { instanceApi } from "../api";
import type { BrainAdminDto } from "../../types";
import type { RunGroupStatus, RunGroupSummaryView } from "../types";

/**
 * What is queued, what is running, and what finished.
 *
 * Operational visibility, not another execution form. Nothing here starts work — the only actions
 * are filtering and following a link — because a screen that both shows a backlog and can add to
 * it invites adding to it while reading it.
 *
 * Two shapes come from the backend rather than from preference. `GET /run-groups` takes one brain
 * and returns a plain list, so this fans out per brain exactly as the landing page does and there
 * is no pagination to offer. And the summary carries no cost and no per-member breakdown; both
 * live on the group detail, which decrypts member output and is a deliberately narrower surface
 * than a listing. So the queue counts what it can count and links to detail for the rest, rather
 * than fetching every group's detail to render one page.
 */

const STATUSES: RunGroupStatus[] = [
  "QUEUED", "PROCESSING", "SUCCEEDED", "PARTIAL", "FAILED", "CANCELLED",
];

interface Row extends RunGroupSummaryView {
  brainDisplayName: string;
}

export default function InstanceRunQueue() {
  const [rows, setRows] = useState<Row[]>([]);
  const [brains, setBrains] = useState<BrainAdminDto[]>([]);
  const [unavailable, setUnavailable] = useState<string[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [brainFilter, setBrainFilter] = useState<string>("");
  const [statusFilter, setStatusFilter] = useState<string>("");

  const load = useCallback(async (signal: { cancelled: boolean }) => {
    setLoading(true);
    setError(null);

    let all: BrainAdminDto[];
    try {
      all = await brainsApi.list();
    } catch (e) {
      if (!signal.cancelled) {
        setError((e as Error).message);
        setRows([]);
        setLoading(false);
      }
      return;
    }
    if (signal.cancelled) return;
    setBrains(all);

    const targets = brainFilter ? all.filter((b) => b.id === brainFilter) : all;
    const query = statusFilter ? `&status=${encodeURIComponent(statusFilter)}` : "";

    const settled = await Promise.allSettled(
      targets.map((brain) =>
        instanceApi
          .get<RunGroupSummaryView[]>(
            `/api/ai/admin/instances/run-groups?brain=${encodeURIComponent(brain.id)}${query}`)
          .then((groups) => groups.map((group) => ({
            ...group,
            brainDisplayName: brain.displayName,
          })))),
    );
    if (signal.cancelled) return;

    const loaded: Row[] = [];
    const failed: string[] = [];
    settled.forEach((result, index) => {
      if (result.status === "fulfilled") loaded.push(...result.value);
      else failed.push(targets[index].displayName);
    });

    // Newest first: the thing you came to look at is almost always the most recent submission.
    loaded.sort((a, b) => b.createdAt.localeCompare(a.createdAt));

    setRows(loaded);
    setUnavailable(failed);
    setLoading(false);
  }, [brainFilter, statusFilter]);

  useEffect(() => {
    const signal = { cancelled: false };
    void load(signal);
    return () => { signal.cancelled = true; };
  }, [load]);

  const counts = {
    queued: rows.filter((r) => r.status === "QUEUED").length,
    processing: rows.filter((r) => r.status === "PROCESSING").length,
    terminal: rows.filter((r) =>
      r.status !== "QUEUED" && r.status !== "PROCESSING").length,
  };

  return (
    <div className="screen">
      <header className="screen-head">
        <h1>Run queue</h1>
      </header>

      <div className="filters">
        <label>
          Brain
          <select value={brainFilter} onChange={(e) => setBrainFilter(e.target.value)}>
            <option value="">All brains</option>
            {brains.map((b) => (
              <option key={b.id} value={b.id}>{b.displayName}</option>
            ))}
          </select>
        </label>
        <label>
          Status
          <select value={statusFilter} onChange={(e) => setStatusFilter(e.target.value)}>
            <option value="">Any status</option>
            {STATUSES.map((s) => <option key={s} value={s}>{s}</option>)}
          </select>
        </label>
      </div>

      <dl className="queue-counts">
        <div><dt>Queued</dt><dd>{counts.queued}</dd></div>
        <div><dt>Processing</dt><dd>{counts.processing}</dd></div>
        <div><dt>Terminal</dt><dd>{counts.terminal}</dd></div>
      </dl>

      {loading && <p className="muted">Loading run groups…</p>}
      {error && (
        <div className="error-note" role="alert">Could not load run groups. {error}</div>
      )}
      {unavailable.length > 0 && (
        <div className="warn-note" role="status">
          Run groups in {unavailable.join(", ")} could not be listed.
        </div>
      )}

      {!loading && !error && rows.length === 0 && (
        <div className="empty-note"><p>No run groups match.</p></div>
      )}

      {rows.length > 0 && (
        <div className="table-scroll">
          <table className="data-table">
            <caption className="sr-only">Run groups, newest first</caption>
            <thead>
              <tr>
                <th scope="col">Submitted</th>
                <th scope="col">Brain</th>
                <th scope="col">Mode</th>
                <th scope="col">Status</th>
                <th scope="col">Members</th>
                <th scope="col">Detail</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => (
                <tr key={row.groupId}>
                  <td>{new Date(row.createdAt).toLocaleString()}</td>
                  <td>{row.brainDisplayName}</td>
                  <td>
                    {row.mode}
                    {row.comparisonDimension && (
                      <span className="muted"> · {row.comparisonDimension}</span>
                    )}
                  </td>
                  <td>
                    <span className={`badge badge-${row.status.toLowerCase()}`}>{row.status}</span>
                    {/* A cancellation that has been asked for but not yet reached every member is
                        its own state; showing only the status would lose it. */}
                    {row.cancellationRequestedAt && row.status === "PROCESSING" && (
                      <span className="muted"> cancelling</span>
                    )}
                  </td>
                  <td className="num">{row.memberCount}</td>
                  <td>
                    <Link to={`/instance-runs/${encodeURIComponent(row.groupId)}`}>Open</Link>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
