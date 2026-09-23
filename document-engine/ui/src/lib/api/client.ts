/**
 * The typed client. One `fetch` call site, one error type, one place that knows
 * the engine's RFC 9457 envelope.
 *
 * The single rule this file exists to enforce: **a component never sees a raw
 * failure.** Every non-2xx and every transport failure becomes an
 * {@link ApiError} carrying a stable `code`, because that is what
 * `GlobalExceptionHandler` promises is safe to show a human — an exception
 * message may quote document content, so the server never sends one and the UI
 * must never invent one.
 */

import { API_BASE } from './config.ts';
import type {
  DocumentDecisionResult,
  DocumentFieldsView,
  FieldCorrectionRequest,
  FieldCorrectionResult,
  JobResponse,
  PackageDocumentsView,
  PackageExportView,
  PackagePagesView,
  PackageSummary,
  PackageUsageView,
  PageVerdict,
  ProblemDetail,
  RegroupRequest,
  UploadResult,
  Uuid,
} from './types.ts';

/**
 * A failed API call, reduced to what is safe and useful to display.
 *
 * `code` is always populated. When the body carried one it is the server's
 * `ErrorCode` name; otherwise it is derived from the HTTP status so the UI has
 * something stable to key on either way.
 */
export class ApiError extends Error {
  /** HTTP status, or 0 when the request never reached the server. */
  readonly status: number;
  /** Stable `ErrorCode` name, or a synthetic one. Safe to display. */
  readonly code: string;
  /** RFC 9457 `type` URI, when present. */
  readonly problemType: string | undefined;
  /** Non-sensitive parameters (sizes, counts) by the server's params contract. */
  readonly params: Record<string, unknown> | undefined;

  constructor(init: {
    status: number;
    code: string;
    problemType?: string;
    params?: Record<string, unknown>;
  }) {
    super(`${init.code} (HTTP ${String(init.status)})`);
    this.name = 'ApiError';
    this.status = init.status;
    this.code = init.code;
    this.problemType = init.problemType;
    this.params = init.params;
  }
}

/** The request never reached the server: DNS, offline, CORS, abort. */
export const CODE_NETWORK = 'NETWORK';

/**
 * Derives a code from the status alone. Mirrors the server's own fallback in
 * `GlobalExceptionHandler.handleFrameworkStatus` so a body-less 4xx and a
 * body-carrying one read the same way.
 */
function codeForStatus(status: number): string {
  if (status === 404) return 'NOT_FOUND';
  if (status === 409) return 'CONFLICT';
  if (status === 413) return 'FILE_TOO_LARGE';
  if (status >= 400 && status < 500) return 'INVALID_REQUEST';
  return 'INTERNAL';
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

async function toApiError(response: Response): Promise<ApiError> {
  let problem: ProblemDetail | undefined;
  try {
    const body: unknown = await response.json();
    if (isRecord(body)) problem = body as ProblemDetail;
  } catch {
    // A non-JSON error body (a proxy's HTML 502, say) is not a contract
    // violation worth surfacing — the status still tells us what happened.
    problem = undefined;
  }

  return new ApiError({
    status: response.status,
    // `title` is the same ErrorCode name as `code` on our server; it is only a
    // fallback for a proxy that rewrites the body.
    code: problem?.code ?? problem?.title ?? codeForStatus(response.status),
    problemType: problem?.type,
    params: isRecord(problem?.params) ? problem.params : undefined,
  });
}

/** Joins the configured base with a path, tolerating a trailing slash on either. */
export function apiUrl(path: string): string {
  const base = API_BASE.endsWith('/') ? API_BASE.slice(0, -1) : API_BASE;
  return path.startsWith('/') ? `${base}${path}` : `${base}/${path}`;
}

/**
 * The one `fetch` call site. Everything a caller must never see — a transport
 * failure, a non-2xx — is turned into an {@link ApiError} here; a caller gets a
 * healthy `Response` or nothing.
 */
async function send(path: string, init?: RequestInit): Promise<Response> {
  let response: Response;
  try {
    response = await fetch(apiUrl(path), init);
  } catch (cause) {
    // Includes AbortError. Callers that abort deliberately check `signal.aborted`
    // rather than pattern-matching on this.
    throw new ApiError({ status: 0, code: CODE_NETWORK, params: { cause: String(cause) } });
  }

  if (!response.ok) throw await toApiError(response);
  return response;
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await send(path, init);
  return (await response.json()) as T;
}

/**
 * For an endpoint that answers 200 with no body — parsing it as JSON would
 * throw on the empty stream, so it must not be parsed at all.
 */
async function requestNoContent(path: string, init?: RequestInit): Promise<void> {
  await send(path, init);
}

// ---------------------------------------------------------------------------
// Endpoints. One function per spec path, named for what a reviewer is doing.
// ---------------------------------------------------------------------------

/** `POST /v1/packages` — multipart, part name `files`. */
export async function uploadPackage(
  files: readonly File[],
  options: { loanId?: string; name?: string; idempotencyKey?: string; signal?: AbortSignal } = {},
): Promise<UploadResult> {
  const form = new FormData();
  for (const file of files) form.append('files', file, file.name);

  const query = new URLSearchParams();
  if (options.loanId) query.set('loanId', options.loanId);
  if (options.name) query.set('name', options.name);
  const suffix = query.size > 0 ? `?${query.toString()}` : '';

  const headers: Record<string, string> = {};
  if (options.idempotencyKey) headers['Idempotency-Key'] = options.idempotencyKey;

  // No Content-Type header: the browser must set the multipart boundary itself.
  return request<UploadResult>(`/packages${suffix}`, {
    method: 'POST',
    body: form,
    headers,
    signal: options.signal,
  });
}

/** `GET /v1/packages/{id}`. */
export function getPackage(packageId: Uuid, signal?: AbortSignal): Promise<PackageSummary> {
  return request<PackageSummary>(`/packages/${encodeURIComponent(packageId)}`, { signal });
}

/** `GET /v1/jobs/{id}` — the polling target while a package processes. */
export function getJob(jobId: Uuid, signal?: AbortSignal): Promise<JobResponse> {
  return request<JobResponse>(`/jobs/${encodeURIComponent(jobId)}`, { signal });
}

/** `POST /v1/jobs/{id}/resume` — retry a job that stopped at a FAILED stage. */
export function resumeJob(jobId: Uuid, signal?: AbortSignal): Promise<JobResponse> {
  return request<JobResponse>(`/jobs/${encodeURIComponent(jobId)}/resume`, {
    method: 'POST',
    signal,
  });
}

/** `GET /v1/packages/{id}/pages` — page geometry, the overlay's other half. */
export function getPackagePages(packageId: Uuid, signal?: AbortSignal): Promise<PackagePagesView> {
  return request<PackagePagesView>(`/packages/${encodeURIComponent(packageId)}/pages`, { signal });
}

/** `GET /v1/packages/{id}/documents` — the split projection, plus what fell out of it. */
export function getPackageDocuments(
  packageId: Uuid,
  signal?: AbortSignal,
): Promise<PackageDocumentsView> {
  return request<PackageDocumentsView>(`/packages/${encodeURIComponent(packageId)}/documents`, {
    signal,
  });
}

/**
 * `GET /v1/packages/{id}/usage` — what the package cost to parse.
 *
 * Counts, durations and version strings only: no field value appears in this
 * payload, so there is nothing here to mask. Safe to fetch as soon as the
 * package id is known — it answers with zeroed timing before a job exists
 * rather than 404ing.
 */
export function getPackageUsage(
  packageId: Uuid,
  signal?: AbortSignal,
): Promise<PackageUsageView> {
  return request<PackageUsageView>(`/packages/${encodeURIComponent(packageId)}/usage`, { signal });
}

/**
 * `POST /v1/packages/{id}/regroup` — apply a reviewer's membership delta.
 *
 * Returns the updated `PackageDocumentsView`, the SAME shape as
 * `getPackageDocuments`, so the caller re-renders the split projection with no
 * new read model (design §4.1). The server also invalidates and re-extracts the
 * affected documents asynchronously; the caller observes that through the
 * existing job poll, not here.
 */
export function regroup(
  packageId: Uuid,
  body: RegroupRequest,
  signal?: AbortSignal,
): Promise<PackageDocumentsView> {
  return request<PackageDocumentsView>(`/packages/${encodeURIComponent(packageId)}/regroup`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
    signal,
  });
}

/**
 * `POST /v1/pages/{id}/verdict` — override a page's blank/duplicate signal so it
 * becomes assignable. 200 with no body; nothing to return. Does not assign the
 * page — that is a subsequent {@link regroup} (design §4.2).
 */
export function overridePageVerdict(
  pageId: Uuid,
  verdict: PageVerdict,
  reason?: string | null,
  signal?: AbortSignal,
): Promise<void> {
  return requestNoContent(`/pages/${encodeURIComponent(pageId)}/verdict`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ verdict, reason: reason ?? null }),
    signal,
  });
}

/** `GET /v1/documents/{id}/fields` — fields with their full evidence chains. */
export function getDocumentFields(
  documentId: Uuid,
  signal?: AbortSignal,
): Promise<DocumentFieldsView> {
  return request<DocumentFieldsView>(`/documents/${encodeURIComponent(documentId)}/fields`, {
    signal,
  });
}

/**
 * `GET /v1/packages/{id}/export`.
 *
 * Sensitive fields in this payload arrive MASKED, exactly like `/fields` and
 * `/history` — the serializer boundary (`MaskingSerializer`, Spec 1 Phase 7,
 * proven by `ResponseMaskingIT`) covers every read surface unconditionally,
 * and no unmask endpoint exists (Spec 3 design D3). Nothing in the review
 * flow calls this; it is exposed for completeness of the client.
 */
export function getPackageExport(
  packageId: Uuid,
  signal?: AbortSignal,
): Promise<PackageExportView> {
  return request<PackageExportView>(`/packages/${encodeURIComponent(packageId)}/export`, { signal });
}

/**
 * `GET /v1/pages/{id}/render` as a URL, for `<img src>`.
 *
 * A URL rather than a fetch: letting the browser own the request gets caching,
 * lazy loading and decode-off-thread for free, and thumbnails are the one place
 * in this UI where that matters.
 *
 * The bytes are the page rendered AT its `/Rotate` — display space, not
 * rotation-0 (`worker/src/pragmaticds_docengine_worker/render.py`). PageViewer relies
 * on that when it positions the image fallback.
 */
export function pageRenderUrl(pageId: Uuid): string {
  return apiUrl(`/pages/${encodeURIComponent(pageId)}/render`);
}

/** `GET /v1/files/{id}/content` as a URL — the original bytes, for pdf.js. */
export function fileContentUrl(fileId: Uuid): string {
  return apiUrl(`/files/${encodeURIComponent(fileId)}/content`);
}

/** `PATCH /v1/fields/{id}` — one human decision on one occurrence: CONFIRM, CORRECT, REJECT. */
export function correctField(
  fieldId: Uuid,
  body: FieldCorrectionRequest,
  signal?: AbortSignal,
): Promise<FieldCorrectionResult> {
  return request<FieldCorrectionResult>(`/fields/${encodeURIComponent(fieldId)}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
    signal,
  });
}

/** `POST /v1/documents/{id}/review` — sign the document off (MARK_REVIEWED). */
export function markDocumentReviewed(
  documentId: Uuid,
  signal?: AbortSignal,
): Promise<DocumentDecisionResult> {
  return request<DocumentDecisionResult>(`/documents/${encodeURIComponent(documentId)}/review`, {
    method: 'POST',
    signal,
  });
}

/** `POST /v1/documents/{id}/classification` — override the document type. */
export function reclassifyDocument(
  documentId: Uuid,
  documentTypeCode: string,
  reason?: string | null,
  signal?: AbortSignal,
): Promise<DocumentDecisionResult> {
  return request<DocumentDecisionResult>(
    `/documents/${encodeURIComponent(documentId)}/classification`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ documentTypeCode, reason: reason ?? null }),
      signal,
    },
  );
}
