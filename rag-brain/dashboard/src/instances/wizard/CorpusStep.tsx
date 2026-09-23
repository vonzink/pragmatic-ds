import { StepViolations } from "./StepProps";
import type { StepProps } from "./StepProps";

/**
 * The evidence this instance may cite, pinned at the version it is pinned at.
 *
 * A release names a collection *and* a version, which is what makes it reproducible: adding a
 * document to a collection afterwards does not silently change what an existing release retrieves.
 * The consequence is that a collection edited between this page loading and the release being
 * created is refused rather than re-pinned, so the version is shown beside every choice.
 */
export default function CorpusStep({ draft, onEdit, violations, collections }: StepProps) {
  const chosen = new Map(draft.collections.map((c) => [c.collectionId, c.collectionVersion]));

  function toggle(id: string, version: number) {
    onEdit({
      collections: chosen.has(id)
        ? draft.collections.filter((c) => c.collectionId !== id)
        : [...draft.collections, { collectionId: id, collectionVersion: version }],
    });
  }

  const active = collections.filter((c) => c.state === "ACTIVE");
  const moved = draft.collections.some((ref) => {
    const current = active.find((c) => c.id === ref.collectionId);
    return current !== undefined && current.version !== ref.collectionVersion;
  });

  return (
    <div className="wizard-step">
      <StepViolations violations={violations} />

      <p className="field-hint">
        Each collection is pinned at the version shown. Editing it later does not change what
        releases already created retrieve; it produces a new version for the next release to pin.
      </p>

      {active.length === 0 && (
        <p className="muted">
          This brain has no active corpus collections. Create one in the corpus library first.
        </p>
      )}

      <ul className="collection-choices">
        {active.map((collection) => (
          <li key={collection.id}>
            <label>
              <input
                type="checkbox"
                checked={chosen.has(collection.id)}
                onChange={() => toggle(collection.id, collection.version)}
              />
              {collection.displayName}
              <span className="muted">
                {" "}v{collection.version}, {collection.documentCount} documents
              </span>
            </label>
          </li>
        ))}
      </ul>

      {/* A pin that no longer matches the live version is the one failure this step can carry into
          the next one, so it is named here rather than discovered at review. */}
      {moved && (
        <p className="warn-note" role="status">
          A chosen collection has changed since it was picked. Unpick and re-pick it to pin the
          current version.
        </p>
      )}
    </div>
  );
}
