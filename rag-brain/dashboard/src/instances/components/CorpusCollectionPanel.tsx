import { useState } from "react";
import { instanceApi } from "../api";
import type { CollectionDetail, CollectionSummary } from "../types";

/**
 * The collections this release retrieves from, and what editing one would cost.
 *
 * ### Sharing is the whole point of this panel
 *
 * Collections are referenced, not copied. Several releases can pin the same collection, and a
 * release pins an exact *version* of it — so editing a collection does not change what existing
 * releases retrieve, it produces a new version that a future release could pin.
 *
 * That is a good property and a confusing one, because "edit this collection" reads like "change
 * what this instance uses" and is not. So the panel says how many releases reference each
 * collection before offering anything that changes it.
 *
 * ### Clone before you edit
 *
 * Cloning copies membership by reference — the same documents, a new collection, no document rows
 * duplicated anywhere. It is the answer to "I want this corpus but slightly different" that does
 * not disturb whatever else is pointing at the original.
 *
 * Membership edits carry the version the caller believes they are editing. A collection that moved
 * since this page loaded is refused rather than overwritten, which is what stops two people
 * editing one corpus and the second silently winning.
 */
export default function CorpusCollectionPanel(
  { brainId, collections, pinned, onChanged }: {
    brainId: string;
    collections: CollectionSummary[];
    /** Collection ids and versions this release pins, from its own configuration. */
    pinned: { collectionId: string; collectionVersion: number }[];
    onChanged: () => void;
  },
) {
  const [cloning, setCloning] = useState<string | null>(null);
  const [slug, setSlug] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const byId = new Map(collections.map((c) => [c.id, c]));

  async function clone(sourceId: string) {
    if (busy || !slug.trim() || !displayName.trim()) return;
    setBusy(true);
    setError(null);
    try {
      await instanceApi.postIdempotent<CollectionDetail>(
        `/api/ai/admin/instances/corpus-collections/${encodeURIComponent(sourceId)}/clone`
        + `?brain=${encodeURIComponent(brainId)}`,
        { slug: slug.trim(), displayName: displayName.trim() },
        crypto.randomUUID());
      setCloning(null);
      setSlug("");
      setDisplayName("");
      onChanged();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="card workbench-card">
      <h3>Corpus collections</h3>
      <p className="muted">
        This release pins each collection at an exact version. Editing a collection later does not
        change what this release retrieves; it produces a new version for a future release to pin.
      </p>

      {error && <div className="error-note" role="alert">{error}</div>}

      {pinned.length === 0 && <p className="muted">This release pins no collections.</p>}

      <ul className="member-list">
        {pinned.map((ref) => {
          const collection = byId.get(ref.collectionId);
          const moved = collection !== undefined && collection.version !== ref.collectionVersion;

          return (
            <li key={ref.collectionId} className="card collection-row">
              <div className="member-head">
                <h4>{collection?.displayName ?? ref.collectionId.slice(0, 8)}</h4>
                {collection && (
                  <span className={`badge ${moved ? "badge-candidate" : "badge-live"}`}>
                    {moved ? "Moved on" : "Current"}
                  </span>
                )}
              </div>

              <dl className="parsed-facts">
                <div>
                  <dt>Pinned version</dt>
                  <dd>v{ref.collectionVersion}</dd>
                </div>
                <div>
                  <dt>Latest version</dt>
                  <dd>{collection
                    ? `v${collection.version}`
                    : <span className="qualifier">No longer in this brain</span>}</dd>
                </div>
                <div>
                  <dt>Documents</dt>
                  <dd>{collection?.documentCount
                    ?? <span className="qualifier">Unavailable</span>}</dd>
                </div>
                <div>
                  <dt>Cloned from</dt>
                  <dd>{collection?.clonedFromId
                    ? <span className="mono">{collection.clonedFromId.slice(0, 8)}</span>
                    : <span className="qualifier">Original</span>}</dd>
                </div>
              </dl>

              {moved && (
                <p className="warn-note" role="status">
                  This collection has changed since the release pinned it. The release still
                  retrieves v{ref.collectionVersion}; promoting it is refused until a release pins
                  the current version.
                </p>
              )}

              {cloning === ref.collectionId ? (
                <form
                  className="parse-form"
                  onSubmit={(e) => { e.preventDefault(); void clone(ref.collectionId); }}
                >
                  <label>
                    New collection slug
                    <input type="text" value={slug} onChange={(e) => setSlug(e.target.value)} />
                  </label>
                  <label>
                    New collection name
                    <input type="text" value={displayName}
                           onChange={(e) => setDisplayName(e.target.value)} />
                  </label>
                  <span className="field-hint">
                    The clone references the same documents. Nothing is duplicated and the original
                    is untouched.
                  </span>
                  <div className="workbench-actions">
                    <button type="submit" className="btn-primary" disabled={busy}>
                      {busy ? "Cloning..." : "Clone by reference"}
                    </button>
                    <button type="button" className="btn" onClick={() => setCloning(null)}>
                      Cancel
                    </button>
                  </div>
                </form>
              ) : (
                <button type="button" className="btn"
                        onClick={() => setCloning(ref.collectionId)}>
                  Clone {collection?.displayName ?? "collection"}
                </button>
              )}
            </li>
          );
        })}
      </ul>
    </section>
  );
}
