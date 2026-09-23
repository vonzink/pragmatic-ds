/**
 * How 67 occurrences become five tables and a banner.
 *
 * A Schedule E returns **one entry per occurrence** — the same field name three
 * or four times over, distinguished only by `groupKey`. Rendered flat that is a
 * wall of repeated labels in which a reviewer cannot tell property A from
 * property B; rendered as the form's own tables it is a page they already know
 * how to read. This module is the arithmetic of that second rendering, kept out
 * of JSX so it can be argued with and tested on its own.
 *
 * **Three strata, and the rule that separates them is a truth table, not a null
 * check.** `groupKey === null` means two different things depending on
 * `groupKind`, and conflating them is precisely the bug `groupKind` was put on
 * the wire to kill:
 *
 * | `groupKind` | `groupKey` | stratum |
 * |---|---|---|
 * | `NONE` | always null | {@link OccurrenceLayout.ungrouped} — a flat row |
 * | `COLUMN`/`ROW` | a key | a cell of its cluster's table |
 * | `COLUMN`/`ROW` | **null** | {@link OccurrenceLayout.regionsNotRead} — the region was never read |
 *
 * That third row is a statement about the TABLE, not a value of the field.
 * Rendering it as an ungrouped field would tell a reviewer "this field is
 * empty" where the truth is "this table was never read" — a lie of category,
 * and the one T9 of Spec 5a stood up to warn about.
 *
 * **The partition matches the server's Markdown deliberately.** Same rule, same
 * order, same orientation as `MarkdownDocumentRenderer` — because the two
 * surfaces project one read model and a reviewer who reads both must not have
 * to reconcile them. Cluster by `(groupKind, exact ordered key sequence)`;
 * order clusters by first member field name; members and keys code-point
 * ascending.
 *
 * **Orientation comes from `groupKind` and nothing else.** Never from
 * `dataType`: a data-type proxy ("all-numeric reads across") turns an
 * all-numeric ROW group of 99 rows into 99 columns.
 */

import { isMissingField } from './fields.ts';
import type { FieldView, GroupKind } from '../../lib/api/types.ts';

/** One rendered table: a kind, an ordered key set, and the fields that repeat over it. */
export type OccurrenceCluster = {
  /** Stable across renders and unique within a document — a React key and a DOM handle. */
  id: string;
  /** `COLUMN` → occurrences are the table's columns; `ROW` → its rows. */
  kind: GroupKind;
  /** Code-point ascending, which is what the zero-padding on ordinal keys is for. */
  keys: readonly string[];
  /** Code-point ascending. */
  fieldNames: readonly string[];
  /** The humanised words every member shares — "Partnership" — or "Group" when they share none. */
  label: string;
  /** How many leading camel-case words {@link label} consumed. See {@link memberLabel}. */
  sharedWordCount: number;
  /** `A–D (4)`, or `01 (1)` for a single-key group. */
  keySummary: string;
  /** Every occurrence in the cluster, keyed by {@link cellKey}. */
  cells: ReadonlyMap<string, FieldView>;
  found: number;
  missing: number;
};

export type OccurrenceLayout = {
  /** `groupKind: 'NONE'` — fields that do not repeat, in read-model order. */
  ungrouped: readonly FieldView[];
  clusters: readonly OccurrenceCluster[];
  /** Grouped fields whose region was never read. Never a row of either of the above. */
  regionsNotRead: readonly FieldView[];
};

/**
 * The occurrence's coordinate, as a human reads it: `rentsReceived#B`, or the
 * bare name when there is no key.
 *
 * This is what `data-occurrence` carries, so a test — or a human in devtools —
 * can tell B's boxes from A's. It is deliberately NOT the selection identity:
 * two occurrences of a null-keyed field would share this string, and the row id
 * never does.
 */
export function occurrenceLabel(field: FieldView): string {
  return field.groupKey === null ? field.fieldName : `${field.fieldName}#${field.groupKey}`;
}

/** The key a cluster stores an occurrence under. */
export function cellKey(fieldName: string, groupKey: string): string {
  return `${fieldName}${groupKey}`;
}

/** The occurrence at a coordinate, or null — which a table renders as an explicit missing cell. */
export function cellAt(
  cluster: OccurrenceCluster,
  fieldName: string,
  groupKey: string,
): FieldView | null {
  return cluster.cells.get(cellKey(fieldName, groupKey)) ?? null;
}

/** Code-point comparison. `localeCompare` would sort by locale; every ordering here is lexical. */
function byCodePoint(left: string, right: string): number {
  return left < right ? -1 : left > right ? 1 : 0;
}

/** `partnershipNonpassiveIncome` -> `["partnership", "Nonpassive", "Income"]`. */
function camelWords(fieldName: string): string[] {
  return fieldName.split(/(?<=[a-z0-9])(?=[A-Z])/);
}

/**
 * The humanised leading words every member shares — derived, never authored.
 *
 * A per-form title table would be per-form knowledge this module deliberately
 * does not carry, and would be wrong for every document type without an entry.
 * The reviewer has the page image beside this anyway.
 */
function sharedWords(fieldNames: readonly string[]): string[] {
  const wordLists = fieldNames.map(camelWords);
  let shared = [...wordLists[0]];
  for (const words of wordLists) {
    while (
      shared.length > 0 &&
      (words.length < shared.length || words.slice(0, shared.length).join('') !== shared.join(''))
    ) {
      shared = shared.slice(0, -1);
    }
  }
  return shared;
}

function titleCase(words: readonly string[]): string {
  return words.map((word) => word.charAt(0).toUpperCase() + word.slice(1)).join(' ');
}

/**
 * What a member field is called *inside* its cluster: the part of its name the
 * cluster's own heading has not already said.
 *
 * `partnershipEin` under a "Partnership" heading is "Ein", which is the
 * difference between a readable seven-column table and a header row of
 * near-identical words. The full wire name never disappears — it rides on
 * `data-field-name` and is spelled out in the detail strip. A member whose
 * whole name IS the shared prefix (a one-member cluster) keeps its full name
 * rather than being left nameless.
 */
export function memberLabel(cluster: OccurrenceCluster, fieldName: string): string {
  const words = camelWords(fieldName);
  const remainder = words.slice(cluster.sharedWordCount);
  return titleCase(remainder.length === 0 ? words : remainder);
}

function keySummary(keys: readonly string[]): string {
  if (keys.length === 1) return `${keys[0]} (1)`;
  return `${keys[0]}–${keys[keys.length - 1]} (${String(keys.length)})`;
}

/**
 * A grouped field whose only coordinate is no coordinate at all.
 *
 * The engine writes exactly one such occurrence when it cannot locate the table
 * region — `groupKind` still naming the shape, `groupKey` null, method `NONE`.
 * On the wire that is byte-identical to an ungrouped missing field, which is
 * why the kind, not the key, is the test.
 */
function isRegionNotRead(field: FieldView): boolean {
  return field.groupKind !== 'NONE' && field.groupKey === null;
}

/**
 * Sorts a document's occurrences into the three strata and builds the cluster
 * tables.
 *
 * The input is expected in read-model order (field name ascending, then key
 * ascending with nulls first) and is re-sorted here anyway: the partition's
 * determinism is this module's guarantee, not a property borrowed from the
 * caller's fetch.
 */
export function layOutOccurrences(fields: readonly FieldView[]): OccurrenceLayout {
  const ungrouped: FieldView[] = [];
  const regionsNotRead: FieldView[] = [];
  /** fieldName -> its keyed occurrences. */
  const keyed = new Map<string, FieldView[]>();

  for (const field of fields) {
    // The order of these two tests is the truth table in the header. Asking
    // "is the key null?" first would sweep the never-read regions in with the
    // fields that simply do not repeat — the exact conflation `groupKind` was
    // put on the wire to make impossible.
    if (isRegionNotRead(field)) {
      regionsNotRead.push(field);
      continue;
    }
    if (field.groupKind === 'NONE') {
      ungrouped.push(field);
      continue;
    }
    if (field.groupKey === null) {
      // Unreachable given the matrix above; kept so a future kind cannot land
      // an unkeyed occurrence in a table by falling through.
      regionsNotRead.push(field);
      continue;
    }
    const existing = keyed.get(field.fieldName);
    if (existing) existing.push(field);
    else keyed.set(field.fieldName, [field]);
  }

  type Accumulator = { kind: GroupKind; keys: string[]; members: Map<string, FieldView[]> };
  const byPartition = new Map<string, Accumulator>();

  for (const fieldName of [...keyed.keys()].sort(byCodePoint)) {
    const occurrences = keyed.get(fieldName) ?? [];
    const keys = occurrences
      .map((occurrence) => occurrence.groupKey)
      .filter((key): key is string => key !== null)
      .sort(byCodePoint);
    const kind = occurrences[0].groupKind;
    // (kind, exact ordered key sequence). The separator is load-bearing:
    // joining the keys bare would let `["AB"]` and `["A","B"]` collide into one
    // table, which no current alphabet produces and no future one should be
    // allowed to.
    const partition = `${kind}${keys.join('')}`;
    const accumulator = byPartition.get(partition) ?? { kind, keys, members: new Map() };
    accumulator.members.set(fieldName, occurrences);
    byPartition.set(partition, accumulator);
  }

  const clusters: OccurrenceCluster[] = [...byPartition.values()].map((accumulator) => {
    const fieldNames = [...accumulator.members.keys()].sort(byCodePoint);
    const shared = sharedWords(fieldNames);
    const cells = new Map<string, FieldView>();
    let found = 0;
    let missing = 0;
    for (const fieldName of fieldNames) {
      for (const occurrence of accumulator.members.get(fieldName) ?? []) {
        if (occurrence.groupKey === null) continue;
        cells.set(cellKey(fieldName, occurrence.groupKey), occurrence);
        if (isMissingField(occurrence)) missing += 1;
        else found += 1;
      }
    }
    return {
      id: `${String(accumulator.kind)}${accumulator.keys.join('')}`,
      kind: accumulator.kind,
      keys: accumulator.keys,
      fieldNames,
      label: shared.length === 0 ? 'Group' : titleCase(shared),
      sharedWordCount: shared.length,
      keySummary: keySummary(accumulator.keys),
      cells,
      found,
      missing,
    };
  });

  // By first member field name — the members are code-point sorted, so "first"
  // is code-point least and the whole ordering is total.
  clusters.sort((left, right) => byCodePoint(left.fieldNames[0], right.fieldNames[0]));

  return { ungrouped, clusters, regionsNotRead };
}
