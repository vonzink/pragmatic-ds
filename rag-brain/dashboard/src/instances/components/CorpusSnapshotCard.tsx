import { useEffect, useState } from "react";
import { instanceApi } from "../api";
import { asRows } from "../rows";
import type { CollectionSummary, SnapshotCollectionRequest, SnapshotDetail } from "../types";

/**
 * The evidence the run is allowed to cite, frozen before it starts.
 *
 * A run pins a snapshot rather than a collection, and the difference is the whole reason this card
 * exists rather than the workbench quietly freezing whatever is there. A collection is editable;
 * documents get added to it and removed from it. A snapshot is a manifest of exact document
 * versions with a digest over them, so "the same corpus" becomes something two runs can be checked
 * against instead of something the collection's name asserts.
 *
 * Freezing carries each collection's expected version, which is what makes the freeze fail rather
 * than silently capture an edit somebody made between this screen loading and the button being
 * pressed.
 *
 * Everything is selected by default because the common case is "all of it", and an operator who
 * meant all of it should not have to prove it with eight clicks.
 */
export default function CorpusSnapshotCard(
  { brainId, snapshot, busy, error, onFreeze }: {
    brainId: string;
    snapshot: SnapshotDetail | null;
    busy: boolean;
    error: string | null;
    onFreeze: (collections: SnapshotCollectionRequest[]) => void;
  },
) {
  const [collections, setCollections] = useState<CollectionSummary[] | null>(null);
  const [excluded, setExcluded] = useState<Set<string>>(new Set());
  const [loadError, setLoadError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    instanceApi
      .get<CollectionSummary[]>(
        `/api/ai/admin/instances/corpus-collections?brain=${encodeURIComponent(brainId)}`)
      .then((rows) => { if (!cancelled) setCollections(asRows(rows)); })
      .catch((e) => { if (!cancelled) setLoadError((e as Error).message); });
    return () => { cancelled = true; };
  }, [brainId]);

  if (snapshot) {
    return (
      <section className="card workbench-card">
        <h2>Corpus</h2>
        <dl className="parsed-facts">
          <div><dt>Collections</dt><dd>{snapshot.collections.length}</dd></div>
          <div>
            <dt>Manifest</dt>
            <dd className="mono" title={snapshot.manifestSha256}>
              {snapshot.manifestSha256.slice(0, 12)}…
            </dd>
          </div>
        </dl>
        <p className="ok-note">
          Frozen. Later edits to these collections will not change what this run cites.
        </p>
      </section>
    );
  }

  const chosen = (collections ?? []).filter((c) => !excluded.has(c.id));

  return (
    <section className="card workbench-card">
      <h2>Corpus</h2>
      <p className="muted">
        Freezing records each collection at the version shown. The run cites that manifest, so
        editing a collection afterwards cannot change what the run was answered from.
      </p>

      {(error || loadError) && (
        <div className="error-note" role="alert">{error ?? loadError}</div>
      )}

      {collections === null && !loadError && <p className="muted">Loading collections…</p>}

      {collections !== null && collections.length === 0 && (
        <p className="muted">
          This brain has no corpus collections. Create one in the corpus library before running.
        </p>
      )}

      {collections !== null && collections.length > 0 && (
        <>
          <ul className="collection-choices">
            {collections.map((c) => (
              <li key={c.id}>
                <label>
                  <input
                    type="checkbox"
                    checked={!excluded.has(c.id)}
                    onChange={() => setExcluded((prev) => {
                      const next = new Set(prev);
                      if (next.has(c.id)) next.delete(c.id); else next.add(c.id);
                      return next;
                    })}
                  />
                  {c.displayName}
                  {/* The version is shown because it is what the freeze asserts, and a stale one
                      is why a freeze can legitimately fail. */}
                  <span className="muted"> · v{c.version} · {c.documentCount} documents</span>
                </label>
              </li>
            ))}
          </ul>
          <button
            type="button"
            className="btn-primary"
            disabled={busy || chosen.length === 0}
            onClick={() => onFreeze(chosen.map(
              (c) => ({ collectionId: c.id, expectedVersion: c.version })))}
          >
            {busy ? "Freezing…" : "Freeze corpus"}
          </button>
        </>
      )}
    </section>
  );
}
