import { useCallback, useEffect, useMemo, useState } from "react";
import { brainsApi, profileApi, publicAsk } from "../api";
import {
  BrainAdminDto,
  BrainProfileDto,
  BrainProfileRequest,
  BrainReadiness,
  PublicAskResponse,
  SyncReport,
} from "../types";
import { ErrorNote, Pill } from "../components";
import {
  curlSnippet,
  jsFetchSnippet,
  parseDomains,
  pythonSnippet,
  widgetSnippet,
} from "../connect/snippets";

const STEPS = ["Choose brain", "Knowledge", "Voice & compliance", "Publish", "Embed", "Verify"];

type SnippetTab = "widget" | "curl" | "js" | "python";

function profileToRequest(p: BrainProfileDto): BrainProfileRequest {
  const { brainId: _brainId, ...rest } = p;
  return rest;
}

function CopyButton({ text }: { text: string }) {
  const [copied, setCopied] = useState(false);
  return (
    <button
      onClick={async () => {
        try {
          await navigator.clipboard.writeText(text);
          setCopied(true);
          setTimeout(() => setCopied(false), 1500);
        } catch {
          setCopied(false);
        }
      }}
    >
      {copied ? "Copied" : "Copy"}
    </button>
  );
}

export default function Connect() {
  const [brains, setBrains] = useState<BrainAdminDto[]>([]);
  const [brainId, setBrainId] = useState<string>("");
  const [readiness, setReadiness] = useState<BrainReadiness | null>(null);
  const [profile, setProfile] = useState<BrainProfileDto | null>(null);
  const [step, setStep] = useState(0);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [report, setReport] = useState<SyncReport | null>(null);
  const [domainsText, setDomainsText] = useState("");
  const [token, setToken] = useState<string>("");
  const [apiBase, setApiBase] = useState(window.location.origin);
  const [tab, setTab] = useState<SnippetTab>("widget");

  const [verifyQuestion, setVerifyQuestion] = useState("What can you help me with?");
  const [verifyResult, setVerifyResult] = useState<PublicAskResponse | null>(null);
  const [verifyError, setVerifyError] = useState<string | null>(null);

  const brain = useMemo(() => brains.find((b) => b.id === brainId) ?? null, [brains, brainId]);

  useEffect(() => {
    brainsApi
      .list()
      .then((list) => {
        setBrains(list);
        if (!brainId && list.length) {
          const active = list.find((b) => b.isDefault) ?? list[0];
          setBrainId(active.id);
        }
      })
      .catch((e) => setError((e as Error).message));
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  const loadBrain = useCallback((id: string) => {
    if (!id) return;
    setError(null);
    Promise.all([brainsApi.readiness(id), profileApi.get(id)])
      .then(([r, p]) => {
        setReadiness(r);
        setProfile(p);
        setDomainsText(p.allowedDomains.join("\n"));
      })
      .catch((e) => setError((e as Error).message));
  }, []);

  useEffect(() => {
    loadBrain(brainId);
  }, [brainId, loadBrain]);

  const reloadReadiness = useCallback(() => {
    if (brainId) brainsApi.readiness(brainId).then(setReadiness).catch(() => undefined);
  }, [brainId]);

  async function saveProfile(patch: Partial<BrainProfileRequest>) {
    if (!profile) return;
    setBusy(true);
    setError(null);
    try {
      const body: BrainProfileRequest = { ...profileToRequest(profile), ...patch };
      const updated = await profileApi.update(brainId, body);
      setProfile(updated);
      setDomainsText(updated.allowedDomains.join("\n"));
      reloadReadiness();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  async function runSync() {
    setBusy(true);
    setError(null);
    setReport(null);
    try {
      setReport(await brainsApi.sync(brainId, false));
      reloadReadiness();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  async function generateToken() {
    setBusy(true);
    setError(null);
    try {
      const res = await profileApi.rotatePublicToken(brainId);
      setToken(res.token);
      reloadReadiness();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  async function runVerify() {
    setVerifyError(null);
    setVerifyResult(null);
    if (!brain) return;
    if (!token) {
      setVerifyError("Generate a public token in the Publish step first (it is only shown once).");
      return;
    }
    setBusy(true);
    try {
      const res = await publicAsk(brain.slug, token, {
        sessionId: `installer-${Date.now()}`,
        conversationId: null,
        message: verifyQuestion,
        pageRoute: "/",
        surface: "PUBLIC",
        facts: {},
      });
      setVerifyResult(res);
    } catch (e) {
      setVerifyError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  const snippetParams = { apiBase, slug: brain?.slug ?? "", token, title: brain?.displayName };
  const snippet =
    tab === "widget"
      ? widgetSnippet(snippetParams)
      : tab === "curl"
        ? curlSnippet(snippetParams)
        : tab === "js"
          ? jsFetchSnippet(snippetParams)
          : pythonSnippet(snippetParams);

  const parsedDomains = parseDomains(domainsText);

  return (
    <>
      <header className="screen-head">
        <h1>Connect a brain</h1>
        <span className="muted">step-by-step setup to attach this brain to a website or app</span>
      </header>
      <ErrorNote message={error} />

      <div className="card">
        <div className="mode-toggle" style={{ flexWrap: "wrap" }}>
          {STEPS.map((label, i) => (
            <button key={label} className={i === step ? "on" : ""} onClick={() => setStep(i)}>
              {i + 1}. {label}
            </button>
          ))}
        </div>
      </div>

      {step === 0 && (
        <div className="card">
          <h2>Choose the brain to connect</h2>
          <div className="setting-row">
            <label>Brain</label>
            <select value={brainId} onChange={(e) => setBrainId(e.target.value)}>
              {brains.map((b) => (
                <option key={b.id} value={b.id}>
                  {b.displayName} ({b.slug}){b.isDefault ? " — active" : ""}
                </option>
              ))}
            </select>
          </div>
          {readiness && (
            <div className="checklist">
              {readiness.checklist.map((c) => (
                <div key={c.key} className="diff-line">
                  <Pill tone={c.ok ? "green" : "amber"}>{c.ok ? "ready" : "todo"}</Pill>
                  <span>
                    {c.label}
                    {c.hint ? ` — ${c.hint}` : ""}
                  </span>
                </div>
              ))}
              <p className="muted">
                {readiness.ready
                  ? "This brain is ready to embed. Continue to grab a snippet."
                  : "Work through the steps below to make this brain embeddable."}
              </p>
            </div>
          )}
        </div>
      )}

      {step === 1 && (
        <div className="card">
          <h2>Knowledge</h2>
          <p className="muted">
            The assistant only answers from synced, approved sources. Point the brain at a folder or
            bucket on the <strong>Brains</strong> screen, then sync here.
          </p>
          {readiness && (
            <div className="setting-row">
              <label>Indexed content</label>
              <span>
                {readiness.chunks.toLocaleString()} chunks · {readiness.activeDocuments} active documents
              </span>
            </div>
          )}
          <div className="setting-row">
            <button className="btn-primary" onClick={runSync} disabled={busy}>
              {busy ? "Syncing…" : "Sync now"}
            </button>
          </div>
          {report && (
            <div className="sync-summary">
              <strong>Sync finished</strong>
              {Object.entries(report.summary).map(([k, v]) => (
                <Pill key={k} tone="gray">
                  {v} {k}
                </Pill>
              ))}
            </div>
          )}
        </div>
      )}

      {step === 2 && profile && (
        <div className="card">
          <h2>Voice & compliance</h2>
          <div className="setting-row">
            <label>Disclaimer (shown with every answer)</label>
            <textarea
              rows={2}
              value={profile.disclaimer}
              onChange={(e) => setProfile({ ...profile, disclaimer: e.target.value })}
            />
          </div>
          <div className="setting-row">
            <label>Purpose</label>
            <input value={profile.purpose} onChange={(e) => setProfile({ ...profile, purpose: e.target.value })} />
          </div>
          <div className="setting-row">
            <label>Audience</label>
            <input value={profile.audience} onChange={(e) => setProfile({ ...profile, audience: e.target.value })} />
          </div>
          <div className="setting-row">
            <label>Tone</label>
            <input value={profile.tone} onChange={(e) => setProfile({ ...profile, tone: e.target.value })} />
          </div>
          <div className="setting-row">
            <label>Confidence target ({profile.confidenceTarget.toFixed(2)})</label>
            <input
              type="range"
              min={0}
              max={1}
              step={0.05}
              value={profile.confidenceTarget}
              onChange={(e) => setProfile({ ...profile, confidenceTarget: Number(e.target.value) })}
            />
          </div>
          <div className="setting-row">
            <button
              className="btn-primary"
              disabled={busy}
              onClick={() =>
                saveProfile({
                  disclaimer: profile.disclaimer,
                  purpose: profile.purpose,
                  audience: profile.audience,
                  tone: profile.tone,
                  confidenceTarget: profile.confidenceTarget,
                })
              }
            >
              {busy ? "Saving…" : "Save voice & compliance"}
            </button>
          </div>
        </div>
      )}

      {step === 3 && profile && (
        <div className="card">
          <h2>Publish</h2>
          <p className="muted">
            List the website domain(s) allowed to embed this assistant. Saving enables public access
            and authorizes these origins for cross-origin (CORS) requests immediately — no restart.
          </p>
          <div className="setting-row">
            <label>Allowed domains (one per line)</label>
            <textarea
              rows={3}
              value={domainsText}
              onChange={(e) => setDomainsText(e.target.value)}
              placeholder={"https://www.example.com\nexample.com"}
            />
          </div>
          {parsedDomains.length > 0 && (
            <div className="sync-summary">
              {parsedDomains.map((d) => (
                <Pill key={d} tone="blue">
                  {d}
                </Pill>
              ))}
            </div>
          )}
          <div className="setting-row">
            <button
              className="btn-primary"
              disabled={busy || parsedDomains.length === 0}
              onClick={() =>
                saveProfile({ publicEnabled: true, mode: "PUBLIC_SITE", allowedDomains: parsedDomains })
              }
            >
              {busy ? "Saving…" : "Enable public access for these domains"}
            </button>
          </div>
          <hr />
          <div className="setting-row">
            <label>Public token</label>
            <button onClick={generateToken} disabled={busy}>
              {readiness?.hasPublicToken ? "Regenerate token" : "Generate token"}
            </button>
          </div>
          {token ? (
            <div className="token-reveal">
              <p className="error-note">
                Copy this token now — it is shown once and stored only as a hash. Regenerating
                invalidates the previous token.
              </p>
              <div className="diff-line">
                <code>{token}</code>
                <CopyButton text={token} />
              </div>
            </div>
          ) : (
            <p className="muted">
              {readiness?.hasPublicToken
                ? "A token already exists. Regenerate to reveal a new one for the snippet and test."
                : "No token yet."}
            </p>
          )}
        </div>
      )}

      {step === 4 && (
        <div className="card">
          <h2>Embed</h2>
          <div className="setting-row">
            <label>API base URL</label>
            <input value={apiBase} onChange={(e) => setApiBase(e.target.value)} />
          </div>
          {!token && (
            <p className="muted">
              Tip: generate a token in the Publish step to bake it into the snippet (otherwise a
              placeholder is shown).
            </p>
          )}
          <div className="mode-toggle">
            <button className={tab === "widget" ? "on" : ""} onClick={() => setTab("widget")}>
              Widget
            </button>
            <button className={tab === "curl" ? "on" : ""} onClick={() => setTab("curl")}>
              cURL
            </button>
            <button className={tab === "js" ? "on" : ""} onClick={() => setTab("js")}>
              JavaScript
            </button>
            <button className={tab === "python" ? "on" : ""} onClick={() => setTab("python")}>
              Python
            </button>
          </div>
          {tab === "widget" && (
            <p className="muted">Paste before &lt;/body&gt; on any page. It renders a floating chat button.</p>
          )}
          <div className="snippet">
            <div className="snippet-actions">
              <CopyButton text={snippet} />
            </div>
            <pre>
              <code>{snippet}</code>
            </pre>
          </div>
        </div>
      )}

      {step === 5 && (
        <div className="card">
          <h2>Verify</h2>
          <p className="muted">
            Send a live question through the public endpoint exactly as a website would, using the
            token you generated.
          </p>
          <div className="setting-row">
            <label>Test question</label>
            <input value={verifyQuestion} onChange={(e) => setVerifyQuestion(e.target.value)} />
          </div>
          <div className="setting-row">
            <button className="btn-primary" onClick={runVerify} disabled={busy}>
              {busy ? "Asking…" : "Run test"}
            </button>
          </div>
          <ErrorNote message={verifyError} />
          {verifyResult && (
            <div className="verify-result">
              <div className="sync-summary">
                <Pill tone="green">{verifyResult.responseType}</Pill>
                <Pill tone="gray">confidence {verifyResult.confidence.toFixed(2)}</Pill>
                {verifyResult.humanEscalationRequired && <Pill tone="amber">human handoff</Pill>}
              </div>
              <p>{verifyResult.answer ?? verifyResult.message ?? verifyResult.clarifyingQuestion}</p>
              {verifyResult.citations.length > 0 && (
                <p className="ragb-cite muted">
                  Sources:{" "}
                  {verifyResult.citations
                    .map((c) => [c.source_name, c.section].filter(Boolean).join(", "))
                    .filter(Boolean)
                    .join("; ")}
                </p>
              )}
              {verifyResult.disclaimer && <p className="muted">{verifyResult.disclaimer}</p>}
            </div>
          )}
        </div>
      )}

      <div className="card wizard-nav">
        <button onClick={() => setStep((s) => Math.max(0, s - 1))} disabled={step === 0}>
          Back
        </button>
        <button
          className="btn-primary"
          onClick={() => setStep((s) => Math.min(STEPS.length - 1, s + 1))}
          disabled={step === STEPS.length - 1}
        >
          Next
        </button>
      </div>
    </>
  );
}
