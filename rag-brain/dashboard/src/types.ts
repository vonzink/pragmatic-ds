export interface Stats {
  brain: { id: string; companyName: string; slug: string };
  corpus: { activeDocuments: number; totalDocuments: number; chunks: number };
}

export interface BrainProfileDto {
  brainId: string;
  mode: "PUBLIC_SITE" | "PRIVATE_SITE" | "SECURE_DEPLOYMENT";
  purpose: string;
  audience: string;
  personality: string;
  tone: string;
  expertiseLevel: string;
  answerLength: string;
  confidenceTarget: number;
  /** Per-brain floor on the top retrieval score before the model runs; null = global default. */
  retrievalConfidenceThreshold: number | null;
  /** Per-brain floor on the model's self-reported confidence after it answers; null = no gate. */
  answerConfidenceFloor: number | null;
  clarificationPolicy: string;
  escalationPolicy: string;
  citationPolicy: string;
  ctaPolicy: string;
  disclaimer: string;
  publicEnabled: boolean;
  allowedDomains: string[];
}

export type BrainProfileRequest = Omit<BrainProfileDto, "brainId">;

export interface PublicAskRequest {
  conversationId?: string | null;
  sessionId: string;
  message: string;
  pageRoute: string | null;
  surface: "PUBLIC";
  facts: Record<string, unknown>;
}

export interface PublicRecommendedPage {
  label: string;
  url: string;
  reason: string;
}

export interface PublicAskResponse {
  responseType: "ANSWER" | "CLARIFY" | "NAVIGATE" | "ESCALATE";
  message: string | null;
  answer: string | null;
  clarifyingQuestion: string | null;
  missingFacts: string[];
  citations: Citation[];
  recommendedPages: PublicRecommendedPage[];
  confidence: number;
  nextAction: string | null;
  conversationId: string | null;
  disclaimer: string | null;
  humanEscalationRequired: boolean;
  traceId: string | null;
}

export interface ConnectChecklistItem {
  key: string;
  label: string;
  ok: boolean;
  hint: string | null;
}

export interface BrainReadiness {
  brainId: string;
  slug: string;
  displayName: string;
  active: boolean;
  isDefault: boolean;
  chunks: number;
  activeDocuments: number;
  publicEnabled: boolean;
  mode: string;
  hasPublicToken: boolean;
  allowedDomains: string[];
  ready: boolean;
  checklist: ConnectChecklistItem[];
}

export interface ConnectorClient {
  id: string;
  name: string;
  type: "MCP_AGENT" | "PEER_BRAIN" | "SERVER_API" | "INTERNAL_APP";
  brainId: string | null;
  scopes: string[];
  allowedOrigins: string[];
  allowedPeerHosts: string[];
  allowedTenants: string[];
  grantedPermissions: string[];
  enabled: boolean;
  hasToken: boolean;
  lastUsedAt: string | null;
  createdAt: string | null;
  updatedAt: string | null;
}

export interface ConnectorClientRequest {
  name: string;
  type: ConnectorClient["type"];
  brainId: string | null;
  scopes: string[];
  allowedOrigins: string[];
  allowedPeerHosts: string[];
  allowedTenants: string[];
  grantedPermissions: string[];
  enabled: boolean;
}

export interface ConnectorTokenResponse {
  token: string;
  client: ConnectorClient;
}

export interface ConnectorEvent {
  id: string;
  connectorClientId: string | null;
  brainId: string | null;
  eventType: string;
  scope: string | null;
  requestHost: string | null;
  requestId: string | null;
  status: string;
  createdAt: string | null;
}

export interface DocumentDto {
  id: string; title: string; sourceName: string; sourceType: string;
  visibility: "PUBLIC" | "INTERNAL" | "SECURE";
  trustLevel: "AUTHORITATIVE" | "APPROVED" | "REFERENCE" | "EXPERIMENTAL" | "BLOCKED";
  fileName: string; analyzerScope: string | null; documentVersion: string | null;
  effectiveDate: string | null; expirationDate: string | null; active: boolean;
}

export interface SyncResult {
  fileName: string; action: string; reason: string | null;
  executed: boolean; succeeded: boolean; error: string | null;
}
export interface SyncReport { dryRun: boolean; summary: Record<string, number>; results: SyncResult[] }

export interface DocumentQuality {
  documentId: string | null;
  title: string;
  fileName: string;
  active: boolean;
  chunkCount: number;
  embeddedChunkCount: number;
  chunksMissingEmbeddingCount: number;
  parentChunkCount: number;
  childChunkCount: number;
  orphanChildChunkCount: number;
  emptyChunkCount: number;
  chunksMissingCitationMetadata: number;
  warnings: string[];
}

export interface IngestionQuality {
  brainId: string;
  documentCount: number;
  activeDocumentCount: number;
  chunkCount: number;
  embeddedChunkCount: number;
  chunksMissingEmbeddingCount: number;
  parentChunkCount: number;
  childChunkCount: number;
  orphanChildChunkCount: number;
  emptyChunkCount: number;
  duplicateChunkTextGroups: number;
  chunksMissingCitationMetadata: number;
  documents: DocumentQuality[];
  warnings: string[];
}

export interface SettingsResponse {
  effective: Record<string, string | number | boolean | null>;
  overrides: Record<string, string>;
  providers: { name: string; configured: boolean }[];
}

export interface Citation {
  source_name: string | null; document_name: string | null;
  section: string | null; page_number: string | null; effective_date: string | null;
}
export interface AskResponse {
  conversationId: string; answer: string; citations: Citation[];
  confidence: number; humanEscalationRequired: boolean; disclaimer: string;
  recommendedPage?: RecommendedPage | null;
  links?: Link[] | null;
  nextAction?: string | null;
  traceId?: string | null;
}

export interface RecommendedPage { route: string; label: string }
export interface Link { name: string; url: string; authority: string }

export interface RetrievedChunk {
  content: string; sourceName: string; documentName: string;
  section: string | null; pageNumber: number | null; combinedScore: number;
}
export interface RetrievalResult { chunks: RetrievedChunk[]; confidence: number; sufficientEvidence: boolean }

export interface AuditRow {
  id: string; createdAt: string; question: string; confidence: number | null;
  modelProvider: string | null; modelName: string | null;
  fallbackUsed: boolean; escalated: boolean;
}
export interface AuditPage { items: AuditRow[]; page: number; size: number; total: number }
export interface AuditDetail extends AuditRow {
  rewrittenQuestion: string | null; answer: string | null;
  sources: Record<string, unknown>[];
}

export interface RuleState {
  key: string; content: string; source: "pack" | "custom";
  updatedAt: string | null; updatedBy: string | null;
}
export interface RulesResponse { hard: RuleState; guidance: RuleState }
export interface RuleRevisionDto {
  revision: number; createdAt: string; createdBy: string;
  reverted: boolean; content: string | null;
}

export interface AnalyzerPromptPublished {
  content: string; source: "pack" | "custom";
  updatedAt: string | null; updatedBy: string | null;
}
export interface AnalyzerPromptDraft { content: string; savedAt: string; savedBy: string }
export interface AnalyzerPromptState {
  slug: string; displayName: string; corpusScope: string | null;
  retrievalQueryTemplate: string | null; retrievalTopK: number;
  envelope: string; v2: boolean; packDefault: string;
  published: AnalyzerPromptPublished; draft: AnalyzerPromptDraft | null;
}
export interface AnalyzerPromptRevisionDto {
  revision: number; createdAt: string; createdBy: string;
  reverted: boolean; content: string | null;
}
export interface PromptSectionDto {
  id: string; title: string; text: string; kind: "BASE_PROMPT" | "STATIC" | "DYNAMIC";
}
export interface AnalyzerAssemblyDto { source: "draft" | "published"; sections: PromptSectionDto[] }

export interface VocabState {
  content: string; source: "pack" | "custom";
  updatedAt: string | null; updatedBy: string | null; entries: number;
}
export interface VocabRevisionDto {
  revision: number; createdAt: string; createdBy: string;
  reverted: boolean; content: string | null;
}
export interface DocumentUpdate {
  title: string; sourceName: string; sourceType: string;
  visibility: "PUBLIC" | "INTERNAL" | "SECURE";
  trustLevel: "AUTHORITATIVE" | "APPROVED" | "REFERENCE" | "EXPERIMENTAL" | "BLOCKED";
  documentVersion: string | null;
  effectiveDate: string | null; expirationDate: string | null;
}

export interface SourceLinkDto {
  id: string;
  name: string;
  url: string;
  domain: string | null;
  authority: string;
  topics: string[];
  freshness_required: boolean;
  allowed_use: string[];
  do_not_use_for: string[];
  surface: string;
  active: boolean;
  created_at: string | null;
  created_by: string | null;
  updated_at: string | null;
  updated_by: string | null;
}

export interface SourceLinkRequest {
  name: string;
  url: string;
  domain: string | null;
  authority: string;
  topics: string[];
  freshnessRequired: boolean;
  allowedUse: string[];
  doNotUseFor: string[];
  surface: string;
}

export interface LinkRef {
  label: string;
  url: string;
}

export interface PageGuideDto {
  id: string;
  route: string | null;
  title: string;
  purpose: string;
  surface: string;
  user_intents: string[];
  allowed_guidance: string[];
  internal_links: LinkRef[];
  source_link_ids: string[];
  topics: string[];
  active: boolean;
  created_at: string | null;
  created_by: string | null;
  updated_at: string | null;
  updated_by: string | null;
}

export interface PageGuideRequest {
  route: string | null;
  title: string;
  purpose: string;
  surface: string;
  userIntents: string[];
  allowedGuidance: string[];
  internalLinks: LinkRef[];
  sourceLinkIds: string[];
  topics: string[];
}

export interface BrainAdminDto {
  id: string;
  slug: string;
  displayName: string;
  packRef: string | null;
  sourceType: string | null;       // "local" | "s3" | null
  s3Bucket: string | null;
  s3Prefix: string | null;
  s3Region: string | null;
  localPath: string | null;
  answerProvider: string | null;
  answerModel: string | null;
  utilityProvider: string | null;
  utilityModel: string | null;
  isDefault: boolean;
  isActive: boolean;
  learningEnabled: boolean;
  dailyCostBudgetUsd: number | null;
}

export interface BrainCreateRequest {
  slug: string;
  displayName: string;
  packRef?: string;          // omitted when generating a starter pack
  disclaimer?: string;       // only used when generating
  sourceType: "local" | "s3";
  s3Bucket: string | null;
  s3Prefix: string | null;
  s3Region: string | null;
  localPath: string | null;
  answerProvider: string;
  answerModel: string;
  utilityProvider: string;
  utilityModel: string;
}

/**
 * PUT payload for editing a brain. packRef is mandatory (the backend
 * re-validates it), and dailyCostBudgetUsd must always round-trip the
 * current value — the backend sets whatever is in the payload, so omitting
 * it would silently clear an existing budget override.
 */
export interface BrainUpdateRequest {
  slug: string;
  displayName: string;
  packRef: string;
  sourceType: "local" | "s3";
  s3Bucket: string | null;
  s3Prefix: string | null;
  s3Region: string | null;
  localPath: string | null;
  answerProvider: string;
  answerModel: string;
  utilityProvider: string;
  utilityModel: string;
  dailyCostBudgetUsd: number | null;
}

export type ToolMode = "READ" | "WRITE" | "NAVIGATE";
export type ToolHttpMethod = "GET" | "POST" | "PUT" | "PATCH" | "DELETE";
export type ToolAuthMode = "NONE" | "BEARER_TOKEN" | "API_KEY_HEADER";

export interface ToolDefinitionDto {
  id: string;
  brainId: string;
  name: string;
  description: string;
  mode: ToolMode;
  confirmationRequired: boolean;
  requiredPermissions: string[];
  inputSchema: Record<string, unknown>;
  active: boolean;
  createdAt: string | null;
  updatedAt: string | null;
}

export interface ToolDefinitionRequest {
  name: string;
  description: string;
  mode: ToolMode;
  confirmationRequired: boolean;
  requiredPermissions: string[];
  inputSchema: Record<string, unknown>;
}

export interface ToolAdapterConfigDto {
  id: string;
  brainId: string;
  toolName: string;
  enabled: boolean;
  httpMethod: ToolHttpMethod;
  urlTemplate: string;
  authMode: ToolAuthMode;
  secretRef: string | null;
  apiKeyHeader: string | null;
  staticHeaders: Record<string, unknown>;
  requestBodyTemplate: Record<string, unknown>;
  timeoutMs: number;
  allowedHosts: string[];
  createdAt: string | null;
  updatedAt: string | null;
}

export interface ToolAdapterConfigRequest {
  enabled: boolean;
  httpMethod: ToolHttpMethod;
  urlTemplate: string;
  authMode: ToolAuthMode;
  secretRef: string;
  apiKeyHeader: string;
  staticHeaders: Record<string, unknown>;
  requestBodyTemplate: Record<string, unknown>;
  timeoutMs: number;
  allowedHosts: string[];
}

export interface ToolAdapterRun {
  id: string;
  toolName: string;
  mode: string;
  targetHost: string | null;
  httpMethod: string;
  status: string;
  httpStatusCode: number | null;
  durationMs: number;
  sessionId: string | null;
  userId: string | null;
  tenantId: string | null;
  errorType: string | null;
  createdAt: string | null;
}

export type FeedbackRating = "UP" | "DOWN";

export interface FeedbackRequest {
  traceId: string;
  rating: FeedbackRating;
  reason: string | null;
}

export interface SourceWeightDto {
  brainId: string;
  documentId: string;
  weight: number;
  feedbackCount: number;
  updatedAt: string | null;
  updatedBy: string;
}

export interface SourceWeightEventDto {
  id: string;
  brainId: string;
  documentId: string;
  oldWeight: number | null;
  newWeight: number | null;
  proposedWeight: number | null;
  evidenceCount: number;
  status: "APPLIED" | "PENDING" | "APPROVED" | "REJECTED" | "REVERTED";
  reason: string | null;
  actor: string;
  createdAt: string | null;
}

// ============================================================ Income Lab prototype
//
// These mirror com.pragmaticds.rag.lab.web.LabDtos exactly. Two rules travel with them:
//
//  1. Every normalized financial decimal is a STRING here because it is a string on the wire
//     (BigDecimal.toPlainString()). Typing one as `number` would invite JSON.parse to re-read it
//     as an IEEE-754 double and silently drop digits, which for a parsed income figure is a wrong
//     answer rather than a rounding detail. Coordinates and bounded confidences stay `number`:
//     those are geometry and ratios, not money.
//  2. There is no member for an engine URL, storage key, credential, or uploaded filename,
//     because the backend has none either. Adding one here would be adding it to the contract.

/** The declared prototype limitation code and the dependencies that are still live. */
export interface LabPrototype {
  code: string;
  liveDependencies: string[];
}

export interface LabInstance {
  slug: string;
  analyzerSlug: string;
  productionReleaseId: string;
  productionReleaseNumber: number;
  productionManifestSha256: string;
  driftDetected: boolean;
  candidateReleaseId: string | null;
  candidateManifestSha256: string | null;
  prototype: LabPrototype;
}

export interface LabInstancesResponse {
  instances: LabInstance[];
  prototype: LabPrototype;
}

export interface LabRegistrationResponse {
  registrationId: string;
  packageId: string;
  jobId: string;
  sourceId: string;
  sourceCount: number;
  duplicateShaPrefixes: string[];
  created: boolean;
  prototype: LabPrototype;
}

export interface LabRevision {
  revision: number;
  processingJobId: string;
  parseGeneration: number;
  envelopeSchemaVersion: string;
  envelopeSha256: string;
  envelopeSizeBytes: number;
  sourceSetSha256: string;
  reuseEligibility: string;
  createdAt: string;
}

export interface LabDocumentStatusResponse {
  registrationId: string;
  packageId: string;
  jobId: string;
  status: string;
  currentStage: string | null;
  analyzable: boolean;
  warnings: string[];
  revisions: LabRevision[];
  prototype: LabPrototype;
}

/** An evidence box in page points. Geometry, so it stays numeric. */
export interface LabBox {
  x: number;
  y: number;
  width: number;
  height: number;
}

export interface LabEvidence {
  pageId: string;
  role: string;
  ordinal: number;
  box: LabBox | null;
}

export interface LabField {
  name: string;
  /** The engine's occurrence row letter, or null. See lab/parseEnvelope.ts on what null means. */
  groupKey: string | null;
  status: "FOUND" | "MISSING";
  dataType: string | null;
  displayedText: string | null;
  rawValue: string | null;
  normalizedText: string | null;
  /** Canonical decimal STRING, never a JSON number. */
  normalizedNumber: string | null;
  normalizedDate: string | null;
  confidence: number | null;
  method: string | null;
  extractorVersion: string | null;
  validationStatus: string | null;
  sensitive: boolean;
  evidence: LabEvidence[];
  reviewState: "UNREVIEWED_SOURCE" | "MACHINE" | "CORRECTED" | "REJECTED";
}

export interface LabDocument {
  id: string;
  documentTypeCode: string | null;
  ordinal: number;
  pageIds: string[];
  fields: LabField[];
}

export interface LabPage {
  id: string;
  packagePageIndex: number;
  sourcePageIndex: number;
  widthPt: number;
  heightPt: number;
  rotation: number;
  textLayer: string | null;
  blank: boolean;
  duplicate: boolean;
  documentTypeCode: string | null;
  classificationConfidence: number | null;
  classificationMethod: string | null;
}

/** A compatibility warning, carrying field identity but never a field value. */
export interface LabCompatibilityWarning {
  code: string;
  documentOrdinal: number;
  documentTypeCode: string | null;
  fieldName: string | null;
  groupKey: string | null;
}

export interface LabEnvelopeResponse {
  registrationId: string;
  packageId: string;
  revision: number;
  parseGeneration: number;
  processingJobId: string;
  envelopeVersion: string;
  canonicalizationVersion: string;
  envelopeSha256: string;
  envelopeSizeBytes: number;
  sourceSetSha256: string;
  reuseEligibility: string;
  compatible: boolean;
  rejection: string | null;
  warnings: LabCompatibilityWarning[];
  pages: LabPage[];
  documents: LabDocument[];
  unassignedPageIds: string[];
  prototype: LabPrototype;
}

export interface LabRunRequest {
  packageId: string;
  revision: number | null;
}

export interface LabRunSource {
  packageId: string;
  revision: number;
  parseGeneration: number;
  processingJobId: string;
  envelopeVersion: string;
  canonicalizationVersion: string;
  envelopeSha256: string;
  envelopeSizeBytes: number;
  sourceSetSha256: string;
  reuseEligibility: string;
  documentCount: number;
  pageCount: number;
}

export type LabRunStatus = "PROCESSING" | "SUCCEEDED" | "FAILED" | "INTERRUPTED";

export interface LabRunSummary {
  runId: string;
  instanceSlug: string;
  status: LabRunStatus;
  failureCode: string | null;
  releaseId: string;
  analysisRunId: string | null;
  packageId: string | null;
  revision: number | null;
  createdAt: string | null;
  terminalAt: string | null;
  discussionExchangeCount: number;
  prototype: LabPrototype;
}

export interface LabRunHistoryResponse {
  runs: LabRunSummary[];
  prototype: LabPrototype;
}

export interface LabCitation {
  sourceName: string | null;
  documentName: string | null;
  section: string | null;
  pageNumber: string | null;
  effectiveDate: string | null;
}

export interface LabRunAnalysis {
  status: string;
  reportMarkdown: string | null;
  findingsJson: string | null;
  citations: LabCitation[];
  provider: string | null;
  model: string | null;
  inputTokens: number;
  outputTokens: number;
  costUsd: number;
  providerAttempts: number;
  reason: string | null;
}

export interface LabRunReviewSnapshot {
  fieldsSha256: string;
  documentCount: number;
  machineCount: number;
  correctedCount: number;
  rejectedCount: number;
}

export interface LabRunResponse {
  runId: string;
  instanceSlug: string;
  status: LabRunStatus;
  failureCode: string | null;
  releaseId: string;
  releaseNumber: number;
  releaseManifestSha256: string;
  analysisRunId: string | null;
  registrationId: string | null;
  source: LabRunSource | null;
  analysis: LabRunAnalysis | null;
  reviewSnapshot: LabRunReviewSnapshot | null;
  createdAt: string | null;
  terminalAt: string | null;
  replayed: boolean;
  prototype: LabPrototype;
}

export interface LabMessageRequest {
  question: string;
}

export interface LabDiscussionMessage {
  role: string;
  ordinal: number;
  body: string;
  createdAt: string | null;
}

export interface LabDiscussionExchange {
  exchangeId: string;
  sequenceNumber: number;
  status: string;
  failureCode: string | null;
  messages: LabDiscussionMessage[];
  prototypeLimitations: string | null;
  createdAt: string | null;
  terminalAt: string | null;
}

export interface LabReviewDrift {
  changed: boolean;
  machineBefore: number; machineNow: number;
  correctedBefore: number; correctedNow: number;
  rejectedBefore: number; rejectedNow: number;
}

export interface LabDiscussionResponse {
  runId: string;
  exchanges: LabDiscussionExchange[];
  replayed: boolean;
  reviewDrift: LabReviewDrift | null;
  prototype: LabPrototype;
}

export interface LabPurgeResponse {
  runId: string;
  deleted: boolean;
  messagesDeleted: number;
  exchangesDeleted: number;
  payloadsDeleted: number;
  documentsDeleted: number;
  analysisRunDeleted: boolean;
  enginePackageRetained: boolean;
  prototype: LabPrototype;
}
