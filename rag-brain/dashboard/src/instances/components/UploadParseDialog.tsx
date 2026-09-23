import { useRef, useState } from "react";

/**
 * The fallback: hand the engine an original and let it parse.
 *
 * Secondary on purpose. This spends a parse, and the usual case is that one already exists. It is
 * here because an operator testing a new document type may have nothing registered yet, and
 * sending them away to another system to produce one would stall the work.
 *
 * One file, because the endpoint takes exactly one and rejects anything else. Saying so here beats
 * letting someone select four and reading a 400.
 */
export default function UploadParseDialog(
  { onUpload, busy }: { onUpload: (file: File) => void; busy: boolean },
) {
  const [file, setFile] = useState<File | null>(null);
  const input = useRef<HTMLInputElement>(null);

  return (
    <form
      className="parse-form"
      onSubmit={(e) => {
        e.preventDefault();
        if (file && !busy) onUpload(file);
      }}
    >
      <label>
        Document
        <input ref={input} type="file" onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
        <span className="field-hint">
          One file. The engine parses it and RAG Brain analyzes the versioned envelope it
          produces — never the document itself.
        </span>
      </label>
      <button className="btn" type="submit" disabled={!file || busy}>
        {busy ? "Uploading…" : "Upload and parse"}
      </button>
    </form>
  );
}
