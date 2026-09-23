/**
 * The engine's wire types, hand-written from `docs/api/openapi.json`.
 *
 * Hand-written rather than generated on purpose: springdoc emits no `required`
 * array, so a generator would make every property optional and push a `?` into
 * every call site — which reads as "this might be missing" for fields the Java
 * records prove are always present. The nullability below mirrors the records
 * (`extraction/.../DocumentFieldsView.java`,
 * `classification/.../PackageDocumentsView.java`,
 * `orchestration/.../JobResponse.java`), so `null` here means the server can
 * genuinely send null.
 *
 * Names are copied from the spec, never invented. Two known spec quirks are
 * annotated where they bite: see {@link UnassignedPageView} and
 * {@link EvidenceRole}.
 */

/** UUID as the API serialises it. Aliased for readability, not enforced. */
export type Uuid = string;

/** ISO-8601 instant, e.g. `2026-01-17T09:30:00Z`. */
export type IsoInstant = string;

/** ISO-8601 local date, e.g. `2026-01-17`. */
export type IsoDate = string;

// ---------------------------------------------------------------------------
// Enumerations. The API sends these as bare strings (`Enum.name()`), so the
// unions are documentation plus autocomplete — every consumer must still cope
// with a value it does not know, because the server may add one before the UI
// is redeployed.
// ---------------------------------------------------------------------------

/** `ProcessingStatus` — a job's status and the name of each stage. */
export type ProcessingStage =
  | 'UPLOADED'
  | 'VALIDATING'
  | 'NORMALIZING'
  | 'RENDERING'
  | 'TEXT_EXTRACTION'
  | 'OCR_PROCESSING'
  | 'PARSING'
  | 'CLASSIFYING'
  | 'SPLITTING'
  | 'BOUNDARY_EXTRACTION'
  | 'EXTRACTING'
  | 'AI_EXTRACTION'
  | 'FINALIZING'
  | 'VALIDATING_DATA'
  | 'AI_REVIEW'
  | 'HUMAN_REVIEW_REQUIRED'
  | 'COMPLETED'
  | 'FAILED';

/** `StageStatus`. */
export type StageStatus = 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'SKIPPED';

/** `ReviewStatus` — the package/document one. A FIELD uses {@link FieldReviewStatus}. */
export type ReviewStatus = 'NOT_REVIEWED' | 'IN_REVIEW' | 'REVIEWED';

/**
 * `ExtractedField.REVIEW_*`. A different vocabulary from the document-level
 * {@link ReviewStatus}, and deliberately so: a document is worked through,
 * a field is judged.
 */
export type FieldReviewStatus =
  | 'NOT_REVIEWED'
  | 'CONFIRMED'
  | 'CORRECTED'
  | 'REJECTED'
  | (string & {});

/** `TextLayer` — how a page's text was obtained. */
export type TextLayer = 'NATIVE' | 'SCANNED' | 'MIXED' | 'NONE';

/**
 * `ExtractionMethod`. `NONE` is the marker of a field that was looked for and
 * not found.
 *
 * `ROW_CELL` is what a labeled- or counted-row group writes for a cell it read
 * off a located row; the server has emitted it since Spec 5a and this union was
 * stale for exactly that long. The `(string & {})` arm is the fix for the
 * general case: this file's header promises consumers cope with unknown values,
 * and a closed union turns the next method the engine invents into a compile
 * error in a UI that would otherwise have rendered it fine.
 *
 * `LABEL_ABOVE` (V40) is `LABEL_BELOW`'s vertical mirror — the tile layout an
 * online activity print-out states a balance in, the amount over its caption.
 *
 * `DERIVED` (2026-09-23) is a value computed from other captured fields — a bank statement's withdrawals from its balances — and carries no page box.
 */
export type ExtractionMethod =
  | 'ANCHOR_LABEL'
  | 'TABLE_CLUSTER'
  | 'REGEX'
  | 'FORM_FIELD'
  | 'OCR_LINE'
  | 'LLM'
  | 'HUMAN'
  | 'NONE'
  | 'CHECKBOX_STATE'
  | 'SIGNATURE_PRESENCE'
  | 'LABEL_BELOW'
  | 'ROW_CELL'
  | 'LABEL_ABOVE'
  | 'DERIVED'
  | (string & {});

/**
 * How a field's occurrences are laid out **on the form** — the consumer
 * contract's §1.7 vocabulary, present on every field and never null.
 *
 * Read this beside `groupKey`, never instead of it. The pair is a truth table,
 * and only one of its three rows is the ordinary case:
 *
 * | `groupKind` | `groupKey` | meaning |
 * |---|---|---|
 * | `NONE` | always null | the field does not repeat |
 * | `COLUMN`/`ROW` | a key | one occurrence at that coordinate |
 * | `COLUMN`/`ROW` | **null** | **the region was never read** — one explicitly-missing occurrence standing for a whole table |
 *
 * That third row is why this member exists. A row-grouped field whose table
 * region could not be located persists ONE occurrence with a null key, which on
 * the wire is otherwise byte-identical to an ungrouped missing field — so
 * `groupKey === null` alone can never be read as "ungrouped".
 *
 * `COLUMN` means the occurrences are the table's **columns** (Schedule E Part
 * I's A/B/C money grid reads across, as the form prints it); `ROW` means they
 * are its **rows**. Do not re-derive the orientation from `dataType`: an
 * all-numeric ROW group of 99 rows would come out as 99 columns.
 */
export type GroupKind = 'COLUMN' | 'ROW' | 'NONE' | (string & {});

/**
 * Where a captured value's characters came from. **Not** {@link TextLayer}:
 * that is a whole-PAGE verdict, and on a `MIXED` page it is the same verdict for
 * every field on it, including the ones read entirely from the text layer. This
 * is per VALUE, folded from the `text_span` rows that value's VALUE evidence
 * cites.
 *
 * - `NATIVE` — every contributing span came from the PDF's own text layer.
 * - `OCR` — every contributing span was recognised from pixels.
 * - `MIXED` — the characters came from **both**. Not a rounding error: a
 *   native-text form with a handwritten or stamped region really does produce
 *   this, and reporting either pure answer would hide half the truth.
 * - `UNKNOWN` — no text span backs the value. A missing occurrence looks like
 *   this, and so does an element-only capture (checkbox state, signature
 *   presence). Never read it as "probably native".
 */
export type TextSource = 'NATIVE' | 'OCR' | 'MIXED' | 'UNKNOWN' | (string & {});

/**
 * Where a value's characters came from, and — when any of them were recognised —
 * which engine did the recognising.
 *
 * **This is not a fourth confidence component.** `confidenceComponents` stays
 * exactly three factors whose product is `confidence`; provenance says which
 * pipeline produced the characters, not how sure it was. Weight an OCR'd value
 * differently if you like, but do so beside the score, never by folding this
 * into it.
 */
export type TextProvenanceView = {
  source: TextSource;
  /**
   * `"RAPIDOCR"` / `"TESSERACT"`, or the two joined with `+` when one value was
   * reconciled from both. Null exactly when no OCR span contributed — so it is
   * null for every `NATIVE` and every `UNKNOWN` value.
   */
  ocrEngine: string | null;
};

/** `DataType` of an extracted field. */
export type DataType = 'STRING' | 'NUMBER' | 'DATE' | 'ENUM' | 'MONEY';

/**
 * `ExtractedField.VALIDATION_*`. Not a Java enum — string constants, so the
 * union is advisory. `NOT_VALIDATED` is what an extracted field starts as;
 * Phase 4's validation rules will move it. `MANUAL_REVIEW_REQUIRED` is what a
 * MISSING field is born as, and is the strongest signal the UI currently has.
 */
export type ValidationStatus =
  | 'NOT_VALIDATED'
  | 'VALID'
  | 'WARNING'
  | 'ERROR'
  | 'UNABLE_TO_VALIDATE'
  | 'MANUAL_REVIEW_REQUIRED'
  | (string & {});

/**
 * `FieldEvidence.ROLE_*`. `VALUE` is the text the value was read from; `LABEL`
 * is the anchor that made that reading the right one; `CONTEXT` is supporting
 * neighbourhood. The `(string & {})` arm keeps an unknown future role
 * assignable rather than a type error at the fetch boundary — the UI degrades
 * to a neutral style instead of refusing to render.
 */
export type EvidenceRole = 'VALUE' | 'LABEL' | 'CONTEXT' | (string & {});

/** `PackageDocumentsView.UnassignedReason`. */
export type UnassignedReason = 'BLANK' | 'DUPLICATE' | 'CLEARED' | (string & {});

/** `MalwareScanStatus`. */
export type MalwareScanStatus = 'PENDING' | 'CLEAN' | 'INFECTED' | 'SKIPPED';

// ---------------------------------------------------------------------------
// POST /v1/packages
// ---------------------------------------------------------------------------

export type FileResult = {
  id: Uuid;
  originalFilename: string;
  contentType: string;
  sizeBytes: number;
  sha256: string;
  pageCount: number;
};

/** A file the package already contained. Carries a hash prefix, never content. */
export type DuplicateWarning = {
  shaPrefix: string;
};

export type UploadResult = {
  packageId: Uuid;
  jobId: Uuid;
  files: FileResult[];
  warnings: DuplicateWarning[];
};

// ---------------------------------------------------------------------------
// GET /v1/jobs/{id}, POST /v1/jobs/{id}/resume
// ---------------------------------------------------------------------------

export type StageResponse = {
  stage: ProcessingStage;
  status: StageStatus;
  attempt: number;
  /** Why a SKIPPED stage was skipped. Null unless `status` is `SKIPPED`. */
  skipReason: string | null;
  /** Stable `ErrorCode` name. Null unless the stage failed. Never a stack trace. */
  errorCode: string | null;
  durationMs: number | null;
};

export type JobResponse = {
  id: Uuid;
  packageId: Uuid;
  status: ProcessingStage;
  currentStage: ProcessingStage | null;
  attempt: number;
  stages: StageResponse[];
  createdAt: IsoInstant;
  startedAt: IsoInstant | null;
  finishedAt: IsoInstant | null;
};

// ---------------------------------------------------------------------------
// GET /v1/packages/{id}
// ---------------------------------------------------------------------------

export type FileView = {
  id: Uuid;
  ordinal: number;
  originalFilename: string;
  contentType: string;
  declaredContentType: string | null;
  sizeBytes: number;
  sha256: string;
  pageCount: number;
  malwareScanStatus: MalwareScanStatus;
};

/**
 * `GET /v1/packages/{id}`. Named `PackageSummary` rather than the spec's
 * `PackageView` only because a React component already owns that name; the
 * wire shape is unchanged.
 */
export type PackageSummary = {
  id: Uuid;
  loanId: Uuid | null;
  name: string | null;
  reviewStatus: ReviewStatus;
  pageCount: number;
  createdAt: IsoInstant;
  files: FileView[];
};

// ---------------------------------------------------------------------------
// GET /v1/packages/{id}/pages
// ---------------------------------------------------------------------------

export type PageView = {
  pageId: Uuid;
  /** The file this page came from — the one to hand pdf.js. */
  sourceFileId: Uuid;
  /** 0-based index WITHIN `sourceFileId`. This is pdf.js's page, minus one. */
  pageIndex: number;
  /** 0-based index within the whole package. This is what evidence refers to. */
  packagePageIndex: number;
  /** Rotation-0 dimensions in PDF points — they do NOT account for `rotation`. */
  widthPt: number;
  heightPt: number;
  /** PDF `/Rotate`: degrees a viewer turns the page clockwise. */
  rotation: number;
  /** What the worker's skew/orientation detection saw. Advisory only. */
  detectedRotation: number | null;
  renderDpi: number | null;
  /** Whether `GET /v1/pages/{id}/render` will return an image. */
  hasRender: boolean;
  textLayer: TextLayer;
  blank: boolean;
  duplicateOfPageId: Uuid | null;
  /**
   * The sniffed type of the file this page came from: `application/pdf`, or one of
   * `image/jpeg` | `image/png` | `image/tiff` | `image/heic`. Only a PDF source has anything to
   * give pdf.js — a photographed or screenshotted document IS its page raster.
   */
  sourceContentType: string;
};

export type PackagePagesView = {
  packageId: Uuid;
  pages: PageView[];
};

// ---------------------------------------------------------------------------
// GET /v1/packages/{id}/documents
// ---------------------------------------------------------------------------

/** Null only if CLASSIFYING has not run yet. */
export type PageClassificationView = {
  type: string;
  confidence: number;
  rulePackVersion: string;
  /**
   * Every type whose rule pack cleared its OWN threshold on this page, when more
   * than one did — a SUSPECTED multi-document sheet (a licence photocopied beside
   * a Social Security card, two receipts on one scan). Empty on a normal page.
   *
   * The engine cannot split WITHIN a page — every mechanism it has cuts between
   * pages — so the page still belongs to exactly one document and the verdict is
   * unchanged. This is here so the second document stops being invisible.
   */
  coQualifyingTypes: string[];
};

export type DocumentPageView = {
  pageId: Uuid;
  packagePageIndex: number;
  classification: PageClassificationView | null;
};

/**
 * How a document's STARTING boundary was decided, strongest evidence first:
 *
 * - `HUMAN` — a reviewer placed or reshaped it. Outranks everything.
 * - `RULE` — a page matched a pack anchor declaring a form header. Proof.
 * - `PACKAGE_START` — the document opens the package. Structural, not inferred.
 * - `INSTANCE_CHANGE` — the document's own printed identity (a bank
 *   statement's period) changed here, so the next instance of the same type
 *   begins. The only cut that separates two documents no anchor distinguishes.
 * - `TYPE_CHANGE` — the page type changed. Real, but blind to same-type seams.
 * - `AI` — proposed by boundary extraction (engine Phase D).
 *
 * `null` for documents split before the engine recorded it (migration V24).
 *
 * Open, like `ExtractionMethod`: a value this UI cannot name must render as an
 * unknown boundary, never break the document list.
 */
export type BoundaryProvenance =
  | 'HUMAN'
  | 'RULE'
  | 'PACKAGE_START'
  | 'INSTANCE_CHANGE'
  | 'TYPE_CHANGE'
  | 'AI'
  | (string & {});

export type DocumentView = {
  id: Uuid;
  ordinal: number;
  documentTypeCode: string;
  classificationConfidence: number | null;
  reviewStatus: ReviewStatus;
  boundaryProvenance: BoundaryProvenance | null;
  /**
   * Pages that carried no type and were absorbed into this document as continuations by the
   * splitter (V46). 0 = every page classified as the document's type; a large count is where an
   * unrelated, untypable document was probably glued on. Null when the engine did not count
   * (pre-V46 splits, human-reshaped documents).
   */
  absorbedUntypedPages?: number | null;
  pages: DocumentPageView[];
};

/**
 * A page that stayed out of every document, and why.
 *
 * ONE TS type covers BOTH endpoints' shapes: the documents endpoint emits
 * `packagePageIndex` (0-based) and the export endpoint emits `pageNumber`
 * (1-based). The UI reads whichever arrived.
 *
 * `docs/api/openapi.json` once collided these into a single schema (springdoc
 * flattening two same-named nested records) and now declares them separately as
 * `UnassignedPageView` and `ExportUnassignedPageView`. That is why the two
 * properties are optional here rather than split into two types: the shape is a
 * union of both, and `openapi-drift.test.ts` checks it against both schemas.
 */
export type UnassignedPageView = {
  pageId: Uuid;
  /** From `GET /v1/packages/{id}/documents`. 0-based. */
  packagePageIndex?: number;
  /** From `GET /v1/packages/{id}/export`. 1-based. */
  pageNumber?: number;
  reason: UnassignedReason;
};

export type PackageDocumentsView = {
  packageId: Uuid;
  documents: DocumentView[];
  unassignedPages: UnassignedPageView[];
};

// ---------------------------------------------------------------------------
// POST /v1/packages/{id}/regroup
// ---------------------------------------------------------------------------

/**
 * One page reassignment. `toDocumentId` null unassigns the page; a value is
 * either an existing document id OR a {@link NewDocument} `tempId` the server
 * resolves after creating it. Mirrors `RegroupRequest.Move` (Java), whose
 * `toDocumentId` is a bare `String` for exactly that dual meaning.
 */
export type Move = {
  pageId: Uuid;
  toDocumentId: string | null;
};

/**
 * A document to create in this call. `tempId` is a client-chosen handle wiring
 * these `pageIds` (and any `moves[].toDocumentId` referencing it) to a document
 * the server has not assigned a real id to yet. Mirrors
 * `RegroupRequest.NewDocument`.
 */
export type NewDocument = {
  tempId: string;
  documentTypeCode: string;
  pageIds: Uuid[];
};

/**
 * The membership delta a reviewer submits to `POST /v1/packages/{id}/regroup`.
 *
 * `intent` is audit metadata the server records verbatim (`MOVE_PAGES` ·
 * `SPLIT` · `MERGE` · `ASSIGN` · `UNASSIGN` · `NEW_DOCUMENT`), never behaviour —
 * the server computes the resulting grouping from `moves`/`newDocuments`/
 * `deletedDocumentIds` alone (design §4.1). Kept a plain `string` so an unknown
 * future intent is still submittable.
 */
export type RegroupRequest = {
  intent: string;
  moves: Move[];
  newDocuments: NewDocument[];
  deletedDocumentIds: Uuid[];
  reason?: string | null;
};

// ---------------------------------------------------------------------------
// POST /v1/pages/{id}/verdict
// ---------------------------------------------------------------------------

/**
 * A reviewer's override of a page's blank/duplicate signal. `NOT_BLANK` clears
 * `is_blank`; `NOT_DUPLICATE` clears `duplicate_of_page_id`. It does not re-run
 * detection and does not assign the page — assignment is a subsequent regroup
 * (design §4.2).
 */
export type PageVerdict = 'NOT_BLANK' | 'NOT_DUPLICATE';

// ---------------------------------------------------------------------------
// GET /v1/documents/{id}/fields
// ---------------------------------------------------------------------------

/** Exactly one arm populated per data type; all null for a missing field. */
export type NormalizedView = {
  text: string | null;
  number: number | null;
  date: IsoDate | null;
};

/** The confidence formula's inputs — auditable, not a bare number. Null when missing. */
export type ConfidenceComponentsView = {
  spanConfidence: number | null;
  anchorStrength: number | null;
  normalizerCertainty: number | null;
};

/**
 * One evidence box: PDF points, top-left origin, at rotation-0, rounded to
 * 0.1pt. The canonical space `features/review/coordinates.ts` converts from —
 * do not do arithmetic on these anywhere else.
 */
export type EvidenceView = {
  role: EvidenceRole;
  ordinal: number;
  pageId: Uuid;
  packagePageIndex: number;
  x: number;
  y: number;
  width: number;
  height: number;
  textSpanId: number | null;
  layoutElementId: Uuid | null;
};

export type FieldView = {
  /**
   * The occurrence row's own id — the only identity that survives a repeating
   * field. A field name repeats within a document (one entry per occurrence),
   * so anything keyed by name — a React key, a selection, a reveal — is wrong
   * the moment a Schedule E is opened. It is also the coordinate
   * `PATCH /v1/fields/{id}` addresses.
   */
  id: Uuid;
  fieldName: string;
  /**
   * The value **printed on the form** at this occurrence's coordinate — a
   * column letter, a preprinted row letter, or a zero-padded ordinal. Null for
   * a field that does not repeat, and — see {@link GroupKind} — also for the
   * one occurrence that stands for a table region that was never read. Present
   * and null, never omitted.
   */
  groupKey: string | null;
  /** See {@link GroupKind}: the shape, so `groupKey: null` is never ambiguous. */
  groupKind: GroupKind;
  /**
   * See {@link TextProvenanceView}: whether this occurrence's value was READ or
   * RECOGNISED. Present and never null. Surface it — an OCR'd value that renders
   * identically to a native one asks a reviewer to trust a guess as far as a
   * fact.
   */
  textProvenance: TextProvenanceView;
  dataType: DataType;
  /** Verbatim as it appears on the page. Null for a missing field. */
  displayedText: string | null;
  rawValue: string | null;
  normalized: NormalizedView | null;
  extractionMethod: ExtractionMethod;
  extractorVersion: string | null;
  confidence: number;
  confidenceComponents: ConfidenceComponentsView | null;
  validationStatus: ValidationStatus;
  reviewStatus: FieldReviewStatus;
  /** The schema says this value class is sensitive. See `features/review/masking.ts`. */
  sensitive: boolean;
  /** VALUE before LABEL, each in ordinal order. Empty for a missing field. */
  evidence: EvidenceView[];
};

export type DocumentFieldsView = {
  documentId: Uuid;
  documentTypeCode: string;
  schemaVersion: string;
  fields: FieldView[];
};

// ---------------------------------------------------------------------------
// GET /v1/packages/{id}/export
// ---------------------------------------------------------------------------

export type BoundingBoxView = {
  x: number;
  y: number;
  width: number;
  height: number;
};

export type ExportFieldView = {
  fieldName: string;
  /** As on {@link FieldView}. The export path carries the same occurrence identity. */
  groupKey: string | null;
  /** As on {@link FieldView}. See {@link GroupKind}. */
  groupKind: GroupKind;
  /** As on {@link FieldView}. See {@link TextProvenanceView}. */
  textProvenance: TextProvenanceView;
  value: string | null;
  displayedText: string | null;
  normalizedValue: unknown;
  confidence: number;
  extractionMethod: ExtractionMethod;
  validationStatus: ValidationStatus;
  reviewStatus: FieldReviewStatus;
  /** 1-based. Null for a missing field. */
  pageNumber: number | null;
  boundingBox: BoundingBoxView | null;
};

export type ExportDocumentView = {
  id: Uuid;
  ordinal: number;
  documentTypeCode: string;
  classificationConfidence: number | null;
  reviewStatus: ReviewStatus;
  boundaryProvenance: BoundaryProvenance | null;
  /** See DocumentView.absorbedUntypedPages. */
  absorbedUntypedPages?: number | null;
  /** 1-based. */
  pageNumbers: number[];
  fields: ExportFieldView[];
};

export type PackageExportView = {
  packageId: Uuid;
  documents: ExportDocumentView[];
  unassignedPages: UnassignedPageView[];
};

// ---------------------------------------------------------------------------
// GET /v1/packages/{id}/usage — what the package cost to parse.
//
// Mirrors `results/.../PackageUsageView.java`. The one thing to carry across
// from that record's Javadoc, because it is the whole point of the endpoint:
// parsing makes ZERO paid model calls today, so cost is COMPUTE and
// `modelCost` reports a MEASURED zero rather than being absent or invented.
// ---------------------------------------------------------------------------

/** Every {@link TextLayer} with its count. All four keys are always present. */
export type TextLayerCounts = Record<TextLayer, number>;

export type PageUsageView = {
  total: number;
  /** SCANNED + MIXED — the pages that were rendered and OCR'd. The expensive ones. */
  ocrPages: number;
  byTextLayer: TextLayerCounts;
};

/**
 * Per-document page accounting. Genuinely derivable (a page belongs to at most
 * one logical document, and `text_layer` lives on the page), unlike elapsed.
 */
export type DocumentUsageView = {
  documentId: Uuid;
  ordinal: number;
  documentTypeCode: string;
  pages: number;
  ocrPages: number;
  byTextLayer: TextLayerCounts;
};

export type StageUsageView = {
  stage: ProcessingStage;
  status: StageStatus;
  attempt: number;
  startedAt: IsoInstant | null;
  finishedAt: IsoInstant | null;
  durationMs: number | null;
  skipReason: string | null;
  errorCode: string | null;
  workerVersion: string | null;
  /** The pinned library versions for the run; shape is the worker's, not ours. */
  parserVersions: Record<string, unknown> | null;
};

/**
 * Elapsed, at PACKAGE scope and labelled so.
 *
 * `scope` and `perDocumentElapsedAvailable` are not decoration: a stage row is
 * keyed by JOB, and the stages that dominate the clock (RENDER, TEXT, OCR,
 * PARSE) all run before any logical document exists. Dividing by document
 * count would make a one-page W-2 look as expensive as a 26-page scan.
 */
export type ElapsedView = {
  scope: 'PACKAGE' | (string & {});
  perDocumentElapsedAvailable: boolean;
  /** Job start to finish, including queueing. Null until both timestamps exist. */
  packageWallClockMs: number | null;
  /** Sum of every attempt's `durationMs`: time actually spent inside stages. */
  packageStageElapsedMs: number;
  /** The share of the above spent on attempts that FAILED — compute that bought nothing. */
  failedAttemptElapsedMs: number;
  stages: StageUsageView[];
};

export type RetriedStageView = {
  stage: ProcessingStage;
  /** The highest attempt number the stage reached. */
  attempts: number;
  lastErrorCode: string | null;
};

export type AttemptsView = {
  /** Null when no job row exists yet — NOT 1. A package never processed has no attempt count. */
  jobAttempt: number | null;
  jobStatus: ProcessingStage | null;
  parseGeneration: number | null;
  stageAttempts: number;
  retriedAttempts: number;
  failedAttempts: number;
  retries: RetriedStageView[];
};

export type ModelSpendView = {
  producer: string;
  provider: string;
  model: string;
  calls: number;
  inputTokens: number;
  outputTokens: number;
  costUsd: number;
};

/**
 * A known spender and whether this endpoint can see its spend. Present so a
 * `$0.00` is readable as "the engine spent nothing", never as "nothing was
 * spent anywhere".
 */
export type CostProducerView = {
  producer: string;
  observed: boolean;
  reason: string;
};

/**
 * Model spend. Today every number is zero because nothing calls a model; the
 * block exists at full shape now so the UI needs no change when it stops being
 * zero (the engine's Phase L tier fills it by writing `ai_interpretation`
 * rows — the same mechanism, not a second one).
 */
export type ModelCostView = {
  calls: number;
  inputTokens: number;
  outputTokens: number;
  costUsd: number;
  currency: string;
  byModel: ModelSpendView[];
  producers: CostProducerView[];
};

export type PackageUsageView = {
  packageId: Uuid;
  pages: PageUsageView;
  documents: DocumentUsageView[];
  unassignedPages: number;
  elapsed: ElapsedView;
  attempts: AttemptsView;
  modelCost: ModelCostView;
};

// ---------------------------------------------------------------------------
// Errors
// ---------------------------------------------------------------------------

/**
 * The RFC 9457 body `platform/.../GlobalExceptionHandler.java` returns.
 *
 * `code` is the stable `ErrorCode` name and is the only part safe to show a
 * user: the handler deliberately never serialises an exception message,
 * because one may quote document content. `params` carries sizes and counts
 * only, by the same contract.
 */
export type ProblemDetail = {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  instance?: string;
  code?: string;
  params?: Record<string, unknown> | null;
};

// ---------------------------------------------------------------------------
// PATCH /v1/fields/{id} · POST /v1/documents/{id}/review · POST /v1/documents/{id}/classification
// ---------------------------------------------------------------------------

export type FieldCorrectionAction = 'CONFIRM' | 'CORRECT' | 'REJECT';

export type FieldCorrectionRequest = {
  action: FieldCorrectionAction;
  /** Required for CORRECT — the value verbatim as printed. */
  value?: string | null;
  reason?: string | null;
  /** CORRECT only: the document-relative page the value is printed on (0-based). */
  pageIndex?: number | null;
};

export type FieldCorrectionResult = {
  fieldId: Uuid;
  fieldName: string;
  /** Masked when the field is sensitive, like every read surface. */
  machineValue: string | null;
  effectiveValue: string | null;
  reviewStatus: FieldReviewStatus;
};

export type DocumentDecisionResult = {
  documentId: Uuid;
  documentTypeCode: string;
  reviewStatus: ReviewStatus;
};
