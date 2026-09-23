/**
 * The right pane: what was extracted, how sure the engine is, and — on a click
 * — where it came from.
 *
 * Three rules here are load-bearing rather than cosmetic.
 *
 * **A missing field is a result.** `ExtractedField`'s own javadoc says so, and
 * so does the export endpoint's. A field the engine looked for and did not find
 * gets a full row — or, inside a group, a full cell — that says it was not
 * found. Hiding it would turn "we checked and it is not there" into "we never
 * checked", which are different facts and only one of them is true.
 *
 * **A sensitive value is masked until asked for.** See `masking.ts` for the
 * long version, including why that is a usability control and not a security
 * one.
 *
 * **Identity is the occurrence row id, never the field name.** A field name
 * repeats within a document the moment a Schedule E is opened — one entry per
 * occurrence, `rentsReceived` three times over — so a React key, a selection or
 * a reveal keyed by name silently addresses whichever occurrence sorts first.
 * The id is on the wire, is unique, and is the same coordinate
 * `PATCH /v1/fields/{id}` uses.
 *
 * The three strata it renders — flat rows, cluster tables, region-not-read
 * banners — and the rule that separates them are `occurrences.ts`'s job; this
 * component only draws them.
 */

import { useState } from 'react';
import ConfidenceBadge from './Confidence.tsx';
import FieldDecisionControls, { type FieldDecision } from './FieldDecisionControls.tsx';
import GroupTable from './GroupTable.tsx';
import ProvenanceChip from './ProvenanceChip.tsx';
import StatusChip from './StatusChip.tsx';
import { isMissingField } from './fields.ts';
import { evidenceSummary, humaniseFieldName, normalisedDisplay } from './fieldFormatting.ts';
import { maskSensitiveValue } from './masking.ts';
import { layOutOccurrences, occurrenceLabel } from './occurrences.ts';
import type { DocumentFieldsView, FieldView, ReviewStatus, Uuid } from '../../lib/api/types.ts';

export type FieldPanelProps = {
  fields: DocumentFieldsView | null;
  /** The occurrence row id, not a field name. See the header. */
  selectedFieldId: Uuid | null;
  onSelectField: (fieldId: Uuid) => void;
  /** Labeling: when supplied, every occurrence gets Confirm / Correct / Reject. */
  onDecide?: (fieldId: Uuid, decision: FieldDecision) => Promise<void>;
  documentPageCount?: number;
  currentDocumentPageIndex?: number;
  onMarkReviewed?: () => Promise<void>;
  documentReviewStatus?: ReviewStatus;
};

/**
 * A grouped field whose region the engine never located.
 *
 * It gets a banner and not a row, because the statement it carries is about the
 * TABLE — "no readable region was found for this" — not about a value of the
 * field. Rendered as an ordinary field row it would read as "this field is
 * empty", which is a lie of category: a reviewer would go looking for a number
 * that was never missing, instead of for a table that was never read.
 */
function RegionNotReadBanner({ field }: { field: FieldView }) {
  return (
    <div
      data-testid="region-not-read"
      data-field-name={field.fieldName}
      data-group-kind={field.groupKind}
      data-field-id={field.id}
      role="status"
      className="border-b border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-900"
    >
      <p className="font-medium">
        <span aria-hidden="true">⚠</span>{' '}
        <span className="font-mono text-xs">{field.fieldName}</span> — table region could not be
        read
      </p>
      <p className="mt-1 text-xs">
        The engine located no readable {field.groupKind} group region for this field and recorded
        one explicitly-missing occurrence with no group key. Manual review required. This is a
        grouped field, not a document-level field.
      </p>
    </div>
  );
}

function FlatFieldRow({
  field,
  selected,
  revealed,
  onSelectField,
  onToggleReveal,
  onDecide,
  documentPageCount,
  currentDocumentPageIndex,
}: {
  field: FieldView;
  selected: boolean;
  revealed: boolean;
  onSelectField: (fieldId: Uuid) => void;
  onToggleReveal: (fieldId: Uuid) => void;
  onDecide?: (fieldId: Uuid, decision: FieldDecision) => Promise<void>;
  documentPageCount?: number;
  currentDocumentPageIndex?: number;
}) {
  const missing = isMissingField(field);
  const masked = field.sensitive && !revealed;
  const label = humaniseFieldName(field.fieldName);

  // Every displayable string is computed through the mask, once, here.
  // Nothing below may reach for `field.displayedText` / `rawValue` /
  // `normalized` again — that is exactly how a raw value leaks into a
  // title or an aria-label while the visible text looks masked.
  const rawNormalised = normalisedDisplay(field);
  const value = masked ? maskSensitiveValue(field.displayedText) : field.displayedText;
  const normalised = masked ? maskSensitiveValue(rawNormalised) : rawNormalised;

  return (
    <div
      data-testid="field-row"
      data-field-name={field.fieldName}
      data-occurrence={occurrenceLabel(field)}
      data-field-id={field.id}
      data-missing={missing ? 'true' : 'false'}
      data-sensitive={field.sensitive ? 'true' : 'false'}
      data-selected={selected ? 'true' : 'false'}
      className={selected ? 'bg-sky-50' : ''}
    >
      <button
        type="button"
        data-testid="field-select"
        aria-pressed={selected}
        onClick={() => { onSelectField(field.id); }}
        className="w-full px-4 py-3 text-left hover:bg-slate-50"
      >
        <span className="flex items-baseline gap-2">
          <span className="text-sm font-medium text-slate-900">{label}</span>
          <span className="font-mono text-[10px] text-slate-400">{field.fieldName}</span>
          <ConfidenceBadge confidence={field.confidence} className="ml-auto" />
        </span>

        {missing ? (
          <span
            data-testid="field-missing"
            className="mt-1 flex items-center gap-1.5 text-sm text-amber-800"
          >
            <span aria-hidden="true">⃠</span>
            Not found on this document
          </span>
        ) : (
          <span data-testid="field-value" className="mt-1 block text-sm break-words text-slate-800">
            {value ?? <span className="text-slate-400">(no text)</span>}
          </span>
        )}

        {normalised !== null && !missing ? (
          <span data-testid="field-normalised" className="mt-0.5 block text-xs text-slate-500">
            normalised: {normalised}
          </span>
        ) : null}

        <span className="mt-1.5 flex flex-wrap items-center gap-1.5">
          <StatusChip status={field.validationStatus} testId="validation-status" />
          <StatusChip status={field.reviewStatus} testId="review-status" />
          <span data-testid="extraction-method" className="text-[11px] text-slate-400">
            {field.extractionMethod}
          </span>
          {/* Beside the method, because it answers the same question one step
              further back: the method says HOW the value was located, this says
              whether its characters were read or recognised. Never beside the
              confidence badge — it is not a fourth component of that score. */}
          <ProvenanceChip provenance={field.textProvenance} />
        </span>

        <span data-testid="evidence-summary" className="mt-1 block text-[11px] text-slate-400">
          {evidenceSummary(field)}
        </span>
      </button>

      {onDecide ? (
        <FieldDecisionControls
          field={field}
          documentPageCount={documentPageCount ?? 1}
          currentDocumentPageIndex={currentDocumentPageIndex ?? 0}
          onDecide={onDecide}
        />
      ) : null}

      {field.sensitive ? (
        <div className="px-4 pb-3">
          <button
            type="button"
            data-testid="reveal-toggle"
            data-field-name={field.fieldName}
            data-field-id={field.id}
            aria-pressed={revealed}
            aria-label={`${revealed ? 'Hide' : 'Reveal'} ${label}`}
            onClick={() => { onToggleReveal(field.id); }}
            className="rounded-sm border border-slate-300 px-2 py-0.5 text-[11px] text-slate-600 hover:border-slate-400"
          >
            {revealed ? 'Hide' : 'Reveal'}
          </button>
          <span className="ml-2 text-[11px] text-slate-400">masked in this view only</span>
        </div>
      ) : null}
    </div>
  );
}

export default function FieldPanel({
  fields,
  selectedFieldId,
  onSelectField,
  onDecide,
  documentPageCount,
  currentDocumentPageIndex,
  onMarkReviewed,
  documentReviewStatus,
}: FieldPanelProps) {
  /**
   * Which sensitive occurrences the reviewer has deliberately unmasked.
   *
   * Keyed by the occurrence row id, which is the narrowest scope that still
   * makes "reveal once, read it" work. Keying it by field name — as this did
   * before occurrences existed — meant revealing one cell of a grouped
   * sensitive field revealed every sibling cell at once, and a masking control
   * that leaks across occurrences is not a control. Local and per-mount on
   * purpose; `PackageView` remounts this per document, so the set cannot cross
   * subjects either.
   */
  const [revealed, setRevealed] = useState<ReadonlySet<Uuid>>(new Set());

  const toggleReveal = (fieldId: Uuid) => {
    setRevealed((current) => {
      const next = new Set(current);
      if (!next.delete(fieldId)) next.add(fieldId);
      return next;
    });
  };

  if (!fields) {
    return (
      <div data-testid="field-panel" className="p-4 text-sm text-slate-500">
        Select a document to see its extracted fields.
      </div>
    );
  }

  const { ungrouped, clusters, regionsNotRead } = layOutOccurrences(fields.fields);

  return (
    <div data-testid="field-panel" className="flex h-full min-h-0 flex-col">
      <header className="shrink-0 border-b border-slate-200 px-4 py-3">
        <h2 className="text-sm font-semibold text-slate-900">{fields.documentTypeCode}</h2>
        <p className="text-xs text-slate-500">
          schema {fields.schemaVersion} · {String(fields.fields.length)} occurrences
          {clusters.length > 0 ? ` · ${String(clusters.length)} groups` : ''}
        </p>
        {onDecide ? (
          <div className="mt-2 flex flex-wrap gap-2">
            <button
              type="button"
              className="rounded border border-emerald-300 px-2 py-0.5 text-xs text-emerald-800"
              onClick={() => {
                const remaining = fields.fields.filter(
                  (field) => field.reviewStatus === 'NOT_REVIEWED' && !isMissingField(field),
                );
                void Promise.all(remaining.map((field) => onDecide(field.id, { action: 'CONFIRM' })));
              }}
            >
              Confirm all remaining
            </button>
            {onMarkReviewed ? (
              <button
                type="button"
                className="rounded border border-slate-400 bg-slate-800 px-2 py-0.5 text-xs text-white disabled:opacity-50"
                disabled={documentReviewStatus === 'REVIEWED'}
                onClick={() => { void onMarkReviewed(); }}
              >
                Mark reviewed
              </button>
            ) : null}
          </div>
        ) : null}
      </header>

      <div className="min-h-0 flex-1 overflow-y-auto">
        {ungrouped.length > 0 ? (
          <ul className="divide-y divide-slate-100 border-b border-slate-200">
            {ungrouped.map((field) => (
              <li key={field.id}>
                <FlatFieldRow
                  field={field}
                  selected={field.id === selectedFieldId}
                  revealed={revealed.has(field.id)}
                  onSelectField={onSelectField}
                  onToggleReveal={toggleReveal}
                  onDecide={onDecide}
                  documentPageCount={documentPageCount}
                  currentDocumentPageIndex={currentDocumentPageIndex}
                />
              </li>
            ))}
          </ul>
        ) : null}

        {clusters.map((cluster) => (
          <GroupTable
            key={cluster.id}
            cluster={cluster}
            selectedFieldId={selectedFieldId}
            onSelectField={onSelectField}
            revealed={revealed}
            onToggleReveal={toggleReveal}
            onDecide={onDecide}
            documentPageCount={documentPageCount}
            currentDocumentPageIndex={currentDocumentPageIndex}
          />
        ))}

        {regionsNotRead.map((field) => (
          <RegionNotReadBanner key={field.id} field={field} />
        ))}

        {fields.fields.length === 0 ? (
          <p className="p-4 text-sm text-slate-500">
            This document has no extraction schema, so no fields were looked for.
          </p>
        ) : null}
      </div>
    </div>
  );
}
