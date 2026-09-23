import { instanceControlRequest } from "../api";

/**
 * The client every instance-control call goes through.
 *
 * Its whole reason to exist is the scope rule. The legacy dashboard appends the HashRouter's
 * `?brain=<slug>` to every `/api/ai/admin` call so that screens which never thread a brain still
 * target the selected one. Instance-control endpoints sit under `/api/ai/admin/instances`, so that
 * rule matches them — and they take a brain UUID, not a slug.
 *
 * The type mismatch is the smaller half. The larger half is that an instance run pins a brain, a
 * release, a parsed revision and a corpus snapshot at submission, and a scope arriving from
 * whatever the sidebar happened to be showing is not pinned by anyone. So these calls opt out, and
 * every one of them names its brain explicitly.
 *
 * Mirrors the verbs of {@link api} rather than inventing its own, so moving a call between the two
 * clients is a one-word change and the difference stays visible at the import.
 */
export const instanceApi = {
  get: <T>(path: string) => instanceControlRequest<T>(path),

  post: <T>(path: string, body?: unknown) =>
    instanceControlRequest<T>(path, {
      method: "POST",
      body: body === undefined ? undefined : JSON.stringify(body),
    }),

  put: <T>(path: string, body: unknown) =>
    instanceControlRequest<T>(path, { method: "PUT", body: JSON.stringify(body) }),

  patch: <T>(path: string, body: unknown) =>
    instanceControlRequest<T>(path, { method: "PATCH", body: JSON.stringify(body) }),

  del: <T>(path: string) => instanceControlRequest<T>(path, { method: "DELETE" }),

  /**
   * A JSON POST carrying `Idempotency-Key`.
   *
   * The caller owns the key and mints it once per explicit user action — one Run click, one Apply
   * to Live — then reuses that value through every retry of that action. The backend answers a
   * repeated key with the stored row and starts no second provider call, so a key minted per
   * attempt defeats the guarantee silently and bills twice for one click.
   */
  postIdempotent: <T>(path: string, body: unknown, idempotencyKey: string) =>
    instanceControlRequest<T>(path, {
      method: "POST",
      body: JSON.stringify(body),
      headers: { "Idempotency-Key": idempotencyKey },
    }),

  /**
   * A multipart POST carrying `Idempotency-Key`.
   *
   * No `Content-Type` is set, so the browser writes its own multipart boundary. Unlike the legacy
   * client's upload there is no brain to append here: instance-control paths already carry the
   * brain UUID the caller pinned, and the sidebar's slug was never going to be right for them.
   *
   * The key matters more on this route than anywhere else. An upload hands the Document Engine an
   * original to parse; a key minted per attempt turns one interrupted upload into two packages,
   * and the second is indistinguishable from a deliberate re-parse afterwards.
   */
  uploadIdempotent: <T>(path: string, form: FormData, idempotencyKey: string) =>
    instanceControlRequest<T>(path, {
      method: "POST",
      body: form,
      headers: { "Idempotency-Key": idempotencyKey },
    }),
};
