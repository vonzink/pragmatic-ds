import { useRef, useState } from "react";
import { instanceApi } from "../api";
import type {
  PinnedParsedInput,
  SnapshotCollectionRequest,
  SnapshotDetail,
  UploadedParsedInput,
} from "../types";

/**
 * The two things every run must be pinned to, and the keys that keep pinning them cheap.
 *
 * A run pins a parsed revision and a corpus snapshot, and getting either wrong costs real money —
 * an upload becomes a second package in the Document Engine, a freeze becomes a second snapshot.
 * Every screen that starts a run needs exactly this, so it lives here once rather than being
 * reimplemented per screen with its own subtly different idea of when a key is reused.
 *
 * ### Keys
 *
 * Three actions here mutate: upload, pin, freeze. Each mints one idempotency key on its first
 * attempt and reuses it through every retry, releasing it only once that action has landed. So the
 * retry after a timeout carries the key the timed-out attempt did, and the backend answers it with
 * the row the first attempt already made instead of starting a second.
 *
 * The rule that makes this correct is narrow: a *new explicit user action* earns a new key; a
 * retry of the same action never does. A key minted per attempt would look identical on screen and
 * be wrong in the ledger.
 */

export interface RunInputs {
  parsed: PinnedParsedInput | null;
  snapshot: SnapshotDetail | null;
  parsedBusy: boolean;
  parsedError: string | null;
  /** Set to the package id when an upload landed but the engine has no revision to pin yet. */
  awaitingParse: string | null;
  snapshotBusy: boolean;
  snapshotError: string | null;
  pin: (packageId: string, revision: number | null, sourceIds: string[]) => void;
  upload: (file: File) => void;
  freeze: (collections: SnapshotCollectionRequest[]) => void;
}

export function useRunInputs(brainId: string, instanceSlug: string): RunInputs {
  const [parsed, setParsed] = useState<PinnedParsedInput | null>(null);
  const [parsedBusy, setParsedBusy] = useState(false);
  const [parsedError, setParsedError] = useState<string | null>(null);
  const [awaitingParse, setAwaitingParse] = useState<string | null>(null);

  const [snapshot, setSnapshot] = useState<SnapshotDetail | null>(null);
  const [snapshotBusy, setSnapshotBusy] = useState(false);
  const [snapshotError, setSnapshotError] = useState<string | null>(null);

  const uploadKey = useRef<string | null>(null);
  const pinKey = useRef<string | null>(null);
  const freezeKey = useRef<string | null>(null);

  const base = "/api/ai/admin/instances";
  const brainQuery = `brain=${encodeURIComponent(brainId)}`;

  async function pin(packageId: string, revision: number | null, sourceIds: string[]) {
    if (pinKey.current === null) pinKey.current = crypto.randomUUID();
    setParsedBusy(true);
    setParsedError(null);
    try {
      const pinned = await instanceApi.postIdempotent<PinnedParsedInput>(
        `${base}/${encodeURIComponent(instanceSlug)}/parsed-inputs?${brainQuery}`,
        { packageId, revision, selectedSourceIds: sourceIds },
        pinKey.current);
      setParsed(pinned);
      setAwaitingParse(null);
      pinKey.current = null;
    } catch (e) {
      // Read as a shape rather than an imported error class: the instance routes answer `{code}`
      // and this is the one code that means "not yet" rather than "no".
      const code = (e as { code?: string }).code ?? null;
      if (code === "PARSE_REVISION_NOT_FOUND") {
        // The engine took the upload and has not finished with it. The key is kept, so checking
        // again is a retry of this same pin and not a second one.
        setAwaitingParse(packageId);
        setParsedError(null);
      } else {
        setParsedError((e as Error).message);
      }
    } finally {
      setParsedBusy(false);
    }
  }

  async function upload(file: File) {
    if (uploadKey.current === null) uploadKey.current = crypto.randomUUID();
    setParsedBusy(true);
    setParsedError(null);
    const form = new FormData();
    form.append("file", file);
    try {
      const uploaded = await instanceApi.uploadIdempotent<UploadedParsedInput>(
        `${base}/${encodeURIComponent(instanceSlug)}/parsed-inputs/upload?${brainQuery}`,
        form, uploadKey.current);
      uploadKey.current = null;
      // An upload is not yet something a run can pin: it has a package but no chosen revision.
      // Pinning the newest revision of that package is the follow-on, with a key of its own.
      // `pin` clears the busy flag for this path, which is why there is no `finally` here.
      await pin(uploaded.packageId, null, []);
    } catch (e) {
      setParsedError((e as Error).message);
      setParsedBusy(false);
    }
  }

  async function freeze(collections: SnapshotCollectionRequest[]) {
    if (freezeKey.current === null) freezeKey.current = crypto.randomUUID();
    setSnapshotBusy(true);
    setSnapshotError(null);
    try {
      const frozen = await instanceApi.postIdempotent<SnapshotDetail>(
        `${base}/corpus-snapshots?${brainQuery}`, { collections }, freezeKey.current);
      setSnapshot(frozen);
      freezeKey.current = null;
    } catch (e) {
      setSnapshotError((e as Error).message);
    } finally {
      setSnapshotBusy(false);
    }
  }

  return {
    parsed, snapshot, parsedBusy, parsedError, awaitingParse, snapshotBusy, snapshotError,
    pin: (packageId, revision, sourceIds) => void pin(packageId, revision, sourceIds),
    upload: (file) => void upload(file),
    freeze: (collections) => void freeze(collections),
  };
}
