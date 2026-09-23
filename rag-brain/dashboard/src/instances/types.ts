/**
 * The wire shapes of the instance control plane, mirrored from the backend DTOs.
 *
 * Every type here was read off the Java record that produces it rather than from the phase plan's
 * illustrative interfaces, which are a sketch and differ in several places — most consequentially
 * that an instance has no `id` of its own on the wire. Its identity is the pair
 * `(brainId, slug)`, which is also what the routes are keyed on.
 *
 * Conventions: Java `UUID` and `OffsetDateTime` both arrive as strings. Java `BigDecimal` arrives
 * as a JSON number, so it is typed `number` here to match what actually parses; anywhere money is
 * summed or compared rather than displayed, do it in the smallest unit and not in float.
 *
 * Nullable means the backend can genuinely omit it. That distinction carries product meaning here:
 * a null actual-usage figure means the provider did not report the category, and the rule is that
 * it renders as `Unavailable` and never as zero.
 */

/** What addresses an instance everywhere: the brain that owns it and its slug within that brain. */
export interface InstanceRouteKey {
  brainId: string;
  instanceSlug: string;
}

export type InstanceState = "ACTIVE" | "DISABLED";

/** Mirrors `InstanceAdminDtos.InstanceSummary`. */
export interface InstanceSummary {
  brainId: string;
  slug: string;
  displayName: string;
  purpose: string;
  state: InstanceState;
  /** Null when no release has been promoted; a live release is not implied by existing. */
  liveReleaseNumber: number | null;
  candidateCount: number;
  manifestVersion: number | null;
  provider: string | null;
  model: string | null;
  collectionCount: number | null;
  hasCandidateRelease: boolean;
  limitationCode: string | null;
  limitationFlags: string[];
  createdAt: string;
  updatedAt: string;
}

/** Mirrors `InstanceAdminDtos.ReleaseSummary`. */
export interface ReleaseSummary {
  releaseId: string;
  releaseNumber: number;
  provenance: string;
  live: boolean;
  manifestVersion: number | null;
  provider: string | null;
  model: string | null;
  collectionCount: number | null;
  limitationCode: string | null;
  limitationFlags: string[];
  createdAt: string;
}

/** Mirrors `InstanceAdminDtos.InstanceDetail` — the summary plus the resolved live release. */
export interface InstanceDetail extends InstanceSummary {
  liveRelease: ReleaseSummary | null;
}

// ============================================================ releases and promotion

/** Mirrors `InstanceReleaseDtos.ViolationView`. Section and code only — never a configured value. */
export interface Violation {
  section: string;
  code: string;
}

export interface ValidationView {
  valid: boolean;
  violations: Violation[];
}

export interface CandidateReleaseView {
  instanceId: string;
  releaseId: string;
  releaseNumber: number;
  provenanceMode: string;
  manifestSha256: string;
  predecessorReleaseId: string | null;
}

export interface EvaluationView {
  evaluationId: string;
  releaseId: string;
  scenarioSetId: string;
  scenarioSetVersion: number;
  score: number;
  passed: boolean;
  reportSha256: string;
  scenariosRun: number;
  scenariosPassed: number;
}

/** Why a promotion is refused, as codes. The UI renders the codes, never an invented sentence. */
export interface PromotionDecisionView {
  allowed: boolean;
  blockingCodes: string[];
}

/** Compare-and-set state for the live pointer. `pointerVersion` is what makes a rollback safe. */
export interface PointerStateView {
  liveReleaseId: string | null;
  pointerVersion: number;
}

// ============================================================ models and cost

/**
 * Mirrors `InstanceRunGroupDtos.CatalogModelView`.
 *
 * This is the only source of provider and model options. The dashboard never carries a hardcoded
 * list: a model absent from this response is a model the deployment cannot run, usually because
 * its credential is not configured, and offering it would produce a failure at dispatch.
 */
export interface CatalogModelView {
  provider: string;
  model: string;
  contextTokenCeiling: number;
  outputTokenCeiling: number;
  tokenizerStrategy: string;
  inputUsdPerMillion: number;
  cachedInputUsdPerMillion: number;
  outputUsdPerMillion: number;
}

// ============================================================ corpus

/** Mirrors `CorpusCollectionDtos.CollectionSummary`. */
export interface CollectionSummary {
  id: string;
  brainId: string;
  slug: string;
  displayName: string;
  state: string;
  /** Optimistic-concurrency version; membership edits carry the version they expect. */
  version: number;
  /** Set when this collection was cloned copy-on-write from another. */
  clonedFromId: string | null;
  documentCount: number;
}

// ============================================================ run groups

export type RunGroupStatus =
  | "QUEUED" | "PROCESSING" | "SUCCEEDED" | "PARTIAL" | "FAILED" | "CANCELLED";

/**
 * Mirrors `InstanceRunGroupDtos.RunGroupSummaryView`.
 *
 * Note what is *not* here, because the run queue is built around the absence. There is no cost
 * figure and no per-member status breakdown on the summary — both live on the group detail, which
 * decrypts member output and is deliberately a narrower surface than a listing. Showing cost in
 * the queue would mean fetching every group's detail to render one page, so the queue links to
 * detail instead of fanning out into it.
 */
export interface RunGroupSummaryView {
  groupId: string;
  brainId: string;
  mode: string;
  comparisonDimension: string | null;
  status: RunGroupStatus;
  memberCount: number;
  createdAt: string;
  terminalAt: string | null;
  cancellationRequestedAt: string | null;
}

// ============================================================ parsed input

/** Mirrors `ParsedInputDtos.CompatibilityWarning`. Field identity only — never a field value. */
export interface CompatibilityWarning {
  code: string;
  documentOrdinal: number;
  documentTypeCode: string;
  fieldName: string | null;
  groupKey: string | null;
}

/** Mirrors `ParsedInputDtos.Compatibility`. `rejection` is a code, set only when incompatible. */
export interface Compatibility {
  compatible: boolean;
  rejection: string | null;
  supportedDocumentCount: number;
  warnings: CompatibilityWarning[];
}

/** Mirrors `ParsedInputDtos.PinnedParsedInput` — a parse verified against the engine. */
export interface PinnedParsedInput {
  registrationId: string;
  brainId: string;
  instanceSlug: string;
  packageId: string;
  revision: number;
  processingJobId: string;
  parseGeneration: number;
  envelopeVersion: string;
  canonicalizationVersion: string;
  envelopeSha256: string;
  envelopeSizeBytes: number;
  sourceSetSha256: string;
  selectedSourceIds: string[];
  compatibility: Compatibility;
}

/**
 * Mirrors `ParsedInputDtos.UploadedParsedInput`.
 *
 * Digest prefixes and counts, never a filename — an upload's original name is borrower-identifying
 * often enough that the endpoint refuses to echo it. `created` is false when an idempotent replay
 * returned the registration a previous attempt already made.
 *
 * Note what is absent: no revision. The engine has been handed the original and has not
 * necessarily finished parsing it, so an upload is not yet something a run can be pinned to.
 */
export interface UploadedParsedInput {
  registrationId: string;
  packageId: string;
  processingJobId: string;
  engineSourceId: string;
  sourceCount: number;
  duplicateShaPrefixes: string[];
  created: boolean;
}

// ============================================================ preflight and members

export interface MemberRequest {
  instanceSlug: string;
  releaseId: string | null;
  registrationId: string;
  corpusSnapshotId: string | null;
}

export interface CreateRunGroupRequest {
  mode: "INDEPENDENT" | "COMPARISON";
  comparisonDimension: string | null;
  members: MemberRequest[];
}

/** Mirrors `InstanceRunGroupDtos.MemberEstimateView`. Ranges, never a single number. */
export interface MemberEstimateView {
  memberIndex: number;
  instanceSlug: string;
  releaseId: string;
  registrationId: string;
  corpusSnapshotId: string;
  provider: string;
  model: string;
  pricingVersionId: string;
  inputTokensMin: number;
  inputTokensMax: number;
  outputTokensMin: number;
  outputTokensMax: number;
  costUsdMin: number;
  costUsdMax: number;
  /** EXACT when a provider tokenizer priced it; otherwise a conservative range. */
  estimateQuality: string;
}

/** Mirrors `InstanceRunGroupDtos.PreflightView`. Prices a submission without creating anything. */
export interface PreflightView {
  requestSha256: string;
  comparisonBasisSha256: string | null;
  members: MemberEstimateView[];
  reservedMaximumUsd: number;
  committedTodayUsd: number;
  alreadyReservedUsd: number;
  dailyBudgetUsd: number;
  withinBudget: boolean;
  acceptable: boolean;
  /** Why a submission would be refused, as codes. Empty when acceptable. */
  blockingCodes: string[];
}

export interface CreatedRunGroupView {
  groupId: string;
  /** False when an idempotent replay returned the existing group rather than starting work. */
  created: boolean;
  memberRunIds: string[];
}

/**
 * Mirrors `InstanceRunGroupDtos.MemberDetailView`.
 *
 * Every `actual*` field is nullable because the provider may not report that category. They are
 * typed nullable rather than defaulted to zero on purpose: `Unavailable` and `0 tokens` are
 * different facts, and a dashboard that renders them alike turns a missing measurement into a
 * measured absence.
 */
export interface MemberDetailView {
  memberIndex: number;
  runId: string;
  instanceSlug: string;
  releaseId: string;
  registrationId: string;
  corpusSnapshotId: string;
  status: string;
  failureCode: string | null;
  provider: string | null;
  model: string | null;
  pricingVersionId: string | null;
  expectedInputMin: number;
  expectedInputMax: number;
  expectedOutputMin: number;
  expectedOutputMax: number;
  expectedCostUsdMin: number;
  expectedCostUsdMax: number;
  estimateQuality: string | null;
  actualInputTokens: number | null;
  actualCachedTokens: number | null;
  actualOutputTokens: number | null;
  actualTotalTokens: number | null;
  actualCostUsd: number | null;
  /** PENDING | REPORTED | INFERRED | UNAVAILABLE. Decides how the numbers beside it are labelled. */
  usageQuality: string | null;
  createdAt: string;
  terminalAt: string | null;
  /** Decrypted output, present only for a member that succeeded. */
  result: Record<string, unknown> | null;
}

export interface RunGroupDetailView {
  group: RunGroupSummaryView;
  members: MemberDetailView[];
}

/** The statuses from which no further transition happens, so polling can stop. */
export const TERMINAL_GROUP_STATUSES: RunGroupStatus[] =
  ["SUCCEEDED", "PARTIAL", "FAILED", "CANCELLED"];

// ============================================================ corpus snapshots

/** Mirrors `CorpusCollectionDtos.SnapshotCollectionMetadata`. */
export interface SnapshotCollectionMetadata {
  collectionId: string;
  collectionVersion: number;
}

/**
 * Mirrors `CorpusCollectionDtos.SnapshotDetail`.
 *
 * `manifestSha256` is the point of the whole object: it is what makes "the same corpus" a checkable
 * claim rather than a description. Two runs quoting the same manifest digest saw the same documents
 * at the same versions, however the collections were named or edited afterwards.
 */
export interface SnapshotDetail {
  snapshotId: string;
  brainId: string;
  manifestSha256: string;
  collections: SnapshotCollectionMetadata[];
  documents: unknown[];
}

/** One collection at the version the caller expects. A version mismatch fails the freeze. */
export interface SnapshotCollectionRequest {
  collectionId: string;
  expectedVersion: number;
}

export interface CreateSnapshotRequest {
  collections: SnapshotCollectionRequest[];
}

// ============================================================ cancellation

/** Mirrors `InstanceRunGroupDtos.CancellationView`. */
export interface CancellationView {
  cancelledMembers: number;
  /** Members already past the point of being stoppable. Cancelling is not the same as cancelled. */
  stillProcessingMembers: number;
}

// ============================================================ discussion

/**
 * Mirrors `InstanceDiscussionController.TurnView`.
 *
 * A failed turn carries no bodies — nothing was stored for it — so `question` and `answer` may both
 * be null and `failureCode` says why. The panel shows that rather than hiding the turn, because a
 * question that was asked and not answered is a fact the transcript should keep.
 */
export interface TurnView {
  sequenceNumber: number;
  question: string | null;
  answer: string | null;
  status: string;
  failureCode: string | null;
}

/**
 * Mirrors `InstanceDiscussionController.DiscussionView`.
 *
 * `estimatedCostUsd` is null when no turn has been measured, and `usageQuality` is `UNAVAILABLE`
 * when any turn was not: a total that silently omits an unmeasured turn understates the bill, so
 * the quality travels with the number and the UI never presents an incomplete total as complete.
 */
export interface DiscussionView {
  runId: string;
  turns: TurnView[];
  provider: string | null;
  model: string | null;
  usageQuality: string | null;
  estimatedCostUsd: number | null;
}

/** One question. There is deliberately no field here for an input the run was not pinned to. */
export interface AskRequest {
  question: string;
}

// ============================================================ wizard options

/** Mirrors `InstanceWizardOptionsController.OutputSchemaOption`. Both fields pin together. */
export interface OutputSchemaOption {
  schemaId: string;
  sha256: string;
}

/** Mirrors `InstanceWizardOptionsController.ScenarioSetOption`. */
export interface ScenarioSetOption {
  scenarioSetId: string;
  version: number;
  sha256: string;
  /** How many cases the set runs. A two-case gate and a two-hundred-case gate differ. */
  scenarioCount: number;
}

/** Mirrors `InstanceWizardOptionsController.ToolOption`. All four fields form the identity. */
export interface ToolOption {
  name: string;
  version: string;
  inputSchemaSha256: string;
  outputSchemaSha256: string;
}

/**
 * Mirrors `InstanceWizardOptionsController.WizardOptionsView`.
 *
 * Everything a manifest needs that a client cannot derive. The two version strings are compared by
 * equality against the parser's own constants, and the schema digest is taken over the resource's
 * bytes — which is why they are fetched rather than remembered: a remembered digest keeps working
 * until the file changes, then fails with a mismatch that points at nothing.
 */
export interface WizardOptions {
  envelopeVersion: string;
  canonicalizationVersion: string;
  outputSchemas: OutputSchemaOption[];
  scenarioSets: ScenarioSetOption[];
  tools: ToolOption[];
}

// ============================================================ pointer and configuration

/** Mirrors `InstancePointerController.PointerEventView`. Append-only; never rewritten. */
export interface PointerEventView {
  action: "PROMOTE" | "ROLLBACK";
  /** Null only for a first promotion, which has no predecessor. */
  fromReleaseId: string | null;
  toReleaseId: string;
  pointerVersion: number;
  /**
   * Who moved it, where the deployment recorded one.
   *
   * Nullable because rows written before the field existed, and machine movements with no human
   * behind them, genuinely have none. Rendered as "Not recorded" rather than as blank space: a
   * gap in an audit record is a fact about the record, and empty space reads as a movement nobody
   * needed to account for.
   */
  actorId: string | null;
  changeReason: string | null;
  occurredAt: string;
}

/**
 * Mirrors `InstancePointerController.PointerHistoryView`.
 *
 * `pointerVersion` is half of the compare-and-set every promotion carries. An instance that has
 * never been promoted reads as a null release at version zero, which is exactly what a first
 * promotion asserts — so no special case is needed on either side.
 */
export interface PointerHistoryView {
  liveReleaseId: string | null;
  pointerVersion: number;
  /**
   * Whether this deployment permits a move at all.
   *
   * Rides along with the pointer because a client composing a move needs it, the live release and
   * the version together. Without it the only way to learn the switch is off is to compose a move
   * and read `INSTANCE_PROMOTION_DISABLED` off the failure — which means offering an operator an
   * action that was never going to work.
   */
  promotionEnabled: boolean;
  events: PointerEventView[];
}

export interface ConfigurationParsedData {
  envelopeVersion: string;
  canonicalizationVersion: string;
  allowedDocumentTypes: string[];
  requireAnyDocumentTypes: string[];
  minimumSupportedDocuments: number;
  reviewRequired: "WARN" | "REJECT";
  missingFields: "PRESERVE" | "REJECT";
}

export interface ConfigurationModel {
  provider: string;
  model: string;
  fallbackPolicy: "NONE" | "CONFIGURED";
}

export interface ConfigurationLimits {
  maximumInputTokens: number;
  maximumRetrievedTokens: number;
  maximumOutputTokens: number;
  maximumDiscussionTokens: number;
  maximumConcurrentRuns: number;
  maximumExpectedCostUsd: number;
}

/**
 * Mirrors `InstancePointerController.ConfigurationView`.
 *
 * Note what is absent: `behavior`. Prompt text is never served, so re-authoring a release means
 * writing its prompts again. `behaviorPresent` says the contract exists without saying what it
 * contains, which is how a client knows it owes prompts rather than guessing from an absence.
 */
export interface ConfigurationView {
  releaseId: string;
  releaseNumber: number;
  live: boolean;
  manifestVersion: number;
  parsedData: ConfigurationParsedData;
  model: ConfigurationModel;
  corpus: SnapshotCollectionMetadata[];
  tools: ToolOption[];
  output: OutputSchemaOption & { schemaSha256?: string };
  limits: ConfigurationLimits;
  evaluations: { scenarioSetId: string; scenarioSetVersion: number; minimumScore: number };
  behaviorPresent: boolean;
}

/** Mirrors `CorpusCollectionDtos.DocumentMetadata`. Titles and digests, never document content. */
export interface CollectionDocument {
  documentId: string;
  title: string;
  documentVersion: string;
  contentSha256: string;
}

/** Mirrors `CorpusCollectionDtos.CollectionDetail`. */
export interface CollectionDetail extends CollectionSummary {
  documents: CollectionDocument[];
}

/** What a promotion or rollback asserts about the world it believes it is changing. */
export interface PointerMoveRequest {
  expectedLiveReleaseId: string | null;
  expectedPointerVersion: number;
  actorId: string;
  changeReason: string;
}
