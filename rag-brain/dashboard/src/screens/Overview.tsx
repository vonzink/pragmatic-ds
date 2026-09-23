import { useEffect, useState } from "react";
import { CSSProperties } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { TriangleAlert } from "lucide-react";
import { api } from "../api";
import { AuditPage, AuditRow, IngestionQuality, Stats } from "../types";
import { ErrorNote, Meter, Status, relTime } from "../components";

/* The morning check: is the brain healthy, and what needs attention. Every
   figure here comes from a live endpoint — stats, ingestion quality, and a
   sample of the audit log. Where no endpoint exists (spend, per-instance
   queues) there is no tile, rather than a mocked one. */

const SAMPLE = 50;

interface Bucket { label: string; tone?: "mid" | "warn" | "bad"; count: number }

function confidenceBuckets(rows: AuditRow[]): { buckets: Bucket[]; scored: number } {
  const scored = rows.filter((r) => r.confidence != null);
  const count = (lo: number, hi: number) =>
    scored.filter((r) => (r.confidence as number) >= lo && (r.confidence as number) < hi).length;
  return {
    scored: scored.length,
    buckets: [
      { label: "0.9–1.0", count: count(0.9, 1.01) },
      { label: "0.7–0.9", tone: "mid", count: count(0.7, 0.9) },
      { label: "0.5–0.7", tone: "warn", count: count(0.5, 0.7) },
      { label: "< 0.5", tone: "bad", count: count(-1, 0.5) },
    ],
  };
}

export default function Overview({ stats }: { stats: Stats | null }) {
  const [searchParams] = useSearchParams();
  const brain = searchParams.get("brain");
  const search = searchParams.toString();
  const suffix = search ? `?${search}` : "";
  const navigate = useNavigate();

  const [quality, setQuality] = useState<IngestionQuality | null>(null);
  const [audit, setAudit] = useState<AuditPage | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    setError(null);
    api.get<IngestionQuality>("/api/ai/admin/ingestion-quality")
      .then(setQuality)
      .catch((e) => setError((e as Error).message));
    api.get<AuditPage>(`/api/ai/admin/audit?page=0&size=${SAMPLE}&escalatedOnly=false`)
      .then(setAudit)
      .catch((e) => setError((e as Error).message));
  }, [brain]);

  const sample = audit?.items ?? [];
  const escalated = sample.filter((r) => r.escalated).length;
  const groundedPct = sample.length > 0 ? ((sample.length - escalated) / sample.length) * 100 : null;
  const { buckets, scored } = confidenceBuckets(sample);
  const recent = sample.slice(0, 5);

  const embeddedRatio = quality && quality.chunkCount > 0
    ? quality.embeddedChunkCount / quality.chunkCount : null;

  return (
    <>
      <header className="screen-head">
        <div>
          <h1>Overview</h1>
          <p>Is the brain healthy, and what needs attention.</p>
        </div>
        <div className="actions">
          <button onClick={() => navigate(`/audit${suffix}`)}>Audit log</button>
          <button className="btn-primary" onClick={() => navigate(`/console${suffix}`)}>Open console</button>
        </div>
      </header>
      <ErrorNote message={error} />

      <div className="cards">
        <div className="stat">
          <span className="stat-label">Active docs</span>
          <span className="stat-value">
            {stats ? stats.corpus.activeDocuments : "—"}
            {stats && <small> / {stats.corpus.totalDocuments}</small>}
          </span>
          <Meter ratio={stats && stats.corpus.totalDocuments > 0
            ? stats.corpus.activeDocuments / stats.corpus.totalDocuments : 0} />
        </div>
        <div className="stat">
          <span className="stat-label">Embedded chunks</span>
          <span className="stat-value">
            {quality ? quality.embeddedChunkCount.toLocaleString() : "—"}
            {quality && <small> / {quality.chunkCount.toLocaleString()}</small>}
          </span>
          <Meter ratio={embeddedRatio ?? 0}
                 tone={quality && quality.chunksMissingEmbeddingCount > 0 ? "warn" : undefined} />
        </div>
        <div className="stat">
          <span className="stat-label">Grounded answers</span>
          <span className="stat-value">{groundedPct === null ? "—" : `${groundedPct.toFixed(1)}%`}</span>
          <span className="stat-sub">
            {sample.length > 0 ? `of the last ${sample.length} answers` : "no answers logged yet"}
          </span>
        </div>
        <div className="stat">
          <span className="stat-label">Escalated</span>
          <span className="stat-value" style={escalated > 0 ? { color: "var(--warn)" } : undefined}>
            {audit ? escalated : "—"}
          </span>
          <span className="stat-sub">
            {sample.length > 0 ? `of the last ${sample.length} answers` : "no answers logged yet"}
          </span>
        </div>
      </div>

      {quality && quality.duplicateChunkTextGroups > 0 && (
        <div className="attention">
          <TriangleAlert size={18} strokeWidth={1.5} aria-hidden="true" />
          <div style={{ flex: 1, minWidth: 0 }}>
            <div className="title">
              {quality.duplicateChunkTextGroups.toLocaleString()} duplicate chunk-text groups in the corpus
            </div>
            <div className="body">
              Duplicates inflate retrieval scores and waste embedding budget.
              {quality.warnings.length > 0 && ` ${quality.warnings.length} ingestion warning${quality.warnings.length === 1 ? "" : "s"} open.`}
            </div>
          </div>
          <button onClick={() => navigate(`/corpus${suffix}`)}>Review corpus</button>
        </div>
      )}
      {quality && quality.duplicateChunkTextGroups === 0 && quality.warnings.length > 0 && (
        <div className="attention">
          <TriangleAlert size={18} strokeWidth={1.5} aria-hidden="true" />
          <div style={{ flex: 1, minWidth: 0 }}>
            <div className="title">
              {quality.warnings.length} ingestion warning{quality.warnings.length === 1 ? "" : "s"}
            </div>
            <div className="body">{quality.warnings[0]}</div>
          </div>
          <button onClick={() => navigate(`/corpus${suffix}`)}>Review corpus</button>
        </div>
      )}

      <div className="grid-2">
        <div className="card p0" role="table" aria-label="Recent answers"
             style={{ "--cols": "1fr 110px 52px 64px" } as CSSProperties}>
          <div className="card-head">
            Recent answers
            <span className="link" onClick={() => navigate(`/audit${suffix}`)}>View audit log →</span>
          </div>
          <div className="tbl-head" role="row">
            <span role="columnheader">Question</span>
            <span role="columnheader">Model</span>
            <span role="columnheader">Conf.</span>
            <span role="columnheader" style={{ textAlign: "right" }}>Time</span>
          </div>
          {recent.map((row) => (
            <div key={row.id} className="tbl-row" role="row">
              <span role="cell" className="cell-title" title={row.question}>{row.question}</span>
              <span role="cell" className="cell-sub">{row.modelName ?? "classifier"}</span>
              <span role="cell"
                    className={`cell-num${row.confidence == null ? "" : row.confidence >= 0.8 ? " good" : " warn"}`}>
                {row.confidence == null ? "—" : row.confidence.toFixed(2)}
              </span>
              <span role="cell" className="cell-num right">{relTime(row.createdAt)}</span>
            </div>
          ))}
          {audit && recent.length === 0 && (
            <div className="tbl-foot">No answers logged yet.</div>
          )}
        </div>

        <div style={{ display: "flex", flexDirection: "column", gap: 14 }}>
          <div className="card">
            <span className="section-label">Confidence distribution</span>
            {scored > 0 ? (
              <>
                <div style={{ marginTop: 8 }}>
                  {buckets.map((b) => (
                    <div key={b.label} className="dist-row">
                      <span className="lbl">{b.label}</span>
                      <Meter ratio={b.count / scored} tone={b.tone} size="lg" />
                      <span className="pct">{Math.round((b.count / scored) * 100)}%</span>
                    </div>
                  ))}
                </div>
                <div className="rail-foot">
                  {escalated} of the last {sample.length} answer{sample.length === 1 ? "" : "s"} escalated to a human
                </div>
              </>
            ) : (
              <p className="hint" style={{ marginTop: 8 }}>No scored answers yet.</p>
            )}
          </div>

          <div className="card">
            <span className="section-label">Pipeline health</span>
            {quality ? (
              <div style={{ marginTop: 4 }}>
                <div className="health-row">
                  <Status kind={quality.chunksMissingEmbeddingCount > 0 ? "warn" : "ok"}>Embeddings</Status>
                  <span className="detail">{quality.chunksMissingEmbeddingCount.toLocaleString()} missing</span>
                </div>
                <div className="health-row">
                  <Status kind={quality.chunksMissingCitationMetadata > 0 ? "warn" : "ok"}>Citations</Status>
                  <span className="detail">{quality.chunksMissingCitationMetadata.toLocaleString()} missing</span>
                </div>
                <div className="health-row">
                  <Status kind={quality.warnings.length > 0 ? "warn" : "ok"}>Ingestion</Status>
                  <span className="detail">{quality.warnings.length} warning{quality.warnings.length === 1 ? "" : "s"}</span>
                </div>
                <div className="health-row">
                  <Status kind="ok">Corpus</Status>
                  <span className="detail">
                    {quality.activeDocumentCount} of {quality.documentCount} active
                  </span>
                </div>
              </div>
            ) : (
              <p className="hint" style={{ marginTop: 8 }}>Loading…</p>
            )}
          </div>
        </div>
      </div>
    </>
  );
}
