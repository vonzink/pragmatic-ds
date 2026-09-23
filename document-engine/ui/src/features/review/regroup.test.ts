import { describe, expect, it } from 'vitest';
import {
  buildMoveDelta,
  buildNewDocumentDelta,
  buildSplitDelta,
  buildUnassignDelta,
} from './regroup.ts';

/*
 * The delta builders are the whole seam between a UI selection and the regroup
 * wire contract (design §4.1): a Set of page ids plus a chosen action becomes a
 * `RegroupRequest`. Keeping them pure — no React, no client — is what lets this
 * test assert the exact body the server will receive without a render or a mock.
 *
 * `intent` is audit metadata the server records verbatim, not behaviour
 * (`RegroupService.safeIntent`), so each builder's job is the SHAPE: which pages
 * move where, and which are born into a new document.
 */

describe('regroup delta builders', () => {
  it('move builds one Move per page to the target document', () => {
    const delta = buildMoveDelta(['p1', 'p2'], 'docB');
    expect(delta.intent).toBe('MOVE_PAGES');
    expect(delta.moves).toEqual([
      { pageId: 'p1', toDocumentId: 'docB' },
      { pageId: 'p2', toDocumentId: 'docB' },
    ]);
    expect(delta.newDocuments).toEqual([]);
    expect(delta.deletedDocumentIds).toEqual([]);
  });

  it('unassign sends every selected page to no document (toDocumentId null)', () => {
    const delta = buildUnassignDelta(['p1', 'p2']);
    expect(delta.intent).toBe('UNASSIGN');
    expect(delta.moves).toEqual([
      { pageId: 'p1', toDocumentId: null },
      { pageId: 'p2', toDocumentId: null },
    ]);
    expect(delta.newDocuments).toEqual([]);
  });

  it('new document carries the type and the selected pages under one tempId', () => {
    const delta = buildNewDocumentDelta(['p3'], 'PAYSTUB');
    expect(delta.intent).toBe('NEW_DOCUMENT');
    expect(delta.newDocuments).toEqual([
      { tempId: 'n1', documentTypeCode: 'PAYSTUB', pageIds: ['p3'] },
    ]);
    expect(delta.moves).toEqual([]);
  });

  it('split creates a new document from the selected pages, inheriting a type', () => {
    const delta = buildSplitDelta(['p4', 'p5'], 'BANK_STATEMENT');
    expect(delta.intent).toBe('SPLIT');
    expect(delta.newDocuments).toEqual([
      { tempId: 'n1', documentTypeCode: 'BANK_STATEMENT', pageIds: ['p4', 'p5'] },
    ]);
    expect(delta.moves).toEqual([]);
  });
});
