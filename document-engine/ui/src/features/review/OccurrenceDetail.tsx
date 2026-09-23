/**
 * Everything known about ONE occurrence, under the table it was clicked in.
 *
 * Under the table rather than inside every cell, and that is the whole design
 * of it: a Schedule E has 67 occurrences, and 67 rows of method chips,
 * validation chips and evidence sentences is exactly what made the flat list
 * unreadable in the first place. The table answers "what are the numbers"; this
 * answers "and how sure are we about this one", for the one the reviewer asked
 * about.
 *
 * The three confidence components are **visible text**, not a tooltip. The
 * mandate is that a reviewer can audit the score, and a number reachable only
 * by hovering is not auditable on a screenshot, on a touch screen, or by
 * anybody using a keyboard.
 */

import ConfidenceBadge from './Confidence.tsx';
import ProvenanceChip from './ProvenanceChip.tsx';
import StatusChip from './StatusChip.tsx';
import { isMissingField } from './fields.ts';
import {
  confidenceComponentsText,
  evidenceSummary,
  humaniseFieldName,
  normalisedDisplay,
  scale4,
} from './fieldFormatting.ts';
import { maskSensitiveValue } from './masking.ts';
import { occurrenceLabel } from './occurrences.ts';
import type { FieldView, Uuid } from '../../lib/api/types.ts';

export type OccurrenceDetailProps = {
  field: FieldView;
  revealed: boolean;
  onToggleReveal: (fieldId: Uuid) => void;
};

export default function OccurrenceDetail({
  field,
  revealed,
  onToggleReveal,
}: OccurrenceDetailProps) {
  const missing = isMissingField(field);
  const masked = field.sensitive && !revealed;
  const label = humaniseFieldName(field.fieldName);

  // Every displayable string is computed through the mask, once, here. Nothing
  // below may reach for `displayedText` / `rawValue` / `normalized` again —
  // that is exactly how a raw value leaks into a title or an aria-label while
  // the visible text looks masked.
  const value = masked ? maskSensitiveValue(field.displayedText) : field.displayedText;
  const normalised = masked
    ? maskSensitiveValue(normalisedDisplay(field))
    : normalisedDisplay(field);
  const components = confidenceComponentsText(field.confidenceComponents);

  return (
    <div
      data-testid="occurrence-detail"
      data-occurrence={occurrenceLabel(field)}
      data-field-id={field.id}
      data-missing={missing ? 'true' : 'false'}
      className="border-t border-slate-200 bg-sky-50/60 px-3 py-2"
    >
      <p className="flex flex-wrap items-baseline gap-2">
        <span className="text-sm font-medium text-slate-900">{label}</span>
        <span className="font-mono text-[10px] text-slate-500">{occurrenceLabel(field)}</span>
        {field.groupKey !== null ? (
          <span
            data-testid="occurrence-key"
            className="rounded-sm bg-slate-200 px-1.5 py-0.5 font-mono text-[11px] text-slate-700"
          >
            {field.groupKey}
          </span>
        ) : null}
        <ConfidenceBadge confidence={field.confidence} className="ml-auto" />
      </p>

      {missing ? (
        <p data-testid="occurrence-detail-missing" className="mt-1 text-sm text-amber-800">
          <span aria-hidden="true">⃠</span> Not found on this document — the engine looked at this
          coordinate and read nothing.
        </p>
      ) : (
        <p data-testid="occurrence-detail-value" className="mt-1 text-sm break-words text-slate-800">
          {value ?? <span className="text-slate-400">(no text)</span>}
        </p>
      )}

      {normalised !== null && !missing ? (
        <p data-testid="occurrence-detail-normalised" className="mt-0.5 text-xs text-slate-500">
          normalised: {normalised}
        </p>
      ) : null}

      <p className="mt-1 text-xs text-slate-600">
        confidence <span className="font-mono tabular-nums">{scale4(field.confidence)}</span>
        {' = '}
        <span data-testid="confidence-components" className="font-mono tabular-nums">
          {components ?? '—'}
        </span>
        <span className="text-slate-400"> (span · anchor · normalizer)</span>
      </p>

      <p className="mt-1.5 flex flex-wrap items-center gap-1.5">
        <StatusChip status={field.validationStatus} testId="validation-status" />
        <StatusChip status={field.reviewStatus} testId="review-status" />
        <span
          data-testid="extraction-method"
          className="rounded-sm bg-slate-100 px-1.5 py-0.5 font-mono text-[11px] text-slate-600"
        >
          {field.extractionMethod}
        </span>
        {/* Always shown, including UNKNOWN on a missing occurrence — the detail
            strip is the audit surface, and "no badge" would have to mean both
            "native" and "this build predates the field". */}
        <ProvenanceChip provenance={field.textProvenance} />
      </p>

      <p data-testid="evidence-summary" className="mt-1 text-[11px] text-slate-500">
        {evidenceSummary(field)}
      </p>

      {field.sensitive ? (
        <p className="mt-1.5">
          <button
            type="button"
            data-testid="reveal-toggle"
            data-field-id={field.id}
            aria-pressed={revealed}
            aria-label={`${revealed ? 'Hide' : 'Reveal'} ${label}`}
            onClick={() => { onToggleReveal(field.id); }}
            className="rounded-sm border border-slate-300 px-2 py-0.5 text-[11px] text-slate-600 hover:border-slate-400"
          >
            {revealed ? 'Hide' : 'Reveal'}
          </button>
          <span className="ml-2 text-[11px] text-slate-400">
            reveals the server-masked string only
          </span>
        </p>
      ) : null}
    </div>
  );
}
