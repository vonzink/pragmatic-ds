import { describe, expect, it } from 'vitest';
import { cellAt, layOutOccurrences, occurrenceLabel } from './occurrences.ts';
import { DOCUMENT_FIELDS, SCHEDULE_E_FIELDS } from '../../test/fixtures.ts';
import type { FieldView } from '../../lib/api/types.ts';

/**
 * The partition is the whole of Spec 5b's legibility claim: a Schedule E must
 * read as a handful of property tables rather than 67 flat rows, and it must
 * read as the SAME handful the server's Markdown renders, or the two surfaces
 * are telling one reviewer two stories.
 *
 * So these assertions are pinned against
 * `app/src/test/resources/extraction/markdown/schedule_e.md` — five clusters,
 * in that order, with those members — not against whatever this module happens
 * to produce.
 */

const SCHEDULE_E = SCHEDULE_E_FIELDS.fields;

function occurrence(fieldName: string, groupKey: string | null): FieldView {
  const found = SCHEDULE_E.find(
    (field) => field.fieldName === fieldName && field.groupKey === groupKey,
  );
  if (!found) throw new Error(`fixture has no ${fieldName}#${groupKey ?? '∅'}`);
  return found;
}

describe('the fixture itself', () => {
  it('carries the 67 occurrences the engine records for this document', () => {
    expect(SCHEDULE_E).toHaveLength(67);
    expect(SCHEDULE_E.filter((field) => field.extractionMethod === 'NONE')).toHaveLength(23);
  });

  it('is in read-model order: field name ascending, then key ascending with null first', () => {
    const sorted = [...SCHEDULE_E].sort((left, right) => {
      if (left.fieldName !== right.fieldName) return left.fieldName < right.fieldName ? -1 : 1;
      if (left.groupKey === right.groupKey) return 0;
      if (left.groupKey === null) return -1;
      if (right.groupKey === null) return 1;
      return left.groupKey < right.groupKey ? -1 : 1;
    });
    expect(SCHEDULE_E.map(occurrenceLabel)).toEqual(sorted.map(occurrenceLabel));
  });
});

describe('layOutOccurrences — the five Schedule E clusters', () => {
  const layout = layOutOccurrences(SCHEDULE_E);

  it('partitions the document into exactly five clusters', () => {
    expect(layout.clusters).toHaveLength(5);
  });

  it('orders clusters by first member field name, and gives each its kind and keys', () => {
    expect(
      layout.clusters.map((cluster) => ({
        kind: cluster.kind,
        keys: [...cluster.keys],
        fields: cluster.fieldNames.length,
      })),
    ).toEqual([
      { kind: 'COLUMN', keys: ['A', 'B', 'C'], fields: 5 },
      { kind: 'ROW', keys: ['A', 'B'], fields: 5 },
      { kind: 'ROW', keys: ['A', 'B', 'C', 'D'], fields: 7 },
      { kind: 'ROW', keys: ['01', '02', '03'], fields: 1 },
      { kind: 'ROW', keys: ['01'], fields: 2 },
    ]);
  });

  it('puts the five Part I money lines in the COLUMN cluster, code-point ascending', () => {
    expect(layout.clusters[0].fieldNames).toEqual([
      'depreciationExpense',
      'incomeOrLoss',
      'mortgageInterest',
      'rentsReceived',
      'totalExpenses',
    ]);
  });

  it('puts the seven Part II fields in the A–D ROW cluster', () => {
    expect(layout.clusters[2].fieldNames).toEqual([
      'partnershipEin',
      'partnershipName',
      'partnershipNonpassiveIncome',
      'partnershipNonpassiveLossAllowed',
      'partnershipPassiveIncome',
      'partnershipPassiveLossAllowed',
      'partnershipSection179Expense',
    ]);
  });

  it('names each cluster from the words its members share, and summarises its keys', () => {
    expect(layout.clusters.map((cluster) => `${cluster.label} ${cluster.keySummary}`)).toEqual([
      'Group A–C (3)',
      'Estate Or Trust A–B (2)',
      'Partnership A–D (4)',
      'Property Address 01–03 (3)',
      'Remic 01 (1)',
    ]);
  });

  it('accounts for every occurrence exactly once, across all three strata', () => {
    const cells = layout.clusters.flatMap((cluster) => [...cluster.cells.values()]);
    const placed = [...layout.ungrouped, ...cells, ...layout.regionsNotRead];

    expect(placed).toHaveLength(67);
    expect(new Set(placed.map((field) => field.id)).size).toBe(67);
  });
});

describe('layOutOccurrences — missing occurrences stay visible', () => {
  const layout = layOutOccurrences(SCHEDULE_E);

  it('keeps Part I column C as a cell of its cluster, not as an absence', () => {
    const columnCluster = layout.clusters[0];
    for (const fieldName of columnCluster.fieldNames) {
      const cell = cellAt(columnCluster, fieldName, 'C');
      expect(cell, `${fieldName}#C`).not.toBeNull();
      expect(cell?.extractionMethod).toBe('NONE');
    }
  });

  it('counts found and missing occurrences per cluster for the header', () => {
    expect(layout.clusters.map((cluster) => [cluster.found, cluster.missing])).toEqual([
      [10, 5],
      [10, 0],
      [12, 16],
      [2, 1],
      [2, 0],
    ]);
  });
});

describe('layOutOccurrences — the null-keyed grouped field', () => {
  const layout = layOutOccurrences(SCHEDULE_E);

  it('isolates it as a region-not-read, keeping its kind', () => {
    expect(layout.regionsNotRead.map((field) => field.fieldName)).toEqual(['remicExcessInclusion']);
    expect(layout.regionsNotRead[0].groupKind).toBe('ROW');
    expect(layout.regionsNotRead[0].groupKey).toBeNull();
  });

  it('never lets it reach the ungrouped list', () => {
    expect(layout.ungrouped.map((field) => field.fieldName)).not.toContain('remicExcessInclusion');
    // The eight the schema actually leaves ungrouped, and only those.
    expect(layout.ungrouped.map((field) => field.fieldName)).toEqual([
      'estateAndTrustTotal',
      'partnershipAndSCorpTotal',
      'remicTotal',
      'taxYear',
      'taxpayerName',
      'taxpayerSsn',
      'totalIncomeOrLoss',
      'totalRentalRealEstateIncomeOrLoss',
    ]);
  });

  it('never lets it reach a cluster table', () => {
    for (const cluster of layout.clusters) {
      expect(cluster.fieldNames).not.toContain('remicExcessInclusion');
      expect([...cluster.cells.values()].map((field) => field.fieldName)).not.toContain(
        'remicExcessInclusion',
      );
    }
  });
});

/**
 * The partition rule is `(groupKind, exact ordered key sequence)`. Both halves
 * earn their place, and these are the cases that prove it — each one passes
 * under a plausible weaker rule and fails the real contract.
 */
describe('layOutOccurrences — what the partition rule is actually made of', () => {
  it('does not merge two ROW groups that share a kind but not a key set', () => {
    // Partitioning by kind alone would collapse Parts II, III, IV and the
    // address list into ONE table of 15 fields over keys A,B,C,D,01,02,03 —
    // three unrelated forms' worth of data in one grid.
    const rowClusters = layOutOccurrences(SCHEDULE_E).clusters.filter(
      (cluster) => cluster.kind === 'ROW',
    );

    expect(rowClusters).toHaveLength(4);
    expect(rowClusters.map((cluster) => cluster.keys.join(','))).toEqual([
      'A,B',
      'A,B,C,D',
      '01,02,03',
      '01',
    ]);
  });

  it('does not merge a COLUMN group and a ROW group that share a key set', () => {
    // Synthetic and minimal on purpose: no shipped schema yet prints a COLUMN
    // group and a ROW group over the same alphabet, so the KIND half of the
    // partition rule is invisible on real data until one does — and a
    // clustering that quietly dropped it would keep passing every assertion
    // above. Two occurrences each, same keys, different kinds.
    const template = occurrence('rentsReceived', 'A');
    const cell = (fieldName: string, groupKind: 'COLUMN' | 'ROW', groupKey: string): FieldView => ({
      ...template,
      id: `${fieldName}-${groupKey}`,
      fieldName,
      groupKind,
      groupKey,
    });
    const fields = [
      cell('columnar', 'COLUMN', 'A'),
      cell('columnar', 'COLUMN', 'B'),
      cell('rowwise', 'ROW', 'A'),
      cell('rowwise', 'ROW', 'B'),
    ];

    const split = layOutOccurrences(fields).clusters;
    expect(split).toHaveLength(2);
    expect(split.map((cluster) => cluster.kind)).toEqual(['COLUMN', 'ROW']);

    // The same four occurrences under ONE kind are one table — which is
    // exactly the merge the kind is there to prevent above.
    const merged = fields.map((field) => ({ ...field, groupKind: 'ROW' as const }));
    expect(layOutOccurrences(merged).clusters).toHaveLength(1);
    expect(layOutOccurrences(merged).clusters[0].fieldNames).toEqual(['columnar', 'rowwise']);
  });
});

describe('layOutOccurrences — a document with no repeating fields', () => {
  it('leaves the flat paystub entirely flat', () => {
    const layout = layOutOccurrences(DOCUMENT_FIELDS.fields);

    expect(layout.clusters).toHaveLength(0);
    expect(layout.regionsNotRead).toHaveLength(0);
    expect(layout.ungrouped).toHaveLength(DOCUMENT_FIELDS.fields.length);
  });
});

describe('occurrenceLabel', () => {
  it('names a grouped occurrence by its coordinate and an ungrouped one by its name', () => {
    expect(occurrenceLabel(occurrence('rentsReceived', 'B'))).toBe('rentsReceived#B');
    expect(occurrenceLabel(occurrence('taxYear', null))).toBe('taxYear');
  });

  it('names the region-not-read occurrence without pretending it has a key', () => {
    expect(occurrenceLabel(occurrence('remicExcessInclusion', null))).toBe('remicExcessInclusion');
  });
});
