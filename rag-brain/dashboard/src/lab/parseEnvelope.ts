import { LabBox, LabEnvelopeResponse, LabField, LabPage } from "../types";

/**
 * Projects one verified engine envelope onto what the Parse Output panel renders.
 *
 * <p>Three decisions are worth stating out loud, because each is a place where a friendlier
 * rendering would be a dishonest one.
 *
 * <p><b>1. Decimals never become numbers.</b> {@link fieldValue} returns the wire STRING. The
 * backend already sends normalized financial values as canonical decimal strings precisely so the
 * browser cannot re-read them as IEEE-754 doubles; the moment this file called `Number()`,
 * `parseFloat()`, or `toFixed()` on one, that guarantee would be gone. Confidence and box
 * coordinates are numbers here because they are ratios and geometry, not money.
 *
 * <p><b>2. A missing field is rendered, never dropped.</b> The engine states absence explicitly
 * (`status: "MISSING"`, `method: "NONE"`, `confidence: 0`). A blank cell would read as "we did not
 * look"; "missing" reads as "we looked and it was not there", which is what the envelope says.
 *
 * <p><b>3. A null group key is not guessed.</b> The canonical envelope at engine tag `spec5a`
 * carries `groupKey` present-and-null but has NO grouping-kind member — that was added to the
 * engine's own read models, not to the envelope. So for a MISSING field a null key is genuinely
 * ambiguous: it could be a grouped field whose region was unreadable, or a field that was never
 * grouped at all. {@link groupLabel} answers "unknown" in that case and
 * {@link EnvelopeView#groupingAmbiguous} lets the screen say so, rather than picking a side the
 * data does not support. For a FOUND field the ambiguity does not arise: the engine read the
 * occurrence and assigned it no group, so "ungrouped" is a statement, not an inference.
 */

/** Which member of the field supplied the displayed text. */
export type ValueKind =
  | "NORMALIZED_NUMBER"
  | "NORMALIZED_DATE"
  | "NORMALIZED_TEXT"
  | "DISPLAYED"
  | "RAW"
  | "MISSING"
  | "REDACTED"
  | "REJECTED"
  | "ABSENT";

export interface FieldValue {
  text: string;
  kind: ValueKind;
}

/** How much the envelope actually tells us about this occurrence's grouping. */
export type GroupCertainty = "KEYED" | "UNGROUPED" | "UNKNOWN";

export interface GroupIdentity {
  label: string;
  certainty: GroupCertainty;
}

export interface EvidenceView {
  pageLabel: string;
  role: string;
  ordinal: number;
  box: LabBox | null;
}

export interface FieldRow {
  field: LabField;
  value: FieldValue;
  evidence: EvidenceView[];
}

export interface GroupView {
  key: string | null;
  label: string;
  certainty: GroupCertainty;
  rows: FieldRow[];
}

export interface DocumentView {
  id: string;
  documentTypeCode: string | null;
  ordinal: number;
  pageLabels: string[];
  groups: GroupView[];
  fieldCount: number;
  foundCount: number;
  missingCount: number;
}

export interface EnvelopeView {
  documents: DocumentView[];
  unassignedPageLabels: string[];
  pageCount: number;
  fieldCount: number;
  foundCount: number;
  missingCount: number;
  /** True when at least one MISSING field has no group key, so grouping cannot be stated. */
  groupingAmbiguous: boolean;
}

/**
 * The text to display for one occurrence, and which member it came from.
 *
 * Preference order is most-normalized first, because that is the value the analyzer consumed.
 * Every arm returns a string taken verbatim off the wire.
 */
export function fieldValue(field: LabField): FieldValue {
  if (field.reviewState === "REJECTED") {
    return { text: "rejected by reviewer", kind: "REJECTED" };
  }
  if (field.status === "MISSING") return { text: "missing", kind: "MISSING" };
  // The server already drops every value arm of a sensitive field; this is the belt to that
  // brace, so an older server or a stray arm can never paint an SSN or account number.
  if (field.sensitive) return { text: "redacted (sensitive)", kind: "REDACTED" };
  if (field.normalizedNumber !== null) {
    return { text: field.normalizedNumber, kind: "NORMALIZED_NUMBER" };
  }
  if (field.normalizedDate !== null) return { text: field.normalizedDate, kind: "NORMALIZED_DATE" };
  if (field.normalizedText !== null) return { text: field.normalizedText, kind: "NORMALIZED_TEXT" };
  if (field.displayedText !== null) return { text: field.displayedText, kind: "DISPLAYED" };
  if (field.rawValue !== null) return { text: field.rawValue, kind: "RAW" };
  return { text: "no value", kind: "ABSENT" };
}

/** The occurrence's group, or an honest admission that the envelope does not say. */
export function groupLabel(field: LabField): GroupIdentity {
  if (field.groupKey !== null) return { label: field.groupKey, certainty: "KEYED" };
  if (field.status === "FOUND") return { label: "ungrouped", certainty: "UNGROUPED" };
  return { label: "unknown", certainty: "UNKNOWN" };
}

/**
 * A page's human label. `packagePageIndex` is zero-based on the wire, so page one is index zero.
 * A page id the envelope did not describe is named by its identifier prefix and nothing more.
 */
export function pageLabel(pageId: string, pages: LabPage[]): string {
  const page = pages.find((candidate) => candidate.id === pageId);
  return page ? `p. ${page.packagePageIndex + 1}` : `page ${pageId.slice(0, 8)}`;
}

export function buildEnvelopeView(envelope: LabEnvelopeResponse): EnvelopeView {
  const pages = envelope.pages;
  let fieldCount = 0;
  let foundCount = 0;
  let groupingAmbiguous = false;

  const documents = envelope.documents.map((document) => {
    // Insertion-ordered: groups appear in the order the envelope first mentions them, and rows
    // keep the envelope's own field order. Neither is re-sorted — the engine's array order is
    // semantic, and reordering it here would be inventing a presentation the parse did not have.
    const groups = new Map<string, GroupView>();
    let documentFound = 0;

    for (const field of document.fields) {
      const identity = groupLabel(field);
      if (identity.certainty === "UNKNOWN") groupingAmbiguous = true;
      const bucket = groups.get(identity.label) ?? {
        key: field.groupKey,
        label: identity.label,
        certainty: identity.certainty,
        rows: [],
      };
      bucket.rows.push({
        field,
        value: fieldValue(field),
        evidence: field.evidence.map((span) => ({
          pageLabel: pageLabel(span.pageId, pages),
          role: span.role,
          ordinal: span.ordinal,
          box: span.box,
        })),
      });
      groups.set(identity.label, bucket);
      if (field.status === "FOUND") documentFound += 1;
    }

    fieldCount += document.fields.length;
    foundCount += documentFound;
    return {
      id: document.id,
      documentTypeCode: document.documentTypeCode,
      ordinal: document.ordinal,
      pageLabels: document.pageIds.map((pageId) => pageLabel(pageId, pages)),
      groups: [...groups.values()],
      fieldCount: document.fields.length,
      foundCount: documentFound,
      missingCount: document.fields.length - documentFound,
    };
  });

  return {
    documents,
    unassignedPageLabels: envelope.unassignedPageIds.map((pageId) => pageLabel(pageId, pages)),
    pageCount: pages.length,
    fieldCount,
    foundCount,
    missingCount: fieldCount - foundCount,
    groupingAmbiguous,
  };
}
