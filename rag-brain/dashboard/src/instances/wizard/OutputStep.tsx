import { StepViolations, fieldErrorProps } from "./StepProps";
import type { StepProps } from "./StepProps";

/**
 * The shape the answer must take.
 *
 * A schema is pinned by id *and* by the digest of the bytes this build ships, and both travel
 * together from the server. That pairing is the whole point: the id alone would let a release keep
 * validating against a schema that had been rewritten underneath it, and a digest the dashboard
 * remembered would stop matching the first time the file changed, with a mismatch naming neither
 * the file nor the field.
 *
 * So this is a select over what the server just reported, and the digest is never typed.
 */
export default function OutputStep(
  { draft, onEdit, violations, options, stepId, issues }: StepProps,
) {
  const schemas = options?.outputSchemas ?? [];

  return (
    <div className="wizard-step">
      <StepViolations violations={violations} />

      <label>
        Output schema
        <select
          {...fieldErrorProps(stepId, issues, "schemaId")}
          value={draft.schemaId}
          onChange={(e) => {
            const picked = schemas.find((s) => s.schemaId === e.target.value) ?? null;
            // The digest travels with the id. Selecting one without the other would author a
            // contract the server refuses as incomplete.
            onEdit({ schemaId: picked?.schemaId ?? "", schemaSha256: picked?.sha256 ?? "" });
          }}
        >
          <option value="">Choose an output schema</option>
          {schemas.map((schema) => (
            <option key={schema.schemaId} value={schema.schemaId}>{schema.schemaId}</option>
          ))}
        </select>
      </label>

      {schemas.length === 0 && (
        <p className="muted">
          This build ships no allowlisted output schemas, so no instance can be created. A schema is
          added server-side and deliberately cannot be supplied from here.
        </p>
      )}

      {draft.schemaSha256 && (
        <dl className="parsed-facts">
          <div>
            <dt>Pinned digest</dt>
            <dd className="mono" title={draft.schemaSha256}>
              {draft.schemaSha256.slice(0, 12)}
            </dd>
          </div>
        </dl>
      )}

      <p className="field-hint">
        The release pins this digest. If the schema changes server-side afterwards, runs of this
        release are refused rather than answered against a schema it was never evaluated with.
      </p>
    </div>
  );
}
