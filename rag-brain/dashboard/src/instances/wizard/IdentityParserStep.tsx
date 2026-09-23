import { useState } from "react";
import { StepViolations, fieldErrorProps } from "./StepProps";
import type { StepProps } from "./StepProps";

/**
 * Who the instance is, and what it will accept as input.
 *
 * The two parser versions are shown but not editable. They are constants this build compares by
 * equality, so the only correct value is the one the server just reported — offering a field would
 * offer a way to author a release that rejects every parse it is ever given.
 *
 * Document types are typed rather than chosen, because they are the Document Engine's codes and no
 * server-side list of them exists. That is safe in a way a free-text model name would not be: an
 * unrecognised type matches no document, where an unrecognised model fails at dispatch.
 */
export default function IdentityParserStep(
  { draft, onEdit, violations, options, stepId, issues }: StepProps,
) {
  const [allowedEntry, setAllowedEntry] = useState("");
  const [requiredEntry, setRequiredEntry] = useState("");
  const named = (...fields: string[]) => fieldErrorProps(stepId, issues, ...fields);

  function addType(field: "allowedDocumentTypes" | "requireAnyDocumentTypes", raw: string) {
    const code = raw.trim().toUpperCase();
    if (!code || draft[field].includes(code)) return;
    onEdit({ [field]: [...draft[field], code] } as never);
  }

  function removeType(field: "allowedDocumentTypes" | "requireAnyDocumentTypes", code: string) {
    onEdit({ [field]: draft[field].filter((t) => t !== code) } as never);
  }

  return (
    <div className="wizard-step">
      <StepViolations violations={violations} />

      <label>
        Slug
        <input type="text" value={draft.slug} {...named("slug")}
               onChange={(e) => onEdit({ slug: e.target.value })} />
        <span className="field-hint">
          Lower-case letters, digits and hyphens. It addresses the instance in every URL and cannot
          be changed later.
        </span>
      </label>

      <label>
        Display name
        <input type="text" value={draft.displayName} {...named("displayName")}
               onChange={(e) => onEdit({ displayName: e.target.value })} />
      </label>

      <label>
        Purpose
        <textarea rows={2} value={draft.purpose} {...named("purpose")}
                  onChange={(e) => onEdit({ purpose: e.target.value })} />
        <span className="field-hint">What this instance decides. Read by whoever inherits it.</span>
      </label>

      <h3>Parsed data contract</h3>
      <dl className="parsed-facts">
        <div>
          <dt>Envelope version</dt>
          <dd>{options?.envelopeVersion
            ?? <span className="qualifier">Unavailable</span>}</dd>
        </div>
        <div>
          <dt>Canonicalization</dt>
          <dd>{options?.canonicalizationVersion
            ?? <span className="qualifier">Unavailable</span>}</dd>
        </div>
      </dl>
      <p className="field-hint">
        Fixed by the build. A release naming anything else would author cleanly and then refuse
        every parse it was given.
      </p>

      <fieldset className="type-picker">
        <legend>Allowed document types</legend>
        <ul className="chip-list">
          {draft.allowedDocumentTypes.map((code) => (
            <li key={code}>
              <span className="mono">{code}</span>
              <button type="button" className="link-button"
                      onClick={() => removeType("allowedDocumentTypes", code)}>
                Remove {code}
              </button>
            </li>
          ))}
        </ul>
        <label>
          Add an allowed type
          {/* The chip list is not focusable, so the refusal is carried by the control that
              resolves it rather than by the fieldset nobody tabs to. */}
          <input type="text" value={allowedEntry} {...named("allowedDocumentTypes")}
                 onChange={(e) => setAllowedEntry(e.target.value)} />
        </label>
        <button type="button" className="btn" onClick={() => {
          addType("allowedDocumentTypes", allowedEntry);
          setAllowedEntry("");
        }}>Add allowed type</button>
      </fieldset>

      <fieldset className="type-picker">
        <legend>Required document types</legend>
        <p className="field-hint">
          At least one of these must be present for a parse to be analyzed. Each must also be
          allowed, or no document could ever satisfy it.
        </p>
        <ul className="chip-list">
          {draft.requireAnyDocumentTypes.map((code) => (
            <li key={code}>
              <span className="mono">{code}</span>
              <button type="button" className="link-button"
                      onClick={() => removeType("requireAnyDocumentTypes", code)}>
                Remove required {code}
              </button>
            </li>
          ))}
        </ul>
        <label>
          Add a required type
          <input type="text" value={requiredEntry} {...named("requireAnyDocumentTypes")}
                 onChange={(e) => setRequiredEntry(e.target.value)} />
        </label>
        <button type="button" className="btn" onClick={() => {
          addType("requireAnyDocumentTypes", requiredEntry);
          setRequiredEntry("");
        }}>Add required type</button>
      </fieldset>

      <label>
        Minimum supported documents
        <input type="text" value={draft.minimumSupportedDocuments}
               {...named("minimumSupportedDocuments")}
               onChange={(e) => onEdit({ minimumSupportedDocuments: e.target.value })} />
      </label>

      <label>
        When a document needs review
        <select value={draft.reviewRequired}
                onChange={(e) => onEdit({ reviewRequired: e.target.value as "WARN" | "REJECT" })}>
          <option value="WARN">Warn and continue</option>
          <option value="REJECT">Refuse the run</option>
        </select>
      </label>

      <label>
        When a field is missing
        <select value={draft.missingFields}
                onChange={(e) => onEdit(
                  { missingFields: e.target.value as "PRESERVE" | "REJECT" })}>
          <option value="PRESERVE">Preserve the gap</option>
          <option value="REJECT">Refuse the run</option>
        </select>
        <span className="field-hint">
          Preserving a gap keeps it visible as a gap. Neither option invents a value.
        </span>
      </label>
    </div>
  );
}
