import type { PinnedParsedInput } from "../types";

/**
 * What was actually pinned, and this release's verdict on it.
 *
 * The digests are shown rather than hidden as plumbing. `envelopeSha256` and `sourceSetSha256` are
 * how a run is reproducible: two runs quoting the same digests analyzed the same bytes, and no
 * amount of matching filenames or revision numbers proves that on its own.
 *
 * Compatibility has three outcomes and they are not degrees of the same thing. Incompatible means
 * the release refuses this parse and no run can happen. Warnings mean it will run and something
 * about the parse is worth knowing first. Clean means neither.
 */
export default function ParsedPackageSummary({ parsed }: { parsed: PinnedParsedInput }) {
  const { compatibility: c } = parsed;

  return (
    <section className="parsed-summary">
      <h3>Selected parse</h3>

      <dl className="parsed-facts">
        <div><dt>Package</dt><dd className="mono">{parsed.packageId}</dd></div>
        <div><dt>Revision</dt><dd>{parsed.revision}</dd></div>
        <div><dt>Sources</dt><dd>{parsed.selectedSourceIds.length}</dd></div>
        <div><dt>Documents supported</dt><dd>{c.supportedDocumentCount}</dd></div>
        <div>
          <dt>Envelope</dt>
          {/* Truncated for reading, full value in the title so it can still be copied out. */}
          <dd className="mono" title={parsed.envelopeSha256}>
            {parsed.envelopeSha256.slice(0, 12)}…
          </dd>
        </div>
        <div>
          <dt>Source set</dt>
          <dd className="mono" title={parsed.sourceSetSha256}>
            {parsed.sourceSetSha256.slice(0, 12)}…
          </dd>
        </div>
        <div><dt>Canonicalization</dt><dd>{parsed.canonicalizationVersion}</dd></div>
      </dl>

      {!c.compatible && (
        <p className="error-note" role="alert">
          {/* The rejection is a stable code. Translating it into prose here would put words in
              the contract's mouth, and the code is what an operator can search for. */}
          This release cannot analyze this parse: <span className="mono">{c.rejection}</span>
        </p>
      )}

      {c.compatible && c.warnings.length > 0 && (
        <div className="warn-note" role="status">
          <p>Compatible, with {c.warnings.length} warning{c.warnings.length === 1 ? "" : "s"}:</p>
          <ul className="warn-list">
            {c.warnings.map((w, i) => (
              <li key={`${w.code}:${w.documentOrdinal}:${w.fieldName ?? i}`}>
                <span className="mono">{w.code}</span>
                {" · "}document {w.documentOrdinal} ({w.documentTypeCode})
                {w.fieldName && <> · field <span className="mono">{w.fieldName}</span></>}
              </li>
            ))}
          </ul>
        </div>
      )}

      {c.compatible && c.warnings.length === 0 && (
        <p className="ok-note">Compatible with this release.</p>
      )}
    </section>
  );
}
