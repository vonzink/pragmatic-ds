/**
 * One repeating group, rendered the way the form prints it.
 *
 * **Orientation comes from `groupKind` and from nothing else.** A `COLUMN`
 * group's occurrences are the table's columns — Schedule E Part I's A/B/C money
 * grid reads across, exactly as a reviewer sees it on paper — and a `ROW`
 * group's occurrences are its rows. The tempting shortcut, inferring the
 * orientation from whether the cluster contains a STRING, was the rule before
 * the kind reached the wire; it turns an all-numeric ROW group of 99 rows into
 * a table 99 columns wide.
 *
 * **Every declared cell is drawn, including the empty ones.** A COLUMN group
 * and a labeled ROW group emit their full key set, so a cell with no value is
 * the engine saying "I looked at column C and read nothing" — a review
 * affordance. Drawing it blank would launder that into "nothing was there",
 * which is a different fact and not a true one. Missing cells are therefore
 * visible, labelled, and selectable like any other.
 */

import ConfidenceBadge from './Confidence.tsx';
import FieldDecisionControls, { type FieldDecision } from './FieldDecisionControls.tsx';
import OccurrenceDetail from './OccurrenceDetail.tsx';
import ProvenanceChip from './ProvenanceChip.tsx';
import { isMissingField } from './fields.ts';
import { maskSensitiveValue } from './masking.ts';
import { cellAt, memberLabel, occurrenceLabel, type OccurrenceCluster } from './occurrences.ts';
import type { FieldView, Uuid } from '../../lib/api/types.ts';

export type GroupTableProps = {
  cluster: OccurrenceCluster;
  selectedFieldId: Uuid | null;
  onSelectField: (fieldId: Uuid) => void;
  revealed: ReadonlySet<Uuid>;
  onToggleReveal: (fieldId: Uuid) => void;
  /** Labeling: when supplied, the selected occurrence gets Confirm / Correct / Reject. */
  onDecide?: (fieldId: Uuid, decision: FieldDecision) => Promise<void>;
  documentPageCount?: number;
  currentDocumentPageIndex?: number;
};

/**
 * A cell the read model did not carry at all — distinct from an occurrence the
 * engine recorded as missing, and rarer: a COLUMN or labeled ROW group always
 * emits its declared key set, so this can only mean the shape and the data
 * disagree. It says so rather than rendering an innocent blank.
 */
function AbsentCell({ fieldName, groupKey }: { fieldName: string; groupKey: string }) {
  return (
    <td
      data-testid="occurrence-cell"
      data-occurrence={`${fieldName}#${groupKey}`}
      data-field-name={fieldName}
      data-group-key={groupKey}
      data-missing="true"
      data-absent="true"
      data-selected="false"
      className="border border-slate-200 px-2 py-1 align-top text-xs text-amber-800"
    >
      no occurrence recorded
    </td>
  );
}

function OccurrenceCell({
  field,
  selected,
  masked,
  onSelectField,
}: {
  field: FieldView;
  selected: boolean;
  masked: boolean;
  onSelectField: (fieldId: Uuid) => void;
}) {
  const missing = isMissingField(field);
  const value = masked ? maskSensitiveValue(field.displayedText) : field.displayedText;

  return (
    <td
      data-testid="occurrence-cell"
      data-occurrence={occurrenceLabel(field)}
      data-field-name={field.fieldName}
      data-group-key={field.groupKey ?? ''}
      data-field-id={field.id}
      data-missing={missing ? 'true' : 'false'}
      data-sensitive={field.sensitive ? 'true' : 'false'}
      data-provenance={field.textProvenance.source}
      data-selected={selected ? 'true' : 'false'}
      className={`border border-slate-200 p-0 align-top ${selected ? 'bg-sky-100' : ''}`}
    >
      <button
        type="button"
        data-testid="occurrence-select"
        aria-pressed={selected}
        onClick={() => { onSelectField(field.id); }}
        className="block w-full px-2 py-1 text-left hover:bg-slate-50"
      >
        {missing ? (
          <span
            data-testid="occurrence-missing"
            className="flex items-center gap-1 text-xs text-amber-800"
          >
            <span aria-hidden="true">⃠</span> missing — review
          </span>
        ) : (
          <>
            <span className="block text-xs break-words text-slate-800">
              {value ?? <span className="text-slate-400">(no text)</span>}
            </span>
            {/* Per CELL, because provenance is per value: one column of a
                Schedule E can be a scanned insert while its neighbours are
                native, and a single verdict over the table would erase exactly
                that. A MISSING cell shows none — it cites no span, so there is
                nothing to have come from, and the Markdown cell is silent for
                the same reason. */}
            <span className="mt-0.5 flex flex-wrap items-center gap-1">
              <ConfidenceBadge confidence={field.confidence} />
              <ProvenanceChip provenance={field.textProvenance} />
            </span>
          </>
        )}
      </button>
    </td>
  );
}

export default function GroupTable({
  cluster,
  selectedFieldId,
  onSelectField,
  revealed,
  onToggleReveal,
  onDecide,
  documentPageCount,
  currentDocumentPageIndex,
}: GroupTableProps) {
  const occurrencesAreColumns = cluster.kind === 'COLUMN';

  const cellFor = (fieldName: string, groupKey: string) => {
    const field = cellAt(cluster, fieldName, groupKey);
    if (!field) return <AbsentCell key={groupKey + fieldName} fieldName={fieldName} groupKey={groupKey} />;
    return (
      <OccurrenceCell
        key={field.id}
        field={field}
        selected={field.id === selectedFieldId}
        masked={field.sensitive && !revealed.has(field.id)}
        onSelectField={onSelectField}
      />
    );
  };

  // The detail strip belongs to the cluster the selection is in, so a document
  // with five tables shows one strip, under the right one.
  const selected = selectedFieldId
    ? ([...cluster.cells.values()].find((field) => field.id === selectedFieldId) ?? null)
    : null;

  return (
    <section
      data-testid="group-cluster"
      data-cluster={`${cluster.label} ${cluster.keySummary}`}
      data-group-kind={cluster.kind}
      data-group-keys={cluster.keys.join(',')}
      data-orientation={occurrencesAreColumns ? 'columns' : 'rows'}
      className="border-b border-slate-200"
    >
      <header className="flex flex-wrap items-baseline gap-2 px-3 py-2">
        <h3 className="text-sm font-semibold text-slate-900">
          {cluster.label} {cluster.keySummary}
        </h3>
        <span className="rounded-sm bg-slate-100 px-1.5 py-0.5 font-mono text-[10px] text-slate-600">
          {cluster.kind}
        </span>
        <span data-testid="cluster-census" className="ml-auto text-[11px] text-slate-500">
          {cluster.found} found · {cluster.missing} missing
        </span>
      </header>

      <div className="overflow-x-auto px-3 pb-2">
        <table className="w-full border-collapse text-left text-xs">
          <thead>
            <tr>
              <th scope="col" className="border border-slate-200 bg-slate-50 px-2 py-1 font-medium">
                {occurrencesAreColumns ? 'Field' : 'Key'}
              </th>
              {occurrencesAreColumns
                ? cluster.keys.map((key) => (
                    <th
                      key={key}
                      scope="col"
                      data-group-key={key}
                      className="border border-slate-200 bg-slate-50 px-2 py-1 font-mono font-medium"
                    >
                      {key}
                    </th>
                  ))
                : cluster.fieldNames.map((fieldName) => (
                    <th
                      key={fieldName}
                      scope="col"
                      data-field-name={fieldName}
                      title={fieldName}
                      className="border border-slate-200 bg-slate-50 px-2 py-1 font-medium"
                    >
                      {memberLabel(cluster, fieldName)}
                    </th>
                  ))}
            </tr>
          </thead>
          <tbody>
            {occurrencesAreColumns
              ? cluster.fieldNames.map((fieldName) => (
                  <tr key={fieldName}>
                    <th
                      scope="row"
                      data-field-name={fieldName}
                      className="border border-slate-200 bg-slate-50/60 px-2 py-1 font-medium"
                    >
                      <span className="block">{memberLabel(cluster, fieldName)}</span>
                      <span className="block font-mono text-[10px] font-normal text-slate-400">
                        {fieldName}
                      </span>
                    </th>
                    {cluster.keys.map((key) => cellFor(fieldName, key))}
                  </tr>
                ))
              : cluster.keys.map((key) => (
                  <tr key={key}>
                    <th
                      scope="row"
                      data-group-key={key}
                      className="border border-slate-200 bg-slate-50/60 px-2 py-1 font-mono font-medium"
                    >
                      {key}
                    </th>
                    {cluster.fieldNames.map((fieldName) => cellFor(fieldName, key))}
                  </tr>
                ))}
          </tbody>
        </table>
      </div>

      {selected ? (
        <>
          <OccurrenceDetail
            field={selected}
            revealed={revealed.has(selected.id)}
            onToggleReveal={onToggleReveal}
          />
          {onDecide ? (
            <FieldDecisionControls
              key={selected.id}
              field={selected}
              documentPageCount={documentPageCount ?? 1}
              currentDocumentPageIndex={currentDocumentPageIndex ?? 0}
              onDecide={onDecide}
            />
          ) : null}
        </>
      ) : null}
    </section>
  );
}
