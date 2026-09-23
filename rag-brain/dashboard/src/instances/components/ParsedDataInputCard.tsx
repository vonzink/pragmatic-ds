import { useState } from "react";
import ExistingParseDialog from "./ExistingParseDialog";
import UploadParseDialog from "./UploadParseDialog";
import ParsedPackageSummary from "./ParsedPackageSummary";
import type { PinnedParsedInput } from "../types";

/**
 * Where a run gets its input, and the one paragraph explaining why it works this way.
 *
 * RAG Brain analyzes a validated, versioned envelope produced by the Document Engine. It does not
 * read the original document, and it does not fall back to reading one when the envelope is
 * missing or rejected — it refuses. That is worth stating on the screen rather than only in a
 * design document, because "why can't I just upload a PDF and get an answer" is the first
 * question this card provokes, and the answer is the guarantee the product is built on.
 */
export default function ParsedDataInputCard(
  { parsed, busy, error, onSelectExisting, onUpload }: {
    parsed: PinnedParsedInput | null;
    busy: boolean;
    error: string | null;
    onSelectExisting: (packageId: string, revision: number | null, sourceIds: string[]) => void;
    onUpload: (file: File) => void;
  },
) {
  const [showUpload, setShowUpload] = useState(false);

  return (
    <section className="card workbench-card">
      <h2>Parsed data</h2>
      <p className="muted">
        This instance analyzes a validated versioned envelope from the Document Engine. It never
        reads the original document, and if the envelope is missing or fails verification the run
        is refused rather than answered from raw text.
      </p>

      {error && <div className="error-note" role="alert">{error}</div>}

      {parsed && <ParsedPackageSummary parsed={parsed} />}

      {!parsed && (
        <>
          <ExistingParseDialog onSelect={onSelectExisting} busy={busy} />

          <div className="fallback">
            <button
              type="button"
              className="link-button"
              aria-expanded={showUpload}
              onClick={() => setShowUpload((v) => !v)}
            >
              No parse yet? Upload and parse one
            </button>
            {showUpload && <UploadParseDialog onUpload={onUpload} busy={busy} />}
          </div>
        </>
      )}
    </section>
  );
}
