import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { ApiError, labApi } from "../api";
import { ErrorNote, Pill } from "../components";
import { buildEnvelopeView } from "../lab/parseEnvelope";
import {
  LabDiscussionResponse,
  LabDocumentStatusResponse,
  LabEnvelopeResponse,
  LabInstancesResponse,
  LabPrototype,
  LabPurgeResponse,
  LabRegistrationResponse,
  LabRunResponse,
  LabRunSummary,
} from "../types";

/**
 * The Income Lab workbench: register one document, watch it parse, read the parse, run the pinned
 * Income release from those parsed facts, and discuss one run.
 *
 * <p><b>Build-time flag, default off.</b> {@link labEnabled} is the only gate. With the flag absent
 * or false, `App` never mounts this screen and never adds its nav entry, so every existing route,
 * the Test Console, and the catch-all redirect behave exactly as before.
 *
 * <p><b>Errors are rendered from a code, because a code is all there is.</b> Lab error bodies are
 * `{code, correlationId, counts}` — no `message`, `error`, `detail`, or `cause`, asserted absent on
 * the server precisely so a provider body, engine URI, filename, or parsed value has nowhere to
 * travel. So this screen shows the code verbatim, the correlation id for the operator to grep the
 * server log with, the safe counts, and — only for codes whose MEANING is part of the published
 * contract — one line of operator guidance. It never phrases a guess as if the server had said it,
 * and it always states why no further detail exists.
 *
 * <p><b>Nothing here can hold a secret.</b> The engine base URL, the engine credential, the model
 * provider keys, and the payload encryption key are backend-only; the DTOs this screen reads have
 * no member for any of them. Every read is by named member, never a blind dump of a response.
 */

/** The dashboard build flag. Absent or anything but "true" means the Lab does not exist. */
export function labEnabled(): boolean {
  return import.meta.env.VITE_FOLDER_AI_LAB_ENABLED === "true";
}

const POLL_INTERVAL_MS = 2000;
const POLL_MAX_ATTEMPTS = 90;

/** Engine job states that will not change again, so polling stops. */
const TERMINAL_JOB_STATES = new Set([
  "COMPLETED", "HUMAN_REVIEW_REQUIRED", "FAILED", "CANCELLED", "REJECTED",
]);

/**
 * Codes that mean "this deployment is not configured to serve the Lab at all". They get their own
 * screen state: an operator seeing a generic failure here would go looking at the document.
 */
const CONFIGURATION_CODES = new Set(["RETENTION_NOT_CONFIGURED", "KEY_UNAVAILABLE"]);

/**
 * Why there is nothing more to show. Stated on every failure so the absence of detail reads as a
 * deliberate contract rather than as this screen swallowing something.
 */
const NO_FURTHER_DETAIL =
  "A Lab error body carries a code, a correlation id, and counts only — there is no message " +
  "member on the wire, so no further server detail exists to show. Search the server log for the " +
  "correlation id.";

/**
 * One line per code whose meaning is published contract. This is not invented server detail: each
 * line restates what the code itself is defined to mean, and no line claims to know anything about
 * this particular request beyond its code.
 */
const CODE_GUIDANCE: Record<string, string> = {
  RETENTION_NOT_CONFIGURED:
    "The Lab is enabled but its retention window is not set. `ragbrain.lab.retention-days` must " +
    "be present and positive before any Lab request can be served. This is deployment " +
    "configuration; ordinary RAG Brain startup and every non-Lab route are unaffected.",
  KEY_UNAVAILABLE:
    "No usable 32-byte Base64 Lab payload key is configured, so encrypted runs and discussion can " +
    "be neither written nor read. This is deployment configuration.",
  UPLOAD_FILE_COUNT_INVALID:
    "One registration carries exactly one original document. Additional documents are added " +
    "through separate uploads and separate engine packages.",
  UPLOAD_EMPTY: "The selected file had no bytes; no engine call was made.",
  UPLOAD_TOO_LARGE: "The file exceeded the Lab upload ceiling; no engine call was made.",
  ENGINE_TIMEOUT: "Document Engine did not answer within the configured connect or read timeout.",
  REGISTRATION_CONFLICT:
    "That engine package is already registered to a different brain or Lab instance. Package " +
    "identity is globally unique in this prototype and cannot be rebound.",
  REGISTRATION_NOT_FOUND:
    "No registration in this brain and instance owns that package, so it cannot be read here.",
  JOB_IDENTITY_MISMATCH: "The job id does not belong to the registration for that package.",
  REVISION_NOT_FOUND: "That engine-result revision does not exist for this package.",
  RUN_NOT_FOUND: "No run with that id exists in this brain.",
  RUN_IN_PROGRESS: "That run is still processing; it is not deleted underneath a live call.",
  RUN_NOT_SUCCEEDED: "Discussion is available only for a run that reached SUCCEEDED.",
  DISCUSSION_EXCHANGE_IN_PROGRESS:
    "Another turn on this run is still being answered. Exchanges are serialized per run so the " +
    "transcript cannot be reordered; wait for it to finish, then send again.",
  DISCUSSION_EXCHANGE_INTERRUPTED:
    "A previous turn's lease expired and was marked interrupted without replaying the model. " +
    "Sending again starts a new turn under a new key.",
  DISCUSSION_CONTEXT_TOO_LARGE:
    "The composed context exceeded the Lab's bound. The Lab refuses rather than silently dropping " +
    "parsed facts, analysis output, or transcript history; shorten the question or start a new run.",
  DISCUSSION_QUESTION_REQUIRED: "A discussion turn needs a question.",
  IDEMPOTENCY_KEY_REQUIRED: "Every mutating Lab route requires an Idempotency-Key header.",
};

/** Capabilities the specification describes but this prototype does not have. */
const ABSENT_CAPABILITIES = [
  "Compare", "Score", "Scenario", "Promote", "Explicit reparse",
  "Original-document rendering", "Correction and review actions",
  "Deletion-event reconciliation with the authoritative source",
];

type Tab = "documents" | "parse" | "analysis" | "history";

const TABS: Array<{ id: Tab; label: string }> = [
  { id: "documents", label: "Documents" },
  { id: "parse", label: "Parse output" },
  { id: "analysis", label: "AI analysis" },
  { id: "history", label: "Discussion & history" },
];

// ---------------------------------------------------------------- failure rendering

function asApiError(error: unknown): ApiError | null {
  return error instanceof ApiError ? error : null;
}

function failureCode(error: unknown): string | null {
  return asApiError(error)?.code ?? null;
}

/** True when a failure means the deployment cannot serve the Lab, not that the request was bad. */
function isConfigurationFailure(error: unknown): boolean {
  const code = failureCode(error);
  return code !== null && CONFIGURATION_CODES.has(code);
}

function CodeChips({ error }: { error: unknown }) {
  const failure = asApiError(error);
  const code = failure?.code;
  return (
    <div className="chips">
      <Pill tone="amber">{code ?? (error as Error)?.message ?? "request failed"}</Pill>
      {failure && <Pill tone="gray">HTTP {failure.status}</Pill>}
      {failure?.correlationId && <Pill tone="gray">correlation {failure.correlationId}</Pill>}
      {Object.entries(failure?.counts ?? {}).map(([name, value]) => (
        <Pill key={name} tone="gray">{name} {String(value)}</Pill>
      ))}
    </div>
  );
}

/** The one failure surface. Code, correlation id, counts, and why that is all there is. */
function LabFailure({ error }: { error: unknown }) {
  if (!error) return null;
  const code = failureCode(error);
  const guidance = code === null ? null : CODE_GUIDANCE[code] ?? null;
  return (
    <div className="card lab-failure" data-testid="lab-failure" role="alert">
      <CodeChips error={error} />
      {guidance && <p className="lab-guidance">{guidance}</p>}
      <p className="muted">{NO_FURTHER_DETAIL}</p>
    </div>
  );
}

/** The deployment-configuration state, which is emphatically not "this document failed". */
function LabUnconfigured({ error }: { error: unknown }) {
  const code = failureCode(error);
  return (
    <div className="card lab-unconfigured" data-testid="lab-unconfigured" role="alert">
      <h2>Lab configuration incomplete</h2>
      <CodeChips error={error} />
      <p className="lab-guidance">{code === null ? NO_FURTHER_DETAIL : CODE_GUIDANCE[code]}</p>
      <p className="muted">
        No document is registered, uploaded, or parsed while the Lab cannot serve a request.
      </p>
    </div>
  );
}

// ---------------------------------------------------------------- prototype boundary

function PrototypeBoundary({ prototype }: { prototype: LabPrototype }) {
  return (
    <div className="card lab-boundary" data-testid="prototype-boundary">
      <div className="chips">
        <Pill tone="purple">{prototype.code}</Pill>
        {prototype.liveDependencies.map((dependency) => (
          <Pill key={dependency} tone="amber">{dependency}</Pill>
        ))}
      </div>
      <p className="muted">
        This prototype pins the analyzer instructions, output contract, and the exact engine-result
        revision. Everything badged above is still live: a repeat of the same run need not produce
        the same answer, and no result here is production-reproducible.
      </p>
      <p className="muted" data-testid="absent-capabilities">
        Absent from this prototype rather than disabled: {ABSENT_CAPABILITIES.join(", ")}.
      </p>
    </div>
  );
}

// ---------------------------------------------------------------- the screen

export default function IncomeLab() {
  const [tab, setTab] = useState<Tab>("documents");

  const [instances, setInstances] = useState<LabInstancesResponse | null>(null);
  const [booting, setBooting] = useState(true);
  const [bootError, setBootError] = useState<unknown>(null);

  const [file, setFile] = useState<File | null>(null);
  // Generated once per selected file and reused through every retry: that is what makes the
  // engine's durable upload idempotency work. A key per attempt would silently defeat it.
  const [uploadKey, setUploadKey] = useState<string | null>(null);
  const [fileError, setFileError] = useState<string | null>(null);
  const [registering, setRegistering] = useState(false);
  const [registration, setRegistration] = useState<LabRegistrationResponse | null>(null);
  const [registerError, setRegisterError] = useState<unknown>(null);

  const [docStatus, setDocStatus] = useState<LabDocumentStatusResponse | null>(null);
  const [pollAttempt, setPollAttempt] = useState(0);
  const [statusError, setStatusError] = useState<unknown>(null);

  const [envelope, setEnvelope] = useState<LabEnvelopeResponse | null>(null);
  const [envelopeError, setEnvelopeError] = useState<unknown>(null);
  const envelopeRequested = useRef<string | null>(null);

  const [run, setRun] = useState<LabRunResponse | null>(null);
  const [running, setRunning] = useState(false);
  const [runError, setRunError] = useState<unknown>(null);

  const [history, setHistory] = useState<LabRunSummary[]>([]);
  const [historyError, setHistoryError] = useState<unknown>(null);
  const [armedDelete, setArmedDelete] = useState<string | null>(null);
  const [purged, setPurged] = useState<LabPurgeResponse | null>(null);
  const [purgeError, setPurgeError] = useState<unknown>(null);

  const [discussion, setDiscussion] = useState<LabDiscussionResponse | null>(null);
  const [question, setQuestion] = useState("");
  const [sending, setSending] = useState(false);
  const [discussionError, setDiscussionError] = useState<unknown>(null);

  const prototype = instances?.prototype ?? null;
  const instance = instances?.instances[0] ?? null;

  const revision = useMemo(() => {
    const revisions = docStatus?.revisions ?? [];
    return revisions.length === 0 ? null : revisions[revisions.length - 1].revision;
  }, [docStatus]);

  const analyzable = Boolean(docStatus?.analyzable)
    && revision !== null
    && (envelope === null || envelope.compatible);

  // ---------------------------------------------------------------- boot

  useEffect(() => {
    let cancelled = false;
    labApi.instances()
      .then((loaded) => { if (!cancelled) setInstances(loaded); })
      .catch((e) => { if (!cancelled) setBootError(e); })
      .finally(() => { if (!cancelled) setBooting(false); });
    return () => { cancelled = true; };
  }, []);

  const loadHistory = useCallback(() => {
    labApi.history()
      .then((loaded) => setHistory(loaded.runs))
      .catch(setHistoryError);
  }, []);

  useEffect(() => { loadHistory(); }, [loadHistory]);

  // ---------------------------------------------------------------- job polling
  //
  // One read per effect run, then a bounded timer schedules the next. The cleanup cancels both the
  // pending timer and the in-flight response, so an unmount or a replaced registration stops the
  // poll rather than writing into a screen that no longer exists.

  useEffect(() => {
    const packageId = registration?.packageId;
    const jobId = registration?.jobId;
    if (!packageId || !jobId) return;

    let cancelled = false;
    let timer: ReturnType<typeof setTimeout> | undefined;

    labApi.documentStatus(packageId, jobId)
      .then((next) => {
        if (cancelled) return;
        setDocStatus(next);
        setStatusError(null);
        if (!TERMINAL_JOB_STATES.has(next.status) && pollAttempt + 1 < POLL_MAX_ATTEMPTS) {
          timer = setTimeout(() => {
            if (!cancelled) setPollAttempt((attempt) => attempt + 1);
          }, POLL_INTERVAL_MS);
        }
      })
      .catch((e) => { if (!cancelled) setStatusError(e); });

    return () => {
      cancelled = true;
      if (timer !== undefined) clearTimeout(timer);
    };
  }, [registration, pollAttempt]);

  // ---------------------------------------------------------------- envelope
  //
  // Fetched once per (package, revision). A repeated read never uploads, resumes, regroups, or
  // reparses on the backend, but there is still no reason to ask twice for immutable bytes.

  useEffect(() => {
    const packageId = registration?.packageId;
    if (!packageId || revision === null || !docStatus?.analyzable) return;
    const identity = `${packageId}@${revision}`;
    if (envelopeRequested.current === identity) return;
    envelopeRequested.current = identity;

    let cancelled = false;
    labApi.envelope(packageId, revision)
      .then((loaded) => { if (!cancelled) { setEnvelope(loaded); setEnvelopeError(null); } })
      .catch((e) => { if (!cancelled) setEnvelopeError(e); });
    return () => { cancelled = true; };
  }, [registration, revision, docStatus]);

  const envelopeView = useMemo(
    () => (envelope === null ? null : buildEnvelopeView(envelope)), [envelope]);

  // ---------------------------------------------------------------- actions

  function selectFiles(selected: FileList | null) {
    setRegisterError(null);
    const files = selected === null ? [] : Array.from(selected);
    if (files.length === 0) return;
    if (files.length !== 1) {
      // Refused here, with no network call: one registration is one original document, matching
      // Document Engine's one-original-per-package contract. The backend refuses this too.
      setFileError(
        `Select exactly one file. ${files.length} were selected; one registration carries one ` +
        "document, and further documents are added as separate uploads.");
      setFile(null);
      setUploadKey(null);
      return;
    }
    setFileError(null);
    setFile(files[0]);
    setUploadKey(crypto.randomUUID());
    setRegistration(null);
    setDocStatus(null);
    setEnvelope(null);
    envelopeRequested.current = null;
    setPollAttempt(0);
  }

  async function register() {
    if (!file || !uploadKey) return;
    setRegistering(true);
    setRegisterError(null);
    try {
      const form = new FormData();
      form.append("file", file);
      const registered = await labApi.register(form, uploadKey);
      setRegistration(registered);
      setPollAttempt(0);
    } catch (e) {
      // The key is deliberately NOT reset: the next attempt is the same action, so it must carry
      // the same key and let the engine answer with the same package rather than a second one.
      setRegisterError(e);
    } finally {
      setRegistering(false);
    }
  }

  /**
   * Starts one analysis over an already-parsed engine revision.
   *
   * <p>Called from the header for the registration in hand, and from a history row for a
   * registration this brain already owns. Both are the same action: pin an existing package and
   * revision, and analyze it. Neither uploads, reparses, or lets a caller name an arbitrary
   * package — this prototype has no field for typing one, by design.
   */
  async function startRun(packageId: string, pinnedRevision: number) {
    setTab("analysis");
    setRunning(true);
    setRunError(null);
    try {
      // One key per explicit Run action. A second Run is a second action and gets a new key, which
      // is what makes it a new analysis run over the same already-parsed revision.
      const started = await labApi.startRun(
        { packageId, revision: pinnedRevision }, crypto.randomUUID());
      setRun(started);
      setDiscussion(null);
      loadHistory();
    } catch (e) {
      setRunError(e);
    } finally {
      setRunning(false);
    }
  }

  async function openRun(runId: string) {
    setRunError(null);
    setDiscussionError(null);
    try {
      // Reads only. Selecting history never re-registers, re-parses, or re-runs anything.
      const [detail, transcript] = await Promise.all([labApi.run(runId), labApi.discussion(runId)]);
      setRun(detail);
      setDiscussion(transcript);
    } catch (e) {
      setRunError(e);
    }
  }

  async function send() {
    if (!run || !question.trim()) return;
    setSending(true);
    setDiscussionError(null);
    try {
      // One key per explicit Send action, retained by this call only; a network retry of the same
      // action would reuse it through the caller, and a new Send is a new action.
      const appended = await labApi.postMessage(
        run.runId, { question: question.trim() }, crypto.randomUUID());
      setDiscussion(appended);
      setQuestion("");
    } catch (e) {
      setDiscussionError(e);
    } finally {
      setSending(false);
    }
  }

  async function confirmPurge(runId: string) {
    setPurgeError(null);
    try {
      const result = await labApi.purgeRun(runId);
      setPurged(result);
      // Removed locally only now that the server said it is gone.
      setHistory((rows) => rows.filter((row) => row.runId !== runId));
      if (run?.runId === runId) { setRun(null); setDiscussion(null); }
      setArmedDelete(null);
    } catch (e) {
      setPurgeError(e);
    }
  }

  // ---------------------------------------------------------------- render

  if (booting) {
    return (
      <>
        <header className="screen-head"><h1>Income lab</h1></header>
        <p className="muted" data-testid="lab-loading">Loading the Income lab instance…</p>
      </>
    );
  }

  if (bootError) {
    return (
      <>
        <header className="screen-head"><h1>Income lab</h1></header>
        {isConfigurationFailure(bootError)
          ? <LabUnconfigured error={bootError} />
          : <LabFailure error={bootError} />}
      </>
    );
  }

  return (
    <>
      <header className="screen-head">
        <h1>Income lab</h1>
        <div className="mode-toggle">
          {TABS.map(({ id, label }) => (
            <button key={id} className={tab === id ? "on" : ""} onClick={() => setTab(id)}>
              {label}
            </button>
          ))}
        </div>
        <div className="actions">
          <button
            className="btn-primary"
            onClick={() => {
              if (registration && revision !== null) startRun(registration.packageId, revision);
            }}
            disabled={!analyzable || running}
          >
            {running ? "Running…" : "Run income analysis"}
          </button>
        </div>
      </header>

      {prototype && <PrototypeBoundary prototype={prototype} />}

      {instance && (
        <div className="chips lab-release">
          <Pill tone="blue">instance {instance.slug}</Pill>
          <Pill tone="gray">analyzer {instance.analyzerSlug}</Pill>
          <Pill tone="gray">release {instance.productionReleaseNumber}</Pill>
          <Pill tone="gray">manifest {instance.productionManifestSha256.slice(0, 16)}</Pill>
          {instance.driftDetected && (
            <Pill tone="amber">
              live pack drifted — runs still use release {instance.productionReleaseNumber}
            </Pill>
          )}
        </div>
      )}

      {tab === "documents" && (
        <div className="card" data-testid="documents-panel">
          <h2>Register one document</h2>
          <p className="muted">
            Document Engine is the document store. One registration carries exactly one original;
            add further documents as separate uploads. Nothing is stored here, and the file name is
            neither read nor forwarded.
          </p>
          <div
            className="lab-drop"
            data-testid="lab-drop"
            onDragOver={(e) => e.preventDefault()}
            onDrop={(e) => { e.preventDefault(); selectFiles(e.dataTransfer.files); }}
          >
            <label htmlFor="lab-file">Document file</label>
            <input
              id="lab-file"
              type="file"
              accept="application/pdf,image/png,image/jpeg,image/tiff"
              onChange={(e) => selectFiles(e.target.files)}
            />
            <span className="muted">or drop a single file here</span>
          </div>
          {file && (
            <p className="muted">
              1 file selected · {file.size} bytes · {file.type || "type not declared"}
            </p>
          )}
          <ErrorNote message={fileError} />
          <div className="actions">
            <button className="btn-primary" onClick={register}
                    disabled={!file || !uploadKey || registering}>
              {registering ? "Registering…" : "Register document"}
            </button>
          </div>
          <LabFailure error={registerError} />

          {registration && (
            <div className="chips">
              <Pill tone={registration.created ? "green" : "gray"}>
                {registration.created ? "registered" : "existing registration"}
              </Pill>
              <Pill tone="gray">package {registration.packageId.slice(0, 8)}</Pill>
              <Pill tone="gray">job {registration.jobId.slice(0, 8)}</Pill>
              <Pill tone="gray">{registration.sourceCount} source</Pill>
              {registration.duplicateShaPrefixes.map((prefix) => (
                <Pill key={prefix} tone="amber">duplicate {prefix}</Pill>
              ))}
            </div>
          )}

          {docStatus && (
            <>
              <h3>Parse state</h3>
              <div className="chips">
                <Pill tone={docStatus.analyzable ? "green" : "amber"}>{docStatus.status}</Pill>
                {docStatus.currentStage && <Pill tone="gray">{docStatus.currentStage}</Pill>}
                <Pill tone="gray">{docStatus.revisions.length} revision(s)</Pill>
              </div>
              {docStatus.warnings.length > 0 && (
                <div className="lab-warning" data-testid="status-warnings" role="status">
                  <div className="chips">
                    {docStatus.warnings.map((warning) => (
                      <Pill key={warning} tone="amber">{warning}</Pill>
                    ))}
                  </div>
                  <p className="lab-guidance">
                    This package is analyzable, but the engine asked for human review of at least
                    one region. Read the parse output before trusting any figure derived from it.
                  </p>
                </div>
              )}
              {!docStatus.analyzable && !TERMINAL_JOB_STATES.has(docStatus.status) && (
                <p className="muted">Still processing. Polling every {POLL_INTERVAL_MS / 1000}s.</p>
              )}
              {!docStatus.analyzable && TERMINAL_JOB_STATES.has(docStatus.status) && (
                <p className="lab-guidance">
                  This job cannot be analyzed. There is no raw-document fallback in this prototype
                  and no reparse action; register the document again to produce a new package.
                </p>
              )}
            </>
          )}
          <LabFailure error={statusError} />
        </div>
      )}

      {tab === "parse" && (
        <div className="card" data-testid="parse-panel">
          <h2>Parse output</h2>
          <p className="muted" data-testid="parse-read-only">
            Read-only. This prototype offers no correction, regroup, reclassify, or reparse action,
            and does not render the original document.
          </p>
          <LabFailure error={envelopeError} />
          {!envelope && !envelopeError && (
            <p className="muted">No parse output yet. Register a document and wait for a revision.</p>
          )}
          {envelope && envelopeView && (
            <>
              <div className="chips">
                <Pill tone={envelope.compatible ? "green" : "amber"}>
                  {envelope.compatible ? "compatible" : "incompatible"}
                </Pill>
                {envelope.rejection && <Pill tone="amber">{envelope.rejection}</Pill>}
                <Pill tone="gray">revision {envelope.revision}</Pill>
                <Pill tone="gray">generation {envelope.parseGeneration}</Pill>
                <Pill tone="gray">envelope {envelope.envelopeVersion}</Pill>
                <Pill tone="gray">{envelope.canonicalizationVersion}</Pill>
                <Pill tone="gray">sha256 {envelope.envelopeSha256.slice(0, 16)}</Pill>
                <Pill tone="gray">{envelope.envelopeSizeBytes} bytes</Pill>
                <Pill tone="gray">{envelope.reuseEligibility}</Pill>
                <Pill tone="gray">{envelopeView.foundCount} found</Pill>
                <Pill tone="gray">{envelopeView.missingCount} missing</Pill>
              </div>

              {envelope.warnings.length > 0 && (
                <ul className="citations">
                  {envelope.warnings.map((warning, index) => (
                    <li key={index}>
                      {[warning.code, warning.documentTypeCode, warning.fieldName,
                        warning.groupKey === null ? null : `group ${warning.groupKey}`]
                        .filter(Boolean).join(" · ")}
                    </li>
                  ))}
                </ul>
              )}

              {envelopeView.groupingAmbiguous && (
                <p className="lab-guidance" data-testid="grouping-caveat">
                  At least one missing field carries no group key. The canonical envelope at this
                  engine release has no grouping-kind member, so this screen cannot tell a grouped
                  field whose region was unreadable from a field that was never grouped. Those rows
                  read “unknown” rather than being assigned a group they may not belong to.
                </p>
              )}

              <div className="lab-scroll" data-testid="lab-scroll">
                <table className="tbl">
                  <thead>
                    <tr><th>page</th><th>size</th><th>rotation</th><th>text layer</th>
                      <th>classification</th></tr>
                  </thead>
                  <tbody>
                    {envelope.pages.map((page) => (
                      <tr key={page.id}>
                        <td>p. {page.packagePageIndex + 1}</td>
                        <td>{page.widthPt}×{page.heightPt} pt</td>
                        <td>{page.rotation}°</td>
                        <td>{page.textLayer ?? "—"}{page.blank ? " · blank" : ""}
                          {page.duplicate ? " · duplicate" : ""}</td>
                        <td>{[page.documentTypeCode, page.classificationConfidence,
                             page.classificationMethod].filter((v) => v !== null).join(" · ")
                             || "unclassified"}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>

              {envelopeView.unassignedPageLabels.length > 0 && (
                <p className="muted">
                  Assigned to no document: {envelopeView.unassignedPageLabels.join(", ")}
                </p>
              )}

              {envelopeView.documents.map((document) => (
                <section key={document.id} className="lab-document">
                  <div className="chips">
                    <Pill tone="blue">{document.documentTypeCode ?? "UNCLASSIFIED"}</Pill>
                    <Pill tone="gray">document {document.ordinal}</Pill>
                    <Pill tone="gray">{document.pageLabels.join(", ") || "no pages"}</Pill>
                    <Pill tone="gray">{document.foundCount} found</Pill>
                    <Pill tone="gray">{document.missingCount} missing</Pill>
                  </div>
                  <div className="lab-scroll" data-testid="lab-scroll">
                    <table className="tbl">
                      <thead>
                        <tr>
                          <th>field</th><th>group</th><th>state</th><th>review</th><th>value</th>
                          <th>displayed</th><th>confidence</th><th>method</th><th>validation</th>
                          <th>evidence</th>
                        </tr>
                      </thead>
                      <tbody>
                        {document.groups.flatMap((group) => group.rows.map((row) => (
                          <tr key={`${group.label}:${row.field.name}`}
                              data-testid={`field-${row.field.name}`}>
                            <td>{row.field.name}</td>
                            <td>{group.label}</td>
                            <td>{row.field.status}</td>
                            <td>{row.field.reviewState}</td>
                            <td className={row.value.kind === "MISSING"
                              || row.value.kind === "REJECTED" ? "lab-missing" : ""}>
                              {row.value.text}
                            </td>
                            <td>{row.field.displayedText ?? "—"}</td>
                            <td>{row.field.confidence === null ? "—" : row.field.confidence}</td>
                            <td>{row.field.method ?? "—"}</td>
                            <td>{row.field.validationStatus ?? "—"}</td>
                            <td>
                              {row.evidence.length === 0 ? "none" : row.evidence.map((span) =>
                                `${span.pageLabel} · ${span.role}#${span.ordinal}${span.box === null
                                  ? "" : ` · x ${span.box.x} y ${span.box.y} `
                                    + `w ${span.box.width} h ${span.box.height}`}`).join(" | ")}
                            </td>
                          </tr>
                        )))}
                      </tbody>
                    </table>
                  </div>
                </section>
              ))}
            </>
          )}
        </div>
      )}

      {tab === "analysis" && (
        <div className="card" data-testid="analysis-panel">
          <h2>AI analysis</h2>
          <LabFailure error={runError} />
          {running && <p className="muted">Running the pinned Income release…</p>}
          {!run && !running && !runError && (
            <p className="muted">
              No analysis yet. Run the pinned release against a parsed revision.
            </p>
          )}
          {run && (
            <>
              <div className="chips">
                <Pill tone={run.status === "SUCCEEDED" ? "green" : "amber"}>{run.status}</Pill>
                {run.failureCode && <Pill tone="amber">{run.failureCode}</Pill>}
                <Pill tone="gray">run {run.runId.slice(0, 8)}</Pill>
                <Pill tone="gray">release {run.releaseId.slice(0, 8)}</Pill>
                <Pill tone="gray">manifest {run.releaseManifestSha256.slice(0, 16)}</Pill>
                {run.analysisRunId
                  && <Pill tone="gray">analysis run {run.analysisRunId.slice(0, 8)}</Pill>}
                {run.replayed && <Pill tone="blue">replayed — no new provider call</Pill>}
                {run.reviewSnapshot
                  ? <Pill tone="gray">{`${run.reviewSnapshot.machineCount} machine `
                      + `· ${run.reviewSnapshot.correctedCount} corrected `
                      + `· ${run.reviewSnapshot.rejectedCount} rejected`}</Pill>
                  : <Pill tone="gray">envelope values only</Pill>}
              </div>
              {run.source && (
                <div className="chips">
                  <Pill tone="gray">package {run.source.packageId.slice(0, 8)}</Pill>
                  <Pill tone="gray">revision {run.source.revision}</Pill>
                  <Pill tone="gray">generation {run.source.parseGeneration}</Pill>
                  <Pill tone="gray">sha256 {run.source.envelopeSha256.slice(0, 16)}</Pill>
                  <Pill tone="gray">{run.source.documentCount} documents</Pill>
                  <Pill tone="gray">{run.source.pageCount} pages</Pill>
                </div>
              )}
              {run.analysis && (
                <>
                  <div className="chips">
                    <Pill tone="gray">
                      {[run.analysis.provider, run.analysis.model].filter(Boolean).join(" · ")
                        || "provider not recorded"}
                    </Pill>
                    <Pill tone="gray">
                      tokens {run.analysis.inputTokens} in / {run.analysis.outputTokens} out
                    </Pill>
                    <Pill tone="gray">cost ${run.analysis.costUsd}</Pill>
                    <Pill tone="gray">{run.analysis.providerAttempts} provider attempt(s)</Pill>
                  </div>
                  {run.analysis.reportMarkdown && (
                    <p className="answer">{run.analysis.reportMarkdown}</p>
                  )}
                  {run.analysis.findingsJson && (
                    <div className="lab-scroll" data-testid="lab-scroll">
                      <pre className="lab-findings">{run.analysis.findingsJson}</pre>
                    </div>
                  )}
                  {run.analysis.citations.length > 0 && (
                    <ul className="citations">
                      {run.analysis.citations.map((citation, index) => (
                        <li key={index}>
                          {[citation.sourceName, citation.documentName, citation.section,
                            citation.pageNumber ? `p. ${citation.pageNumber}` : null,
                            citation.effectiveDate].filter(Boolean).join(" — ")}
                        </li>
                      ))}
                    </ul>
                  )}
                  {run.analysis.reason && <p className="muted">{run.analysis.reason}</p>}
                </>
              )}
              <div className="chips">
                <Pill tone="purple">{run.prototype.code}</Pill>
              </div>
              <p className="muted">
                Reproducibility boundary: {run.prototype.liveDependencies.join(", ")} were not
                frozen by this release.
              </p>
            </>
          )}
        </div>
      )}

      {tab === "history" && (
        <div className="card" data-testid="history-panel">
          <h2>Run history</h2>
          <p className="muted" data-testid="purge-note">
            Deleting a run removes Lab-owned rows only — encrypted payload, transcript, document
            references, and the linked analyzer row. It never deletes the Document Engine package,
            which keeps its own retention lifecycle.
          </p>
          <LabFailure error={historyError} />
          <LabFailure error={purgeError} />
          {purged && (
            <div className="chips">
              <Pill tone="green">purged</Pill>
              <Pill tone="gray">{purged.messagesDeleted} messages</Pill>
              <Pill tone="gray">{purged.exchangesDeleted} exchanges</Pill>
              <Pill tone="gray">{purged.payloadsDeleted} payloads</Pill>
              <Pill tone="gray">
                engine package {purged.enginePackageRetained ? "retained" : "not retained"}
              </Pill>
            </div>
          )}
          {history.length === 0 ? (
            <p className="muted">No runs yet. A run appears here once an analysis reaches a
              terminal state.</p>
          ) : (
            <div className="lab-scroll" data-testid="lab-scroll">
              <table className="tbl">
                <thead>
                  <tr><th>run</th><th>state</th><th>package</th><th>revision</th><th>started</th>
                    <th>turns</th><th /></tr>
                </thead>
                <tbody>
                  {history.map((row) => (
                    <tr key={row.runId} className={run?.runId === row.runId ? "detail-row" : ""}>
                      <td>{row.runId.slice(0, 8)}</td>
                      <td>{row.status}{row.failureCode ? ` · ${row.failureCode}` : ""}</td>
                      <td>{row.packageId ? row.packageId.slice(0, 8) : "—"}</td>
                      <td>{row.revision ?? "—"}</td>
                      <td>{row.createdAt ?? "—"}</td>
                      <td>{row.discussionExchangeCount}</td>
                      <td>
                        <div className="row-actions">
                          <button onClick={() => openRun(row.runId)}>
                            Open run {row.runId.slice(0, 8)}
                          </button>
                          {/* The only way to analyze an existing package again: this prototype
                              has no field for entering an arbitrary package or revision id. */}
                          {row.packageId !== null && row.revision !== null && (
                            <button
                              onClick={() => startRun(row.packageId!, row.revision!)}
                              disabled={running}
                            >
                              Run again from {row.runId.slice(0, 8)}
                            </button>
                          )}
                          {armedDelete === row.runId ? (
                            <>
                              <button onClick={() => confirmPurge(row.runId)}>
                                Confirm delete {row.runId.slice(0, 8)}
                              </button>
                              <button onClick={() => setArmedDelete(null)}>Cancel</button>
                            </>
                          ) : (
                            <button onClick={() => setArmedDelete(row.runId)}>
                              Delete run {row.runId.slice(0, 8)}
                            </button>
                          )}
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}

          <h2>Discussion</h2>
          {!run ? (
            <p className="muted">Open a run to read or continue its discussion.</p>
          ) : (
            <>
              <p className="muted" data-testid="discussion-pinning">
                Pinned to run {run.runId}: the same package, engine-result revision, release, and
                terminal output every turn. A question needing different inputs needs a new run.
                Still live prototype dependencies:{" "}
                {run.prototype.liveDependencies.join(", ")}.
              </p>
              <LabFailure error={discussionError} />
              {discussion?.reviewDrift?.changed && (
                <p className="lab-warning">
                  {`Reviewer changes were recorded after this run (corrected `
                    + `${discussion.reviewDrift.correctedBefore}`
                    + `→${discussion.reviewDrift.correctedNow}, rejected `
                    + `${discussion.reviewDrift.rejectedBefore}`
                    + `→${discussion.reviewDrift.rejectedNow}). The stored analysis predates `
                    + `them. Re-run to analyze current values.`}
                </p>
              )}
              {discussion?.exchanges.length === 0 && (
                <p className="muted">No turns on this run yet.</p>
              )}
              {discussion?.exchanges.map((exchange) => (
                <div key={exchange.exchangeId} className="chunk">
                  <div className="chunk-head">
                    <strong>exchange {exchange.sequenceNumber}</strong>
                    <Pill tone={exchange.status === "SUCCEEDED" ? "green" : "amber"}>
                      {exchange.status}
                    </Pill>
                    {exchange.failureCode && <Pill tone="amber">{exchange.failureCode}</Pill>}
                    {exchange.prototypeLimitations
                      && <Pill tone="purple">{exchange.prototypeLimitations}</Pill>}
                  </div>
                  {exchange.messages.map((message) => (
                    <p key={message.ordinal} className="answer">
                      <strong>{message.role}: </strong>{message.body}
                    </p>
                  ))}
                </div>
              ))}
              {run.status === "SUCCEEDED" && (
                <div className="ask-bar">
                  <textarea
                    className="console-textarea"
                    aria-label="Discussion question"
                    value={question}
                    onChange={(e) => setQuestion(e.target.value)}
                    placeholder="Ask about this run's parsed facts and analysis"
                  />
                  <button className="btn-primary" onClick={send}
                          disabled={sending || !question.trim()}>
                    {sending ? "Sending…" : "Send"}
                  </button>
                </div>
              )}
            </>
          )}
        </div>
      )}
    </>
  );
}
