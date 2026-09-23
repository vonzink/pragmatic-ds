import {
  BrainAdminDto,
  BrainCreateRequest,
  BrainUpdateRequest,
  ConnectorClient,
  ConnectorClientRequest,
  ConnectorEvent,
  ConnectorTokenResponse,
  BrainProfileDto,
  BrainProfileRequest,
  BrainReadiness,
  FeedbackRating,
  FeedbackRequest,
  LabDiscussionResponse,
  LabDocumentStatusResponse,
  LabEnvelopeResponse,
  LabInstancesResponse,
  LabMessageRequest,
  LabPurgeResponse,
  LabRegistrationResponse,
  LabRunHistoryResponse,
  LabRunRequest,
  LabRunResponse,
  PublicAskRequest,
  PublicAskResponse,
  SourceWeightDto,
  SourceWeightEventDto,
  SyncReport,
  ToolAdapterConfigDto,
  ToolAdapterConfigRequest,
  ToolAdapterRun,
  ToolDefinitionDto,
  ToolDefinitionRequest,
} from "./types";

const KEY_STORAGE = "rag-brain-admin-key";
const DEFAULT_LEGACY_ASK_SLUG = "generic";

export class AuthError extends Error {
  constructor() {
    super("Admin key missing or rejected");
  }
}

/**
 * A failed request.
 *
 * Most of this API answers `{error: "<prose>"}` and `message` carries that prose. The Income Lab
 * routes answer `{code, correlationId, counts}` and nothing else — by design, so that a provider
 * body, engine URI, filename, or parsed value has no member to travel in. For those,
 * {@link code}/{@link correlationId}/{@link counts} carry what the server actually said and
 * `message` is the code itself rather than prose this client made up.
 */
export class ApiError extends Error {
  status: number;
  code: string | null;
  correlationId: string | null;
  counts: Record<string, unknown> | null;

  constructor(
    status: number,
    message: string,
    code: string | null = null,
    correlationId: string | null = null,
    counts: Record<string, unknown> | null = null,
  ) {
    super(message);
    this.status = status;
    this.code = code;
    this.correlationId = correlationId;
    this.counts = counts;
  }
}

export const adminKey = {
  get: () => sessionStorage.getItem(KEY_STORAGE),
  set: (key: string) => sessionStorage.setItem(KEY_STORAGE, key),
  clear: () => sessionStorage.removeItem(KEY_STORAGE),
};

// The dashboard runs under HashRouter, so the active ?brain=<slug> selector lives
// in the hash fragment (e.g. "#/corpus?brain=mortgage&scope=income"), not in
// window.location.search. Every brain-scoped admin/documents call must carry it so
// the whole dashboard — not just the screens that thread it explicitly — targets
// the selected brain instead of falling back to the default (generic) brain.
export function currentBrainSlug(): string | null {
  const hash = typeof window !== "undefined" ? window.location.hash : "";
  const q = hash.indexOf("?");
  if (q === -1) return null;
  const brain = new URLSearchParams(hash.slice(q + 1)).get("brain");
  return brain && brain.trim() ? brain.trim() : null;
}

// Inject the URL's brain into brain-scoped admin/documents endpoints. Never
// overrides an explicit brain= a caller already set, and never touches public
// (/api/ai/public, /api/ai/<slug>/ask) endpoints.
function withBrainFromUrl(path: string): string {
  if (!/^\/api\/ai\/(admin|documents)(\/|$|\?)/.test(path)) return path;
  if (/[?&]brain=/.test(path)) return path;
  const brain = currentBrainSlug();
  if (!brain) return path;
  return `${path}${path.includes("?") ? "&" : "?"}brain=${encodeURIComponent(brain)}`;
}

/**
 * Per-call switches for {@link request}.
 *
 * `injectLegacyBrain` defaults true so every existing caller keeps the sidebar-derived scope it
 * has always had. Instance-control calls set it false: they carry an explicit brain UUID and must
 * not have a slug appended behind them — see {@link instanceControlRequest}.
 */
type RequestOptions = { injectLegacyBrain?: boolean };

async function request<T>(
  path: string,
  init: RequestInit = {},
  options: RequestOptions = {},
): Promise<T> {
  const headers = new Headers(init.headers);
  headers.set("X-Admin-Api-Key", adminKey.get() ?? "");
  const isForm = init.body instanceof FormData;
  if (init.body && !isForm) headers.set("Content-Type", "application/json");
  // FormData uploads carry brain as a form field already; only rewrite non-upload paths.
  const injectLegacyBrain = options.injectLegacyBrain ?? true;
  const target = isForm || !injectLegacyBrain ? path : withBrainFromUrl(path);

  const response = await fetch(target, { ...init, headers });
  if (response.status === 401) {
    adminKey.clear();
    throw new AuthError();
  }
  if (!response.ok) {
    const body = await response.json().catch(() => null) as {
      error?: string;
      code?: string;
      correlationId?: string;
      counts?: Record<string, unknown>;
    } | null;
    throw new ApiError(
      response.status,
      // Prose when the server sent prose; otherwise the code verbatim. Never an invented sentence.
      body?.error ?? body?.code ?? `HTTP ${response.status}`,
      body?.code ?? null,
      body?.correlationId ?? null,
      body?.counts ?? null,
    );
  }
  if (response.status === 204) return undefined as T;
  // Some endpoints (e.g. GET tool-adapters when none is configured) return a null
  // body as an empty 200, not a 204. response.json() on an empty body throws
  // "Unexpected end of JSON input", so treat an unparseable/empty 2xx body as no
  // content (mirrors the error-path .catch below).
  return await response.json().catch(() => undefined) as T;
}

/**
 * A request that resolves its own scope.
 *
 * The only difference from {@link api} is that the HashRouter's `?brain=<slug>` is never appended.
 * Instance-control endpoints live under `/api/ai/admin/instances`, which the legacy injector
 * matches, and they take a brain UUID rather than a slug — but the type mismatch is the lesser
 * problem. The real one is that an instance run pins a brain, a release, a parsed revision and a
 * corpus snapshot at submission, and scope arriving from whatever the sidebar was showing is not
 * pinned by anybody.
 *
 * Exported narrowly rather than exposing `request` itself, so the opt-out is the only new thing
 * callers outside this module can reach.
 */
export function instanceControlRequest<T>(path: string, init: RequestInit = {}): Promise<T> {
  return request<T>(path, init, { injectLegacyBrain: false });
}

export const api = {
  get: <T>(path: string) => request<T>(path),
  post: <T>(path: string, body?: unknown) =>
    request<T>(path, { method: "POST", body: body === undefined ? undefined : JSON.stringify(body) }),
  put: <T>(path: string, body: unknown) =>
    request<T>(path, { method: "PUT", body: JSON.stringify(body) }),
  patch: <T>(path: string, body: unknown) =>
    request<T>(path, { method: "PATCH", body: JSON.stringify(body) }),
  del: <T>(path: string) => request<T>(path, { method: "DELETE" }),
  upload: <T>(path: string, form: FormData) =>
    request<T>(path, { method: "POST", body: form }),

  /**
   * A JSON POST that carries `Idempotency-Key`.
   *
   * The caller owns the key and must generate it once per explicit user action — one Run click,
   * one Send click — and reuse that same value through every network retry of that action. That is
   * the whole point: the backend answers a repeated key with the stored row and starts no second
   * analyzer or provider call. A key minted per attempt would defeat it silently.
   */
  postIdempotent: <T>(path: string, body: unknown, idempotencyKey: string) =>
    request<T>(path, {
      method: "POST",
      body: JSON.stringify(body),
      headers: { "Idempotency-Key": idempotencyKey },
    }),

  /**
   * A multipart POST that carries `Idempotency-Key`.
   *
   * Two things differ from {@link api.postIdempotent} and both matter. First, no JSON
   * `Content-Type` is set, so the browser writes its own multipart boundary. Second, the brain is
   * resolved and appended HERE: `request()` deliberately skips its hash-route brain rewrite for
   * FormData bodies, so without this the upload would silently register against the default brain.
   * It is appended to the query rather than added as a form field so it travels exactly once.
   */
  uploadIdempotent: <T>(path: string, form: FormData, idempotencyKey: string) =>
    request<T>(withBrainFromUrl(path), {
      method: "POST",
      body: form,
      headers: { "Idempotency-Key": idempotencyKey },
    }),
};

// ============================================================ Income Lab prototype
//
// Every route is under /api/ai/admin/**, which AdminApiKeyFilter already gates; nothing here
// widens that. The engine base URL and every engine/provider credential live on the backend, so
// there is nothing in this client that could put one in the browser.

const LAB = "/api/ai/admin/lab";
const LAB_INSTANCE = "income";

export const labApi = {
  instances: () => api.get<LabInstancesResponse>(`${LAB}/instances`),

  /** One registration = one file. Key is generated per selected file and survives retries. */
  register: (form: FormData, idempotencyKey: string) =>
    api.uploadIdempotent<LabRegistrationResponse>(
      `${LAB}/instances/${LAB_INSTANCE}/documents`, form, idempotencyKey),

  documentStatus: (packageId: string, jobId: string) =>
    api.get<LabDocumentStatusResponse>(
      `${LAB}/documents/${encodeURIComponent(packageId)}?jobId=${encodeURIComponent(jobId)}`),

  envelope: (packageId: string, revision: number | null) =>
    api.get<LabEnvelopeResponse>(
      `${LAB}/documents/${encodeURIComponent(packageId)}/envelope${
        revision === null ? "" : `?revision=${revision}`}`),

  startRun: (body: LabRunRequest, idempotencyKey: string) =>
    api.postIdempotent<LabRunResponse>(
      `${LAB}/instances/${LAB_INSTANCE}/runs`, body, idempotencyKey),

  history: () => api.get<LabRunHistoryResponse>(`${LAB}/runs?instance=${LAB_INSTANCE}`),

  run: (runId: string) => api.get<LabRunResponse>(`${LAB}/runs/${encodeURIComponent(runId)}`),

  /** Idempotent purge of Lab-owned rows. It never deletes the Document Engine package. */
  purgeRun: (runId: string) =>
    api.del<LabPurgeResponse>(`${LAB}/runs/${encodeURIComponent(runId)}`),

  discussion: (runId: string) =>
    api.get<LabDiscussionResponse>(`${LAB}/runs/${encodeURIComponent(runId)}/messages`),

  postMessage: (runId: string, body: LabMessageRequest, idempotencyKey: string) =>
    api.postIdempotent<LabDiscussionResponse>(
      `${LAB}/runs/${encodeURIComponent(runId)}/messages`, body, idempotencyKey),
};

export function legacyAskPath(slug: string) {
  const routeSlug = import.meta.env.VITE_LEGACY_ASK_SLUG || DEFAULT_LEGACY_ASK_SLUG;
  return `/api/ai/${encodeURIComponent(routeSlug)}/ask?brain=${encodeURIComponent(slug)}`;
}

export const brainsApi = {
  list: () => api.get<BrainAdminDto[]>("/api/ai/admin/brains"),
  create: (body: BrainCreateRequest) => api.post<BrainAdminDto>("/api/ai/admin/brains", body),
  update: (id: string, body: BrainUpdateRequest) =>
    api.put<BrainAdminDto>(`/api/ai/admin/brains/${id}`, body),
  activate: (id: string) => api.post<BrainAdminDto>(`/api/ai/admin/brains/${id}/activate`),
  sync: (id: string, dryRun: boolean) =>
    api.post<SyncReport>(`/api/ai/admin/brains/${id}/sync?dryRun=${dryRun}`),
  remove: (id: string) => api.del<BrainAdminDto>(`/api/ai/admin/brains/${id}`),
  restore: (id: string) => api.post<BrainAdminDto>(`/api/ai/admin/brains/${id}/restore`),
  readiness: (id: string) => api.get<BrainReadiness>(`/api/ai/admin/brains/${id}/readiness`),
};

export const learningApi = {
  toggle: (brainId: string, enabled: boolean) =>
    api.post<BrainAdminDto>(`/api/ai/admin/brains/${brainId}/learning?enabled=${enabled}`),
  pending: (brainId: string) =>
    api.get<SourceWeightEventDto[]>(`/api/ai/admin/learning/pending?brain=${encodeURIComponent(brainId)}`),
  approve: (eventId: string, brainSlug: string) =>
    api.post<{ approved: boolean; eventId: string }>(
      `/api/ai/admin/learning/approve/${eventId}?brain=${encodeURIComponent(brainSlug)}`),
  reject: (eventId: string, brainSlug: string) =>
    api.post<{ rejected: boolean; eventId: string }>(
      `/api/ai/admin/learning/reject/${eventId}?brain=${encodeURIComponent(brainSlug)}`),
  reset: (brainId: string) =>
    api.post<{ reset: boolean; brainId: string }>(`/api/ai/admin/learning/reset?brain=${encodeURIComponent(brainId)}`),
  weights: (brainId: string) =>
    api.get<SourceWeightDto[]>(`/api/ai/admin/learning/weights?brain=${encodeURIComponent(brainId)}`),
  submitFeedback: async (slug: string, token: string, sessionId: string, body: FeedbackRequest): Promise<void> => {
    const headers = new Headers();
    headers.set("X-Public-Brain-Token", token);
    headers.set("X-Session-Id", sessionId);
    headers.set("Content-Type", "application/json");
    const response = await fetch(`/api/ai/public/${slug}/feedback`, {
      method: "POST",
      headers,
      body: JSON.stringify(body),
    });
    if (!response.ok) {
      const data = await response.json().catch(() => null) as { error?: string } | null;
      throw new Error(data?.error || `HTTP ${response.status}`);
    }
    // Backend returns 204 No Content (empty body) on success; nothing to parse.
    if (response.status === 204) return;
  },
  // Admin-key authed rating on a trace (AdminTraceFeedbackController). Used by the
  // dashboard's "Full ask" tester mode, which has no public brain token to send.
  submitAdminFeedback: (traceId: string, rating: FeedbackRating, reason?: string | null) =>
    api.post<void>(`/api/ai/admin/traces/${traceId}/feedback`, { rating, reason: reason ?? null }),
};

export const profileApi = {
  get: (brainId: string) => api.get<BrainProfileDto>(`/api/ai/admin/brains/${brainId}/profile`),
  update: (brainId: string, body: BrainProfileRequest) =>
    api.put<BrainProfileDto>(`/api/ai/admin/brains/${brainId}/profile`, body),
  rotatePublicToken: (brainId: string) =>
    api.post<{ token: string }>(`/api/ai/admin/brains/${brainId}/profile/public-token`, {}),
};

export const connectorsApi = {
  list: () => api.get<ConnectorClient[]>("/api/ai/admin/connectors"),
  create: (body: ConnectorClientRequest) => api.post<ConnectorClient>("/api/ai/admin/connectors", body),
  update: (id: string, body: ConnectorClientRequest) =>
    api.put<ConnectorClient>(`/api/ai/admin/connectors/${id}`, body),
  rotateToken: (id: string) =>
    api.post<ConnectorTokenResponse>(`/api/ai/admin/connectors/${id}/token`, {}),
  enable: (id: string) => api.post<ConnectorClient>(`/api/ai/admin/connectors/${id}/enable`, {}),
  disable: (id: string) => api.post<ConnectorClient>(`/api/ai/admin/connectors/${id}/disable`, {}),
  events: (id: string) => api.get<ConnectorEvent[]>(`/api/ai/admin/connectors/${id}/events`),
};

function brainQuery(slug: string) {
  return `brain=${encodeURIComponent(slug)}`;
}

export const toolDefinitionsApi = {
  list: (slug: string) =>
    api.get<ToolDefinitionDto[]>(`/api/ai/admin/tool-definitions?${brainQuery(slug)}`),
  create: (slug: string, body: ToolDefinitionRequest) =>
    api.post<ToolDefinitionDto>(`/api/ai/admin/tool-definitions?${brainQuery(slug)}`, body),
  update: (slug: string, id: string, body: ToolDefinitionRequest) =>
    api.patch<ToolDefinitionDto>(`/api/ai/admin/tool-definitions/${id}?${brainQuery(slug)}`, body),
  activate: (slug: string, id: string) =>
    api.post<ToolDefinitionDto>(`/api/ai/admin/tool-definitions/${id}/activate?${brainQuery(slug)}`, {}),
  deactivate: (slug: string, id: string) =>
    api.post<ToolDefinitionDto>(`/api/ai/admin/tool-definitions/${id}/deactivate?${brainQuery(slug)}`, {}),
  remove: (slug: string, id: string) =>
    api.del<{ deleted: boolean; id: string }>(`/api/ai/admin/tool-definitions/${id}?${brainQuery(slug)}`),
};

export const toolAdaptersApi = {
  get: (slug: string, toolName: string) =>
    api.get<ToolAdapterConfigDto | null>(
      `/api/ai/admin/tool-adapters/${encodeURIComponent(toolName)}?${brainQuery(slug)}`,
    ),
  upsert: (slug: string, toolName: string, body: ToolAdapterConfigRequest) =>
    api.put<ToolAdapterConfigDto>(
      `/api/ai/admin/tool-adapters/${encodeURIComponent(toolName)}?${brainQuery(slug)}`,
      body,
    ),
  remove: (slug: string, toolName: string) =>
    api.del<{ deleted: boolean; toolName: string }>(
      `/api/ai/admin/tool-adapters/${encodeURIComponent(toolName)}?${brainQuery(slug)}`,
    ),
  runs: (slug: string, toolName: string) =>
    api.get<ToolAdapterRun[]>(
      `/api/ai/admin/tool-adapters/${encodeURIComponent(toolName)}/runs?${brainQuery(slug)}`,
    ),
};

export async function publicAsk(slug: string, token: string, body: PublicAskRequest) {
  const headers = new Headers();
  headers.set("X-Public-Brain-Token", token);
  headers.set("Content-Type", "application/json");
  const response = await fetch(`/api/ai/public/${slug}/ask`, {
    method: "POST",
    headers,
    body: JSON.stringify(body),
  });
  if (!response.ok) {
    const data = await response.json().catch(() => null) as { error?: string } | null;
    throw new Error(data?.error || `HTTP ${response.status}`);
  }
  return response.json() as Promise<PublicAskResponse>;
}
