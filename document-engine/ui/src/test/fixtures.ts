/**
 * API-shaped test data, transcribed from `fixtures/truth/paystub_twopage.json`.
 *
 * The boxes are the generator's ground truth, not numbers invented to make an
 * assertion pass — which matters because a coordinate test built on made-up
 * geometry proves only that the code is self-consistent. Same reasoning as
 * `coordinates.test.ts`, which draws on the same fixture family.
 *
 * `paystub_twopage` specifically, because it is the fixture where `payFrequency`
 * sits on page 2 while everything else is on page 1 — the per-page attribution
 * case the overlay has to get right.
 *
 * **No sensitive field is defined here.** No shipped extraction field is
 * `sensitive: true`, and adding one to a shared fixture would invite someone to
 * "fix" the schema to match. The masking tests build their own synthetic field
 * inline, where its fictional nature is unmissable.
 */

import type {
  DocumentFieldsView,
  EvidenceView,
  FieldView,
  GroupKind,
  JobResponse,
  PackageDocumentsView,
  PackageUsageView,
  PageView,
  Uuid,
} from '../lib/api/types.ts';

export const PACKAGE_ID = '11111111-1111-4111-8111-111111111111';
export const JOB_ID = '22222222-2222-4222-8222-222222222222';
export const DOCUMENT_ID = '33333333-3333-4333-8333-333333333333';
export const FILE_ID = '44444444-4444-4444-8444-444444444444';
export const PAGE_1_ID = '55555555-5555-4555-8555-555555555551';
export const PAGE_2_ID = '55555555-5555-4555-8555-555555555552';

/** US Letter, the size every generated fixture page is. */
export const PAGE_WIDTH_PT = 612;
export const PAGE_HEIGHT_PT = 792;

function page(pageId: string, index: number, overrides: Partial<PageView> = {}): PageView {
  return {
    pageId,
    sourceFileId: FILE_ID,
    pageIndex: index,
    packagePageIndex: index,
    widthPt: PAGE_WIDTH_PT,
    heightPt: PAGE_HEIGHT_PT,
    rotation: 0,
    detectedRotation: 0,
    renderDpi: 200,
    hasRender: true,
    textLayer: 'NATIVE',
    blank: false,
    duplicateOfPageId: null,
    sourceContentType: 'application/pdf',
    ...overrides,
  };
}

export const PAGES: PageView[] = [page(PAGE_1_ID, 0), page(PAGE_2_ID, 1)];

export const IMAGE_FILE_ID = '44444444-4444-4444-8444-444444444445';
export const IMAGE_PAGE_ID = '55555555-5555-4555-8555-555555555553';

/**
 * A page from a photographed document: one JPEG, one page, no text layer. Its 1224x1584 px
 * become the same 612x792 pt canonical box a letter PDF page has (the worker's nominal-DPI
 * rule), which is exactly why the overlay maths needs no special case for it.
 */
export const IMAGE_PAGE: PageView = page(IMAGE_PAGE_ID, 0, {
  sourceFileId: IMAGE_FILE_ID,
  sourceContentType: 'image/jpeg',
  renderDpi: 144,
  textLayer: 'SCANNED',
});

function evidence(
  role: 'VALUE' | 'LABEL',
  ordinal: number,
  pageIndex: number,
  box: { x: number; y: number; width: number; height: number },
): EvidenceView {
  return {
    role,
    ordinal,
    pageId: pageIndex === 0 ? PAGE_1_ID : PAGE_2_ID,
    packagePageIndex: pageIndex,
    ...box,
    textSpanId: null,
    layoutElementId: null,
  };
}

/**
 * Occurrence row ids.
 *
 * Fixed rather than generated: the id is the selection identity the panel and
 * the overlay agree on, so a test that asserts "B's boxes, not A's" needs to be
 * able to name B.
 */
const FIELD_ID_PREFIX = '66666666-6666-4666-8666-';

function fieldId(ordinal: number): Uuid {
  return `${FIELD_ID_PREFIX}${String(ordinal).padStart(12, '0')}`;
}

/** `borrowerName`: three VALUE words and one LABEL word, all on page 1. */
export const BORROWER_NAME: FieldView = {
  id: fieldId(1),
  fieldName: 'borrowerName',
  groupKey: null,
  groupKind: 'NONE',
  textProvenance: { source: 'NATIVE', ocrEngine: null },
  dataType: 'STRING',
  displayedText: 'Jordan Q. Fixture',
  rawValue: 'Jordan Q. Fixture',
  normalized: { text: 'Jordan Q. Fixture', number: null, date: null },
  extractionMethod: 'ANCHOR_LABEL',
  extractorVersion: '1.0.0',
  confidence: 0.94,
  confidenceComponents: { spanConfidence: 0.99, anchorStrength: 0.95, normalizerCertainty: 1 },
  validationStatus: 'NOT_VALIDATED',
  reviewStatus: 'NOT_REVIEWED',
  sensitive: false,
  evidence: [
    evidence('VALUE', 0, 0, { x: 130.0, y: 94.1, width: 33.6, height: 10.2 }),
    evidence('VALUE', 1, 0, { x: 169.6, y: 94.1, width: 11.6, height: 10.2 }),
    evidence('VALUE', 2, 0, { x: 187.2, y: 94.1, width: 33.6, height: 10.2 }),
    evidence('LABEL', 0, 0, { x: 72.0, y: 94.1, width: 52.0, height: 10.2 }),
  ],
};

/** `netPay`: one VALUE word, two LABEL words, page 1. */
export const NET_PAY: FieldView = {
  id: fieldId(2),
  fieldName: 'netPay',
  groupKey: null,
  groupKind: 'NONE',
  textProvenance: { source: 'NATIVE', ocrEngine: null },
  dataType: 'MONEY',
  displayedText: '$3,105.87',
  rawValue: '$3,105.87',
  normalized: { text: null, number: 3105.87, date: null },
  extractionMethod: 'ANCHOR_LABEL',
  extractorVersion: '1.0.0',
  confidence: 0.91,
  confidenceComponents: { spanConfidence: 0.98, anchorStrength: 0.93, normalizerCertainty: 1 },
  validationStatus: 'VALID',
  reviewStatus: 'NOT_REVIEWED',
  sensitive: false,
  evidence: [
    evidence('VALUE', 0, 0, { x: 124.7, y: 313.4, width: 53.4, height: 11.1 }),
    evidence('LABEL', 0, 0, { x: 72.0, y: 313.4, width: 19.3, height: 11.1 }),
    evidence('LABEL', 1, 0, { x: 97.3, y: 313.4, width: 21.3, height: 11.1 }),
  ],
};

/** `payFrequency`: the whole point of this fixture — its evidence is on PAGE 2. */
export const PAY_FREQUENCY: FieldView = {
  id: fieldId(3),
  fieldName: 'payFrequency',
  groupKey: null,
  groupKind: 'NONE',
  // The MIXED case, on purpose: this occurrence's evidence sits on page 2, part
  // in the text layer and part recognised from a scanned insert. A page-level
  // verdict could not express it and a majority rule would erase it.
  textProvenance: { source: 'MIXED', ocrEngine: 'RAPIDOCR' },
  dataType: 'ENUM',
  displayedText: 'Bi-Weekly',
  rawValue: 'Bi-Weekly',
  normalized: { text: 'BIWEEKLY', number: null, date: null },
  extractionMethod: 'ANCHOR_LABEL',
  extractorVersion: '1.0.0',
  confidence: 0.88,
  confidenceComponents: { spanConfidence: 0.97, anchorStrength: 0.9, normalizerCertainty: 1 },
  validationStatus: 'VALID',
  reviewStatus: 'NOT_REVIEWED',
  sensitive: false,
  evidence: [
    evidence('VALUE', 0, 1, { x: 158.0, y: 130.1, width: 49.5, height: 10.2 }),
    evidence('LABEL', 0, 1, { x: 72.0, y: 130.1, width: 19.0, height: 10.2 }),
    evidence('LABEL', 1, 1, { x: 97.0, y: 130.1, width: 55.0, height: 10.2 }),
  ],
};

/** `employerName`: found by REGEX, so there is no LABEL evidence at all. */
export const EMPLOYER_NAME: FieldView = {
  id: fieldId(4),
  fieldName: 'employerName',
  groupKey: null,
  groupKind: 'NONE',
  /** Wholly recognised — the case the badge exists to make impossible to miss. */
  textProvenance: { source: 'OCR', ocrEngine: 'RAPIDOCR' },
  dataType: 'STRING',
  displayedText: 'ACME WIDGETS LLC',
  rawValue: 'ACME WIDGETS LLC',
  normalized: { text: 'ACME WIDGETS LLC', number: null, date: null },
  extractionMethod: 'REGEX',
  extractorVersion: '1.0.0',
  confidence: 0.62,
  confidenceComponents: { spanConfidence: 0.99, anchorStrength: 0.4, normalizerCertainty: 1 },
  validationStatus: 'NOT_VALIDATED',
  reviewStatus: 'NOT_REVIEWED',
  sensitive: false,
  evidence: [
    evidence('VALUE', 0, 0, { x: 72.0, y: 61.9, width: 41.2, height: 13.0 }),
    evidence('VALUE', 1, 0, { x: 119.2, y: 61.9, width: 65.3, height: 13.0 }),
    evidence('VALUE', 2, 0, { x: 190.6, y: 61.9, width: 27.2, height: 13.0 }),
  ],
};

/**
 * A field the engine looked for and did not find. Exactly the shape
 * `FieldExtractionService` writes: method NONE, confidence 0, no components,
 * no evidence, MANUAL_REVIEW_REQUIRED.
 */
export const MISSING_FIELD: FieldView = {
  id: fieldId(5),
  fieldName: 'ytdGrossPay',
  groupKey: null,
  groupKind: 'NONE',
  /** No evidence at all, so no span, so nothing to have come from. */
  textProvenance: { source: 'UNKNOWN', ocrEngine: null },
  dataType: 'MONEY',
  displayedText: null,
  rawValue: null,
  normalized: null,
  extractionMethod: 'NONE',
  extractorVersion: null,
  confidence: 0,
  confidenceComponents: null,
  validationStatus: 'MANUAL_REVIEW_REQUIRED',
  reviewStatus: 'NOT_REVIEWED',
  sensitive: false,
  evidence: [],
};

export const DOCUMENT_FIELDS: DocumentFieldsView = {
  documentId: DOCUMENT_ID,
  documentTypeCode: 'PAYSTUB',
  schemaVersion: '1.0.0',
  fields: [BORROWER_NAME, EMPLOYER_NAME, MISSING_FIELD, NET_PAY, PAY_FREQUENCY],
};

// ---------------------------------------------------------------------------
// SCHEDULE_E — the repeating-group fixture
// ---------------------------------------------------------------------------

/**
 * The two-page synthetic Schedule E, as `GET /v1/documents/{id}/fields` serves
 * it: **67 occurrences across 29 schema fields** — 44 FOUND, 23 MISSING.
 *
 * Transcribed, not invented. Every box, method and displayed text below comes
 * from `fixtures/truth/schedule_e.json`; every confidence and its three
 * components come from the server's committed Markdown golden
 * (`app/src/test/resources/extraction/markdown/schedule_e.md`), which is
 * rendered from this same read model. That matters twice over: a coordinate
 * test built on made-up geometry proves only self-consistency, and a UI whose
 * clustering is asserted against invented keys proves nothing about the wire.
 *
 * The occurrences this fixture exists for:
 *
 * - `incomeOrLoss#B` is `( 18,470 )` — **three** VALUE spans, because the two
 *   parenthesis glyphs are their own words. Selecting it must light all three.
 * - `remicExcessInclusion` is `groupKind: 'ROW'` with a **null** `groupKey`:
 *   the region was never read. It is not an ungrouped field and must never
 *   render as one.
 * - Part I column `C`, `propertyAddress` `03` and sixteen Part II cells are
 *   MISSING occurrences that a labeled group still emits — visible, not absent.
 *
 * `taxpayerSsn` is the one sensitive field, and its value here is the string
 * the **server** sends: already masked. The raw digits never reach a browser,
 * so they are not in this file either.
 */

const SCHEDULE_E_DOCUMENT_ID = '77777777-7777-4777-8777-777777777777';

type Box = readonly [x: number, y: number, width: number, height: number];

type Truth = {
  /** 0-based package page index. */
  p: 0 | 1;
  m: string;
  c: number;
  /** span · anchor · normalizer, in that order. */
  n: readonly [number, number, number];
  /** Displayed text, verbatim from the page. */
  t: string;
  /** The normalised value, in whichever arm the data type populates. */
  z: string | number;
  v: readonly Box[];
  l: readonly Box[];
};

/** Keyed `fieldName#groupKey`, with an empty key for an ungrouped field. */
const SCHEDULE_E_TRUTH: Record<string, Truth> = {
  'taxpayerName#': { p: 0, m: 'LABEL_BELOW', c: 0.9, n: [1, 0.9, 1], t: 'Jordan Q. Fixture and Casey Reese Fixture', z: 'Jordan Q. Fixture and Casey Reese Fixture', v: [[36, 106.8, 30.6, 9.3], [72.6, 106.8, 10.6, 9.3], [89.1, 106.8, 30.6, 9.3], [125.7, 106.8, 16.7, 9.3], [148.4, 106.8, 28.3, 9.3], [182.7, 106.8, 28.9, 9.3], [217.6, 106.8, 30.6, 9.3]], l: [[36, 96.3, 30.7, 7.4], [72.7, 96.3, 23.1, 7.4], [101.8, 96.3, 8.9, 7.4], [116.7, 96.3, 20.9, 7.4]] },
  'taxpayerSsn#': { p: 0, m: 'LABEL_BELOW', c: 0.9, n: [1, 0.9, 1], t: '•••-••-4321', z: '•••-••-4321', v: [[430, 106.8, 56.7, 9.3]], l: [[430, 96.3, 16.9, 7.4], [452.9, 96.3, 20.4, 7.4], [479.3, 96.3, 27.6, 7.4], [512.9, 96.3, 27.1, 7.4]] },
  'taxYear#': { p: 0, m: 'ANCHOR_LABEL', c: 0.9, n: [1, 0.9, 1], t: '2025', z: '2025', v: [[560, 54.8, 22.2, 9.3]], l: [[36, 55.5, 24, 8.3], [66, 55.5, 23, 8.3]] },
  'propertyAddress#01': { p: 0, m: 'ROW_CELL', c: 0.9, n: [1, 0.9, 1], t: '1234 SYNTHETIC AVE, DENVER, CO 80202', z: '1234 SYNTHETIC AVE, DENVER, CO 80202', v: [[60, 160.3, 162.3, 7.4]], l: [[50.9, 148.3, 29.8, 7.4], [86.7, 148.3, 28.5, 7.4]] },
  'propertyAddress#02': { p: 0, m: 'ROW_CELL', c: 0.9, n: [1, 0.9, 1], t: '5678 SAMPLE ST, AURORA, CO 80014', z: '5678 SAMPLE ST, AURORA, CO 80014', v: [[60, 172.3, 144.5, 7.4]], l: [[50.9, 148.3, 29.8, 7.4], [86.7, 148.3, 28.5, 7.4]] },
  'rentsReceived#A': { p: 0, m: 'ANCHOR_LABEL', c: 0.81, n: [1, 0.9, 0.9], t: '44,400', z: 44400, v: [[386, 281.3, 24.5, 7.4]], l: [[46.4, 281.3, 20.9, 7.4], [73.3, 281.3, 30.2, 7.4]] },
  'rentsReceived#B': { p: 0, m: 'ANCHOR_LABEL', c: 0.81, n: [1, 0.9, 0.9], t: '29,700', z: 29700, v: [[456, 281.3, 24.5, 7.4]], l: [[46.4, 281.3, 20.9, 7.4], [73.3, 281.3, 30.2, 7.4]] },
  'mortgageInterest#A': { p: 0, m: 'ANCHOR_LABEL', c: 0.81, n: [1, 0.9, 0.9], t: '13,260', z: 13260, v: [[386, 323.3, 24.5, 7.4]], l: [[50.9, 323.3, 33.8, 7.4], [90.7, 323.3, 26.2, 7.4]] },
  'mortgageInterest#B': { p: 0, m: 'ANCHOR_LABEL', c: 0.81, n: [1, 0.9, 0.9], t: '10,415', z: 10415, v: [[456, 323.3, 24.5, 7.4]], l: [[50.9, 323.3, 33.8, 7.4], [90.7, 323.3, 26.2, 7.4]] },
  'depreciationExpense#A': { p: 0, m: 'ANCHOR_LABEL', c: 0.81, n: [1, 0.9, 0.9], t: '8,900', z: 8900, v: [[386, 337.3, 20, 7.4]], l: [[50.9, 337.3, 44.9, 7.4]] },
  'depreciationExpense#B': { p: 0, m: 'ANCHOR_LABEL', c: 0.81, n: [1, 0.9, 0.9], t: '7,150', z: 7150, v: [[456, 337.3, 20, 7.4]], l: [[50.9, 337.3, 44.9, 7.4]] },
  'totalExpenses#A': { p: 0, m: 'ANCHOR_LABEL', c: 0.81, n: [1, 0.9, 0.9], t: '31,540', z: 31540, v: [[386, 351.3, 24.5, 7.4]], l: [[74.7, 351.3, 36.5, 7.4]] },
  'totalExpenses#B': { p: 0, m: 'ANCHOR_LABEL', c: 0.81, n: [1, 0.9, 0.9], t: '48,170', z: 48170, v: [[456, 351.3, 24.5, 7.4]], l: [[74.7, 351.3, 36.5, 7.4]] },
  'incomeOrLoss#A': { p: 0, m: 'ANCHOR_LABEL', c: 0.81, n: [1, 0.9, 0.9], t: '12,860', z: 12860, v: [[386, 365.3, 24.5, 7.4]], l: [[50.9, 365.3, 29.8, 7.4]] },
  'incomeOrLoss#B': { p: 0, m: 'ANCHOR_LABEL', c: 0.81, n: [1, 0.9, 0.9], t: '( 18,470 )', z: -18470, v: [[456, 365.3, 2.7, 7.4], [464.7, 365.3, 24.5, 7.4], [495.1, 365.3, 2.7, 7.4]], l: [[50.9, 365.3, 29.8, 7.4]] },
  'totalRentalRealEstateIncomeOrLoss#': { p: 0, m: 'ANCHOR_LABEL', c: 0.81, n: [1, 0.9, 0.9], t: '(5,610)', z: -5610, v: [[470, 416.3, 25.3, 7.4]], l: [[241.6, 416.3, 21.8, 7.4]] },
  'partnershipName#A': { p: 1, m: 'ROW_CELL', c: 0.9, n: [1, 0.9, 1], t: 'SUMMIT RIDGE PARTNERS LP', z: 'SUMMIT RIDGE PARTNERS LP', v: [[48, 151, 102.3, 6.5]], l: [[48, 132, 6.1, 4.6], [58.6, 132, 13.3, 4.6]] },
  'partnershipName#B': { p: 1, m: 'ROW_CELL', c: 0.9, n: [1, 0.9, 1], t: '1ST CHOICE PROPERTIES LLC', z: '1ST CHOICE PROPERTIES LLC', v: [[48, 163, 103.9, 6.5]], l: [[48, 132, 6.1, 4.6], [58.6, 132, 13.3, 4.6]] },
  'partnershipEin#A': { p: 1, m: 'ROW_CELL', c: 0.9, n: [1, 0.9, 1], t: '27-1234567', z: '27-1234567', v: [[160, 151, 37.4, 6.5]], l: [[160, 130.2, 6.1, 4.6], [170.6, 130.2, 21.1, 4.6]] },
  'partnershipEin#B': { p: 1, m: 'ROW_CELL', c: 0.9, n: [1, 0.9, 1], t: '84-7654321', z: '84-7654321', v: [[160, 163, 37.4, 6.5]], l: [[160, 130.2, 6.1, 4.6], [170.6, 130.2, 21.1, 4.6]] },
  'partnershipPassiveLossAllowed#A': { p: 1, m: 'ROW_CELL', c: 0.81, n: [1, 0.9, 0.9], t: '2,400', z: 2400, v: [[206, 219, 17.5, 6.5]], l: [[206, 200.4, 6.1, 4.6]] },
  'partnershipPassiveLossAllowed#B': { p: 1, m: 'ROW_CELL', c: 0.81, n: [1, 0.9, 0.9], t: '3,800', z: 3800, v: [[206, 231, 17.5, 6.5]], l: [[206, 200.4, 6.1, 4.6]] },
  'partnershipPassiveIncome#A': { p: 1, m: 'ROW_CELL', c: 0.81, n: [1, 0.9, 0.9], t: '4,200', z: 4200, v: [[277, 219, 17.5, 6.5]], l: [[277, 200.4, 6.1, 4.6]] },
  'partnershipNonpassiveLossAllowed#B': { p: 1, m: 'ROW_CELL', c: 0.81, n: [1, 0.9, 0.9], t: '($6,310)', z: -6310, v: [[334, 231, 26.1, 6.5]], l: [[334, 200.4, 4.4, 4.6]] },
  'partnershipSection179Expense#A': { p: 1, m: 'ROW_CELL', c: 0.81, n: [1, 0.9, 0.9], t: '1,150', z: 1150, v: [[412, 219, 17.5, 6.5]], l: [[412, 200.4, 4.4, 4.6], [420.9, 200.4, 16.7, 4.6]] },
  'partnershipSection179Expense#B': { p: 1, m: 'ROW_CELL', c: 0.9, n: [1, 0.9, 1], t: '950.00', z: 950, v: [[412, 231, 21.4, 6.5]], l: [[412, 200.4, 4.4, 4.6], [420.9, 200.4, 16.7, 4.6]] },
  'partnershipNonpassiveIncome#A': { p: 1, m: 'ROW_CELL', c: 0.81, n: [1, 0.9, 0.9], t: '42,150', z: 42150, v: [[482, 219, 21.4, 6.5]], l: [[482, 200.4, 5.8, 4.6]] },
  'partnershipNonpassiveIncome#B': { p: 1, m: 'ROW_CELL', c: 0.81, n: [1, 0.9, 0.9], t: '18,725', z: 18725, v: [[482, 231, 21.4, 6.5]], l: [[482, 200.4, 5.8, 4.6]] },
  'partnershipAndSCorpTotal#': { p: 1, m: 'ANCHOR_LABEL', c: 0.81, n: [1, 0.9, 0.9], t: '50,465', z: 50465, v: [[505, 293, 21.4, 6.5]], l: [[140.7, 293, 35, 6.5]] },
  'estateOrTrustName#A': { p: 1, m: 'ROW_CELL', c: 0.9, n: [1, 0.9, 1], t: "O'BRIEN FAMILY TRUST", z: "O'BRIEN FAMILY TRUST", v: [[48, 351, 80.7, 6.5]], l: [[48, 332.4, 6.1, 4.6], [58.6, 332.4, 13.3, 4.6]] },
  'estateOrTrustName#B': { p: 1, m: 'ROW_CELL', c: 0.9, n: [1, 0.9, 1], t: 'Meridian Fixture Estate', z: 'Meridian Fixture Estate', v: [[48, 363, 72, 6.5]], l: [[48, 332.4, 6.1, 4.6], [58.6, 332.4, 13.3, 4.6]] },
  'estateOrTrustPassiveDeductionOrLoss#A': { p: 1, m: 'ROW_CELL', c: 0.81, n: [1, 0.9, 0.9], t: '3,150', z: 3150, v: [[137, 395, 17.5, 6.5]], l: [[137, 376.4, 5.8, 4.6], [169.3, 376.4, 21.7, 4.6]] },
  'estateOrTrustPassiveDeductionOrLoss#B': { p: 1, m: 'ROW_CELL', c: 0.81, n: [1, 0.9, 0.9], t: '1,100', z: 1100, v: [[137, 407, 17.5, 6.5]], l: [[137, 376.4, 5.8, 4.6], [169.3, 376.4, 21.7, 4.6]] },
  'estateOrTrustPassiveIncome#A': { p: 1, m: 'ROW_CELL', c: 0.81, n: [1, 0.9, 0.9], t: '5,400', z: 5400, v: [[243, 395, 17.5, 6.5]], l: [[243, 376.4, 6.1, 4.6]] },
  'estateOrTrustPassiveIncome#B': { p: 1, m: 'ROW_CELL', c: 0.81, n: [1, 0.9, 0.9], t: '2,250', z: 2250, v: [[243, 407, 17.5, 6.5]], l: [[243, 376.4, 6.1, 4.6]] },
  'estateOrTrustDeductionOrLoss#A': { p: 1, m: 'ROW_CELL', c: 0.81, n: [1, 0.9, 0.9], t: '2,100', z: 2100, v: [[300, 395, 17.5, 6.5]], l: [[300, 376.4, 6.1, 4.6], [310.6, 376.4, 22.5, 4.6]] },
  'estateOrTrustDeductionOrLoss#B': { p: 1, m: 'ROW_CELL', c: 0.81, n: [1, 0.9, 0.9], t: '1,450', z: 1450, v: [[300, 407, 17.5, 6.5]], l: [[300, 376.4, 6.1, 4.6], [310.6, 376.4, 22.5, 4.6]] },
  'estateOrTrustOtherIncome#A': { p: 1, m: 'ROW_CELL', c: 0.81, n: [1, 0.9, 0.9], t: '9,850', z: 9850, v: [[364, 395, 17.5, 6.5]], l: [[364, 376.4, 4.7, 4.6], [373.2, 376.4, 12.5, 4.6]] },
  'estateOrTrustOtherIncome#B': { p: 1, m: 'ROW_CELL', c: 0.81, n: [1, 0.9, 0.9], t: '3,375', z: 3375, v: [[364, 407, 17.5, 6.5]], l: [[364, 376.4, 4.7, 4.6], [373.2, 376.4, 12.5, 4.6]] },
  'estateAndTrustTotal#': { p: 1, m: 'ANCHOR_LABEL', c: 0.81, n: [1, 0.9, 0.9], t: '13,075', z: 13075, v: [[505, 421, 21.4, 6.5]], l: [[114.1, 421, 13.6, 6.5]] },
  'remicName#01': { p: 1, m: 'ROW_CELL', c: 0.9, n: [1, 0.9, 1], t: 'McALLISTER REMIC TRUST', z: 'McALLISTER REMIC TRUST', v: [[48, 457, 92.2, 6.5]], l: [[48, 444.2, 6.1, 4.6], [58.6, 444.2, 13.3, 4.6]] },
  'remicIncome#01': { p: 1, m: 'ROW_CELL', c: 0.81, n: [1, 0.9, 0.9], t: '5,600', z: 5600, v: [[380, 457, 17.5, 6.5]], l: [[380, 444.2, 6.1, 4.6]] },
  'remicTotal#': { p: 1, m: 'ANCHOR_LABEL', c: 0.81, n: [1, 0.9, 0.9], t: '5,600', z: 5600, v: [[505, 469, 17.5, 6.5]], l: [[83.8, 469, 26.1, 6.5]] },
  'totalIncomeOrLoss#': { p: 1, m: 'ANCHOR_LABEL', c: 0.81, n: [1, 0.9, 0.9], t: '63,530', z: 63530, v: [[505, 505, 21.4, 6.5]], l: [[137.2, 505, 28, 6.5]] },
};

/**
 * The schema's declared shape per field: kind, data type, and the key set the
 * group emits **whether or not a value was found there**.
 *
 * A COLUMN group and a labeled ROW group always emit their full declared set —
 * Part I column `C` on a two-property form is a MISSING occurrence, not an
 * absent one. An unlabeled ROW group emits one occurrence per row found, which
 * is why `remicName` declares only `01` here and `propertyAddress` declares
 * `01`–`03` (its third row is a located-but-empty row). `remicExcessInclusion`
 * declares a single null key: its region was never read at all.
 */
const SCHEDULE_E_SHAPE: readonly {
  field: string;
  kind: GroupKind;
  dataType: FieldView['dataType'];
  keys: readonly (string | null)[];
}[] = [
  { field: 'taxpayerName', kind: 'NONE', dataType: 'STRING', keys: [null] },
  { field: 'taxpayerSsn', kind: 'NONE', dataType: 'STRING', keys: [null] },
  { field: 'taxYear', kind: 'NONE', dataType: 'STRING', keys: [null] },
  { field: 'propertyAddress', kind: 'ROW', dataType: 'STRING', keys: ['01', '02', '03'] },
  { field: 'rentsReceived', kind: 'COLUMN', dataType: 'MONEY', keys: ['A', 'B', 'C'] },
  { field: 'mortgageInterest', kind: 'COLUMN', dataType: 'MONEY', keys: ['A', 'B', 'C'] },
  { field: 'depreciationExpense', kind: 'COLUMN', dataType: 'MONEY', keys: ['A', 'B', 'C'] },
  { field: 'totalExpenses', kind: 'COLUMN', dataType: 'MONEY', keys: ['A', 'B', 'C'] },
  { field: 'incomeOrLoss', kind: 'COLUMN', dataType: 'MONEY', keys: ['A', 'B', 'C'] },
  { field: 'totalRentalRealEstateIncomeOrLoss', kind: 'NONE', dataType: 'MONEY', keys: [null] },
  { field: 'partnershipName', kind: 'ROW', dataType: 'STRING', keys: ['A', 'B', 'C', 'D'] },
  { field: 'partnershipEin', kind: 'ROW', dataType: 'STRING', keys: ['A', 'B', 'C', 'D'] },
  { field: 'partnershipPassiveLossAllowed', kind: 'ROW', dataType: 'MONEY', keys: ['A', 'B', 'C', 'D'] },
  { field: 'partnershipPassiveIncome', kind: 'ROW', dataType: 'MONEY', keys: ['A', 'B', 'C', 'D'] },
  { field: 'partnershipNonpassiveLossAllowed', kind: 'ROW', dataType: 'MONEY', keys: ['A', 'B', 'C', 'D'] },
  { field: 'partnershipSection179Expense', kind: 'ROW', dataType: 'MONEY', keys: ['A', 'B', 'C', 'D'] },
  { field: 'partnershipNonpassiveIncome', kind: 'ROW', dataType: 'MONEY', keys: ['A', 'B', 'C', 'D'] },
  { field: 'partnershipAndSCorpTotal', kind: 'NONE', dataType: 'MONEY', keys: [null] },
  { field: 'estateOrTrustName', kind: 'ROW', dataType: 'STRING', keys: ['A', 'B'] },
  { field: 'estateOrTrustPassiveDeductionOrLoss', kind: 'ROW', dataType: 'MONEY', keys: ['A', 'B'] },
  { field: 'estateOrTrustPassiveIncome', kind: 'ROW', dataType: 'MONEY', keys: ['A', 'B'] },
  { field: 'estateOrTrustDeductionOrLoss', kind: 'ROW', dataType: 'MONEY', keys: ['A', 'B'] },
  { field: 'estateOrTrustOtherIncome', kind: 'ROW', dataType: 'MONEY', keys: ['A', 'B'] },
  { field: 'estateAndTrustTotal', kind: 'NONE', dataType: 'MONEY', keys: [null] },
  { field: 'remicName', kind: 'ROW', dataType: 'STRING', keys: ['01'] },
  { field: 'remicIncome', kind: 'ROW', dataType: 'MONEY', keys: ['01'] },
  { field: 'remicExcessInclusion', kind: 'ROW', dataType: 'MONEY', keys: [null] },
  { field: 'remicTotal', kind: 'NONE', dataType: 'MONEY', keys: [null] },
  { field: 'totalIncomeOrLoss', kind: 'NONE', dataType: 'MONEY', keys: [null] },
];

function scheduleEEvidence(truth: Truth): EvidenceView[] {
  const boxes = (role: 'VALUE' | 'LABEL', list: readonly Box[]) =>
    list.map((box, ordinal) =>
      evidence(role, ordinal, truth.p, { x: box[0], y: box[1], width: box[2], height: box[3] }),
    );
  // VALUE before LABEL, each in ordinal order — the server's own evidence order.
  return [...boxes('VALUE', truth.v), ...boxes('LABEL', truth.l)];
}

function scheduleEOccurrence(
  ordinal: number,
  shape: (typeof SCHEDULE_E_SHAPE)[number],
  groupKey: string | null,
): FieldView {
  const truth = SCHEDULE_E_TRUTH[`${shape.field}#${groupKey ?? ''}`];
  const common = {
    id: fieldId(100 + ordinal),
    fieldName: shape.field,
    groupKey,
    groupKind: shape.kind,
    dataType: shape.dataType,
    sensitive: shape.field === 'taxpayerSsn',
    reviewStatus: 'NOT_REVIEWED' as const,
  };
  if (!truth) {
    // The exact shape `FieldExtractionService` writes for a coordinate it
    // looked at and found nothing in — including a provenance of UNKNOWN, since
    // an occurrence with no evidence cites no span to have come from.
    return {
      ...common,
      textProvenance: { source: 'UNKNOWN', ocrEngine: null },
      displayedText: null,
      rawValue: null,
      normalized: null,
      extractionMethod: 'NONE',
      extractorVersion: null,
      confidence: 0,
      confidenceComponents: null,
      validationStatus: 'MANUAL_REVIEW_REQUIRED',
      evidence: [],
    };
  }
  return {
    ...common,
    // The committed Schedule E fixture is a native-text PDF throughout.
    textProvenance: { source: 'NATIVE', ocrEngine: null },
    displayedText: truth.t,
    rawValue: truth.t,
    normalized:
      typeof truth.z === 'number'
        ? { text: null, number: truth.z, date: null }
        : { text: truth.z, number: null, date: null },
    extractionMethod: truth.m,
    extractorVersion: '1.0.0',
    confidence: truth.c,
    confidenceComponents: {
      spanConfidence: truth.n[0],
      anchorStrength: truth.n[1],
      normalizerCertainty: truth.n[2],
    },
    validationStatus: 'NOT_VALIDATED',
    evidence: scheduleEEvidence(truth),
  };
}

/**
 * All 67 occurrences in the read model's own order: **field name ascending,
 * then group key ascending with a null key first** — the guarantee both read
 * paths make (`order by f.fieldName asc, f.groupKey asc nulls first`). Keys
 * sort lexically by code point, which is what the zero-padding is for.
 */
export const SCHEDULE_E_OCCURRENCES: FieldView[] = SCHEDULE_E_SHAPE.flatMap((shape) =>
  shape.keys.map((key) => ({ shape, key })),
)
  .sort((left, right) => {
    const byName = left.shape.field < right.shape.field ? -1 : left.shape.field > right.shape.field ? 1 : 0;
    if (byName !== 0) return byName;
    if (left.key === right.key) return 0;
    if (left.key === null) return -1;
    if (right.key === null) return 1;
    return left.key < right.key ? -1 : 1;
  })
  .map(({ shape, key }, ordinal) => scheduleEOccurrence(ordinal, shape, key));

export const SCHEDULE_E_FIELDS: DocumentFieldsView = {
  documentId: SCHEDULE_E_DOCUMENT_ID,
  documentTypeCode: 'SCHEDULE_E',
  schemaVersion: '1.0.1',
  fields: SCHEDULE_E_OCCURRENCES,
};

/** The Schedule E document, as the package view lists it. */
export const SCHEDULE_E_DOCUMENTS: PackageDocumentsView = {
  packageId: PACKAGE_ID,
  documents: [
    {
      id: SCHEDULE_E_DOCUMENT_ID,
      ordinal: 0,
      documentTypeCode: 'SCHEDULE_E',
      classificationConfidence: 1,
      reviewStatus: 'NOT_REVIEWED',
      boundaryProvenance: 'RULE',
      pages: [
        {
          pageId: PAGE_1_ID,
          packagePageIndex: 0,
          classification: { type: 'SCHEDULE_E', confidence: 1, rulePackVersion: '1.0.0', coQualifyingTypes: []  },
        },
        {
          pageId: PAGE_2_ID,
          packagePageIndex: 1,
          classification: { type: 'SCHEDULE_E', confidence: 0.9, rulePackVersion: '1.0.0', coQualifyingTypes: []  },
        },
      ],
    },
  ],
  unassignedPages: [],
};

export const PACKAGE_DOCUMENTS: PackageDocumentsView = {
  packageId: PACKAGE_ID,
  documents: [
    {
      id: DOCUMENT_ID,
      ordinal: 0,
      documentTypeCode: 'PAYSTUB',
      classificationConfidence: 0.97,
      reviewStatus: 'NOT_REVIEWED',
      boundaryProvenance: 'PACKAGE_START',
      pages: [
        {
          pageId: PAGE_1_ID,
          packagePageIndex: 0,
          classification: { type: 'PAYSTUB', confidence: 0.97, rulePackVersion: 'paystub-1', coQualifyingTypes: []  },
        },
        {
          pageId: PAGE_2_ID,
          packagePageIndex: 1,
          classification: { type: 'PAYSTUB', confidence: 0.93, rulePackVersion: 'paystub-1', coQualifyingTypes: []  },
        },
      ],
    },
  ],
  unassignedPages: [],
};

/**
 * The cheap case: a native-text paystub. Nothing went to OCR, nothing was
 * retried, and — as for every package the engine has ever parsed — no model was
 * called, so `modelCost` is a measured set of zeros.
 */
export const PACKAGE_USAGE: PackageUsageView = {
  packageId: PACKAGE_ID,
  pages: { total: 2, ocrPages: 0, byTextLayer: { NATIVE: 2, SCANNED: 0, MIXED: 0, NONE: 0 } },
  documents: [
    {
      documentId: DOCUMENT_ID,
      ordinal: 0,
      documentTypeCode: 'PAYSTUB',
      pages: 2,
      ocrPages: 0,
      byTextLayer: { NATIVE: 2, SCANNED: 0, MIXED: 0, NONE: 0 },
    },
  ],
  unassignedPages: 0,
  elapsed: {
    scope: 'PACKAGE',
    perDocumentElapsedAvailable: false,
    packageWallClockMs: 2000,
    packageStageElapsedMs: 1887,
    failedAttemptElapsedMs: 0,
    stages: [
      {
        stage: 'RENDERING',
        status: 'SUCCEEDED',
        attempt: 1,
        startedAt: '2026-01-17T09:30:01Z',
        finishedAt: '2026-01-17T09:30:01Z',
        durationMs: 412,
        skipReason: null,
        errorCode: null,
        workerVersion: 'worker/1.4.0',
        parserVersions: { pymupdf: '1.24.9' },
      },
      {
        stage: 'OCR_PROCESSING',
        status: 'SKIPPED',
        attempt: 1,
        startedAt: null,
        finishedAt: null,
        durationMs: null,
        skipReason: 'native text layer present',
        errorCode: null,
        workerVersion: null,
        parserVersions: null,
      },
      {
        stage: 'PARSING',
        status: 'SUCCEEDED',
        attempt: 1,
        startedAt: '2026-01-17T09:30:02Z',
        finishedAt: '2026-01-17T09:30:03Z',
        durationMs: 1233,
        skipReason: null,
        errorCode: null,
        workerVersion: 'worker/1.4.0',
        parserVersions: { pymupdf: '1.24.9' },
      },
      {
        stage: 'EXTRACTING',
        status: 'SUCCEEDED',
        attempt: 1,
        startedAt: '2026-01-17T09:30:03Z',
        finishedAt: '2026-01-17T09:30:03Z',
        durationMs: 242,
        skipReason: null,
        errorCode: null,
        workerVersion: null,
        parserVersions: null,
      },
    ],
  },
  attempts: {
    jobAttempt: 1,
    jobStatus: 'COMPLETED',
    parseGeneration: 1,
    stageAttempts: 4,
    retriedAttempts: 0,
    failedAttempts: 0,
    retries: [],
  },
  modelCost: {
    calls: 0,
    inputTokens: 0,
    outputTokens: 0,
    costUsd: 0,
    currency: 'USD',
    byModel: [],
    producers: [
      {
        producer: 'ENGINE',
        observed: true,
        reason: 'read from ai_interpretation in this database; zero rows means zero calls',
      },
      {
        producer: 'RAG_BRAIN',
        observed: false,
        reason: 'spends in another service and is not recorded here',
      },
    ],
  },
};

/**
 * The expensive case, and the one the owner actually uploaded: a 26-page tax
 * return that was 26/26 SCANNED, with the WORKER_UNAVAILABLE retry loop that
 * cost eight seconds and produced nothing.
 */
export const SCANNED_PACKAGE_USAGE: PackageUsageView = {
  ...PACKAGE_USAGE,
  pages: { total: 26, ocrPages: 26, byTextLayer: { NATIVE: 0, SCANNED: 26, MIXED: 0, NONE: 0 } },
  documents: [
    {
      documentId: DOCUMENT_ID,
      ordinal: 0,
      documentTypeCode: 'TAX_RETURN',
      pages: 26,
      ocrPages: 26,
      byTextLayer: { NATIVE: 0, SCANNED: 26, MIXED: 0, NONE: 0 },
    },
  ],
  elapsed: {
    ...PACKAGE_USAGE.elapsed,
    packageWallClockMs: 190_000,
    packageStageElapsedMs: 184_300,
    failedAttemptElapsedMs: 8000,
  },
  attempts: {
    jobAttempt: 1,
    jobStatus: 'HUMAN_REVIEW_REQUIRED',
    parseGeneration: 1,
    stageAttempts: 6,
    retriedAttempts: 2,
    failedAttempts: 2,
    retries: [{ stage: 'OCR_PROCESSING', attempts: 3, lastErrorCode: 'WORKER_UNAVAILABLE' }],
  },
};

export const COMPLETED_JOB: JobResponse = {
  id: JOB_ID,
  packageId: PACKAGE_ID,
  status: 'COMPLETED',
  currentStage: null,
  attempt: 1,
  stages: [
    { stage: 'RENDERING', status: 'SUCCEEDED', attempt: 1, skipReason: null, errorCode: null, durationMs: 412 },
    { stage: 'TEXT_EXTRACTION', status: 'SUCCEEDED', attempt: 1, skipReason: null, errorCode: null, durationMs: 233 },
    {
      stage: 'OCR_PROCESSING',
      status: 'SKIPPED',
      attempt: 1,
      skipReason: 'native text layer present',
      errorCode: null,
      durationMs: null,
    },
    { stage: 'CLASSIFYING', status: 'SUCCEEDED', attempt: 1, skipReason: null, errorCode: null, durationMs: 88 },
    { stage: 'EXTRACTING', status: 'SUCCEEDED', attempt: 1, skipReason: null, errorCode: null, durationMs: 154 },
  ],
  createdAt: '2026-01-17T09:30:00Z',
  startedAt: '2026-01-17T09:30:01Z',
  finishedAt: '2026-01-17T09:30:03Z',
};

/** The same job, stopped at OCR with a stable error code. */
export const FAILED_JOB: JobResponse = {
  ...COMPLETED_JOB,
  status: 'FAILED',
  currentStage: 'OCR_PROCESSING',
  stages: [
    COMPLETED_JOB.stages[0],
    COMPLETED_JOB.stages[1],
    {
      stage: 'OCR_PROCESSING',
      status: 'FAILED',
      attempt: 3,
      skipReason: null,
      errorCode: 'WORKER_TIMEOUT',
      durationMs: 30_000,
    },
  ],
};
