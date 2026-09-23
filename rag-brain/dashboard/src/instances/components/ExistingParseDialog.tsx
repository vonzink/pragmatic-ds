import { useState } from "react";

/**
 * The primary way in: point at a parse that already exists.
 *
 * It is primary because the parse is normally already there — Document Manager produced it — and
 * re-uploading the same originals would spend another parse to arrive at the same envelope.
 *
 * Source selection is optional and means "all sources in the revision" when left empty. That is
 * the backend's rule, not a convenience: the parse's own ordinal decides what gets analyzed, and
 * the server sorts the selection before hashing so the same choice expressed two ways is the same
 * request.
 */
export default function ExistingParseDialog(
  { onSelect, busy }: {
    onSelect: (packageId: string, revision: number | null, sourceIds: string[]) => void;
    busy: boolean;
  },
) {
  const [packageId, setPackageId] = useState("");
  const [revision, setRevision] = useState("");
  const [sourceIds, setSourceIds] = useState("");

  const ready = packageId.trim().length > 0 && !busy;

  return (
    <form
      className="parse-form"
      onSubmit={(e) => {
        e.preventDefault();
        if (!ready) return;
        onSelect(
          packageId.trim(),
          revision.trim() ? Number(revision.trim()) : null,
          sourceIds.split(/[\s,]+/).map((s) => s.trim()).filter(Boolean),
        );
      }}
    >
      <label>
        Package ID
        <input value={packageId} onChange={(e) => setPackageId(e.target.value)}
               placeholder="UUID of the parsed package" />
      </label>
      <label>
        Revision
        <input value={revision} onChange={(e) => setRevision(e.target.value)}
               inputMode="numeric" placeholder="latest" />
        <span className="field-hint">Leave blank for the newest revision.</span>
      </label>
      <label>
        Source IDs
        <textarea value={sourceIds} onChange={(e) => setSourceIds(e.target.value)}
                  rows={2} placeholder="all sources in the revision" />
        <span className="field-hint">
          Leave blank to analyze every source. Order does not matter.
        </span>
      </label>
      <button className="btn-primary" type="submit" disabled={!ready}>
        {busy ? "Verifying…" : "Verify parse"}
      </button>
    </form>
  );
}
