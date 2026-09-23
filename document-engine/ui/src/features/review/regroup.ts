/**
 * The seam between a select-and-act UI gesture and the regroup wire contract.
 *
 * Each builder turns a page selection plus a chosen action into a
 * {@link RegroupRequest} — the exact body `POST /v1/packages/{id}/regroup`
 * expects (design §4.1). They are deliberately pure: no React, no client, no
 * network. That is what lets `regroup.test.ts` pin the wire shape without a
 * render or a mock, and what keeps the component that calls them free to worry
 * only about *which* pages and *which* action.
 *
 * `intent` is audit metadata the server stores verbatim, not behaviour — it
 * computes the resulting grouping from `moves`/`newDocuments` alone. So each
 * builder's real job is the shape: which pages move where, and which are born
 * into a fresh document.
 */

import type { NewDocument, RegroupRequest, Uuid } from '../../lib/api/types.ts';

/**
 * One tempId is enough: every builder that creates a document creates exactly
 * one, so a single stable handle wires its pages to it. The server swaps this
 * for the real id it assigns (design §4.1).
 */
const NEW_DOCUMENT_TEMP_ID = 'n1';

function newDocument(pageIds: readonly Uuid[], documentTypeCode: string): NewDocument {
  return { tempId: NEW_DOCUMENT_TEMP_ID, documentTypeCode, pageIds: [...pageIds] };
}

/** Move the selected pages into an existing document. */
export function buildMoveDelta(
  pageIds: readonly Uuid[],
  toDocumentId: string,
): RegroupRequest {
  return {
    intent: 'MOVE_PAGES',
    moves: pageIds.map((pageId) => ({ pageId, toDocumentId })),
    newDocuments: [],
    deletedDocumentIds: [],
  };
}

/** Detach the selected pages from whatever document holds them. */
export function buildUnassignDelta(pageIds: readonly Uuid[]): RegroupRequest {
  return {
    intent: 'UNASSIGN',
    moves: pageIds.map((pageId) => ({ pageId, toDocumentId: null })),
    newDocuments: [],
    deletedDocumentIds: [],
  };
}

/** Promote the selected pages into a brand-new document of the chosen type. */
export function buildNewDocumentDelta(
  pageIds: readonly Uuid[],
  documentTypeCode: string,
): RegroupRequest {
  return {
    intent: 'NEW_DOCUMENT',
    moves: [],
    newDocuments: [newDocument(pageIds, documentTypeCode)],
    deletedDocumentIds: [],
  };
}

/**
 * Split the selected pages off into a new document, inheriting a type from the
 * document they came from. Structurally identical to a new-document promotion —
 * a new document from selected pages — but tagged `SPLIT` so the audit records
 * the reviewer's meaning: "these belonged together, now they don't."
 */
export function buildSplitDelta(
  pageIds: readonly Uuid[],
  documentTypeCode: string,
): RegroupRequest {
  return {
    intent: 'SPLIT',
    moves: [],
    newDocuments: [newDocument(pageIds, documentTypeCode)],
    deletedDocumentIds: [],
  };
}
