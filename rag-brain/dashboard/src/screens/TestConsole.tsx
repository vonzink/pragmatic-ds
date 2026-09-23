import { useMemo, useState } from "react";
import { api, learningApi, legacyAskPath, publicAsk } from "../api";
import { AskResponse, FeedbackRating, PublicAskResponse, RetrievalResult } from "../types";
import { ThumbsDown, ThumbsUp } from "lucide-react";
import { ConfidenceMeter, ErrorNote, Meter, Pill, Status, scoreTone } from "../components";

export default function TestConsole({ slug }: { slug: string }) {
  const [mode, setMode] = useState<"ask" | "retrieval" | "public">("ask");
  const [question, setQuestion] = useState("What is PMI?");
  const [pageRoute, setPageRoute] = useState("");
  const [surface, setSurface] = useState("PUBLIC");
  const [publicToken, setPublicToken] = useState("");
  const [factsText, setFactsText] = useState("{}");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [answer, setAnswer] = useState<AskResponse | null>(null);
  const [publicAnswer, setPublicAnswer] = useState<PublicAskResponse | null>(null);
  const [retrieval, setRetrieval] = useState<RetrievalResult | null>(null);
  const [elapsed, setElapsed] = useState<number | null>(null);
  const [sessionId] = useState(() => `dashboard-${crypto.randomUUID()}`);
  const [feedbackVote, setFeedbackVote] = useState<FeedbackRating | null>(null);
  const [feedbackError, setFeedbackError] = useState<string | null>(null);
  const normalizedOrigin = useMemo(() => window.location.origin, []);

  function parseFacts(): Record<string, unknown> {
    const raw = factsText.trim();
    if (!raw) return {};
    const parsed = JSON.parse(raw) as unknown;
    if (!parsed || Array.isArray(parsed) || typeof parsed !== "object") {
      throw new Error("Facts must be a JSON object");
    }
    return parsed as Record<string, unknown>;
  }

  async function run() {
    setBusy(true);
    setError(null);
    setAnswer(null);
    setPublicAnswer(null);
    setRetrieval(null);
    setElapsed(null);
    setFeedbackVote(null);
    setFeedbackError(null);
    const start = performance.now();
    try {
      if (!slug.trim()) {
        throw new Error("Active brain slug is unavailable");
      }
      if (mode === "ask") {
        setAnswer(await api.post<AskResponse>(legacyAskPath(slug), {
          sessionId,
          question,
          pageRoute: pageRoute.trim() || null,
          surface,
        }));
      } else if (mode === "public") {
        if (!publicToken.trim()) {
          throw new Error("Public token is required");
        }
        setPublicAnswer(await publicAsk(slug, publicToken.trim(), {
          sessionId,
          message: question,
          pageRoute: pageRoute.trim() || null,
          surface: "PUBLIC",
          facts: parseFacts(),
        }));
      } else {
        setRetrieval(await api.get<RetrievalResult>(
          `/api/ai/documents/test-retrieval?question=${encodeURIComponent(question)}`));
      }
      setElapsed((performance.now() - start) / 1000);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  const publicTone = (responseType: PublicAskResponse["responseType"]): "ok" | "warn" | "inactive" => {
    if (responseType === "ANSWER") return "ok";
    if (responseType === "CLARIFY") return "warn";
    return "inactive";
  };

  async function vote(traceId: string, rating: FeedbackRating) {
    setFeedbackError(null);
    setFeedbackVote(rating); // optimistic; disables both buttons immediately
    try {
      if (mode === "public") {
        // Same call the public embeddable widget uses:
        //   learningApi.submitFeedback(slug, publicBrainToken, sessionId, { traceId, rating, reason })
        await learningApi.submitFeedback(slug, publicToken.trim(), sessionId, {
          traceId,
          rating,
          reason: null,
        });
      } else {
        // "Full ask" (admin-key) mode has no public brain token; rate the trace
        // through the admin-key-authed endpoint instead.
        await learningApi.submitAdminFeedback(traceId, rating, null);
      }
    } catch (e) {
      setFeedbackVote(null); // re-enable so the user can retry
      setFeedbackError((e as Error).message);
    }
  }

  function feedbackBar(traceId: string) {
    return (
      <div className="feedback-bar">
        <button
          onClick={() => vote(traceId, "UP")}
          disabled={feedbackVote !== null}
          className={feedbackVote === "UP" ? "on" : ""}
        >
          <ThumbsUp size={14} strokeWidth={1.5} aria-hidden="true" /> Helpful
        </button>
        <button
          onClick={() => vote(traceId, "DOWN")}
          disabled={feedbackVote !== null}
          className={feedbackVote === "DOWN" ? "on" : ""}
        >
          <ThumbsDown size={14} strokeWidth={1.5} aria-hidden="true" /> Not helpful
        </button>
        {feedbackVote !== null && !feedbackError && <span className="muted">thanks for the feedback</span>}
        {feedbackError && <span className="error-note">{feedbackError}</span>}
      </div>
    );
  }

  return (
    <>
      <header className="screen-head">
        <h1>Test console</h1>
        <div className="mode-toggle">
          <button className={mode === "ask" ? "on" : ""} onClick={() => setMode("ask")}>Full ask</button>
          <button className={mode === "public" ? "on" : ""} onClick={() => setMode("public")}>Public ask</button>
          <button className={mode === "retrieval" ? "on" : ""} onClick={() => setMode("retrieval")}>Retrieval only</button>
        </div>
      </header>
      <div className="ask-bar">
        <input value={question} onChange={(e) => setQuestion(e.target.value)}
               onKeyDown={(e) => e.key === "Enter" && !busy && run()} />
        <button className="btn-primary" onClick={run} disabled={busy || !question.trim() || !slug.trim()}>
          {busy ? "Working…" : mode === "retrieval" ? "Retrieve" : "Ask"}
        </button>
      </div>
      <div className="ask-bar" style={{ marginTop: 8 }}>
        <input placeholder="page route (optional)" value={pageRoute}
               onChange={(e) => setPageRoute(e.target.value)} />
        {mode === "ask" ? (
          <select value={surface} onChange={(e) => setSurface(e.target.value)}>
            <option value="PUBLIC">public</option>
            <option value="INTERNAL">internal</option>
            <option value="BOTH">both</option>
          </select>
        ) : mode === "public" ? (
          <input placeholder="public token" value={publicToken} onChange={(e) => setPublicToken(e.target.value)} />
        ) : (
          <input value={slug} readOnly />
        )}
      </div>
      {mode === "public" && (
        <>
          <div className="ask-bar" style={{ marginTop: 8 }}>
            <input value={normalizedOrigin} readOnly />
            <span className="muted">browser origin used for public requests</span>
          </div>
          <textarea
            className="console-textarea"
            value={factsText}
            onChange={(e) => setFactsText(e.target.value)}
            placeholder='{"loanType":"fha"}'
          />
        </>
      )}
      <ErrorNote message={error} />
      {answer && (
        <div className="card">
          <div className="chips">
            <Status kind={answer.humanEscalationRequired ? "escalated" : "ok"}>
              {answer.humanEscalationRequired ? "escalated" : "grounded"}</Status>
            <ConfidenceMeter value={answer.confidence} />
            <span className="run-sum">
              · {answer.citations.length} citations{elapsed !== null ? ` · ${elapsed.toFixed(1)}s` : ""}
            </span>
            {answer.traceId && <Pill tone="gray">trace {answer.traceId.slice(0, 8)}</Pill>}
          </div>
          <p className="answer">{answer.answer}</p>
          {answer.citations.length > 0 && (
            <ul className="citations">
              {answer.citations.map((c, i) => (
                <li key={i}>{[c.source_name, c.section, c.page_number ? `p. ${c.page_number}` : null]
                  .filter(Boolean).join(" — ")}</li>
              ))}
            </ul>
          )}
          {answer.recommendedPage && (
            <p className="muted">Recommended page: {answer.recommendedPage.label} ({answer.recommendedPage.route})</p>
          )}
          {answer.links && answer.links.length > 0 && (
            <ul className="citations">
              {answer.links.map((l, i) => (
                <li key={i}><a href={l.url} target="_blank" rel="noreferrer">{l.name}</a> · {l.authority.toLowerCase()}</li>
              ))}
            </ul>
          )}
          {answer.nextAction && <p className="muted">{answer.nextAction}</p>}
          <p className="muted">{answer.disclaimer}</p>
          {answer.traceId && feedbackBar(answer.traceId)}
        </div>
      )}
      {publicAnswer && (
        <div className="card">
          <div className="chips">
            <Status kind={publicTone(publicAnswer.responseType)}>
              {publicAnswer.responseType.toLowerCase()}
            </Status>
            <ConfidenceMeter value={publicAnswer.confidence} />
            <span className="run-sum">
              {publicAnswer.citations.length > 0 ? `· ${publicAnswer.citations.length} citations` : ""}
              {elapsed !== null ? ` · ${elapsed.toFixed(1)}s` : ""}
            </span>
          </div>
          {publicAnswer.message && <p className="muted">{publicAnswer.message}</p>}
          {publicAnswer.clarifyingQuestion && <p className="answer">{publicAnswer.clarifyingQuestion}</p>}
          {publicAnswer.answer && <p className="answer">{publicAnswer.answer}</p>}
          {publicAnswer.missingFacts.length > 0 && (
            <p className="muted">Missing: {publicAnswer.missingFacts.join(", ")}</p>
          )}
          {publicAnswer.citations.length > 0 && (
            <ul className="citations">
              {publicAnswer.citations.map((c, i) => (
                <li key={i}>{[c.source_name, c.section, c.page_number ? `p. ${c.page_number}` : null]
                  .filter(Boolean).join(" — ")}</li>
              ))}
            </ul>
          )}
          {publicAnswer.recommendedPages.length > 0 && (
            <ul className="citations">
              {publicAnswer.recommendedPages.map((p) => (
                <li key={p.url}>{p.label} ({p.url}) - {p.reason}</li>
              ))}
            </ul>
          )}
          {publicAnswer.nextAction && <p className="muted">{publicAnswer.nextAction}</p>}
          {publicAnswer.traceId && feedbackBar(publicAnswer.traceId)}
        </div>
      )}
      {retrieval && (
        <div className="card">
          <div className="chips">
            <Status kind={retrieval.sufficientEvidence ? "ok" : "warn"}>
              {retrieval.sufficientEvidence ? "sufficient evidence" : "insufficient evidence"}</Status>
            <ConfidenceMeter value={retrieval.confidence} />
            <span className="run-sum">
              · {retrieval.chunks.length} chunks{elapsed !== null ? ` · ${elapsed.toFixed(1)}s` : ""}
            </span>
          </div>
          {retrieval.chunks.map((chunk, i) => (
            <div key={i} className="chunk">
              <div className="chunk-head">
                <strong>{chunk.sourceName}</strong>
                <span className="muted">{[chunk.section, chunk.pageNumber ? `p. ${chunk.pageNumber}` : null]
                  .filter(Boolean).join(" — ")}</span>
                <span className="conf" style={{ marginLeft: "auto" }}>
                  <Meter ratio={chunk.combinedScore} tone={scoreTone(chunk.combinedScore)} size="sm" />
                  <span className="val">{chunk.combinedScore.toFixed(2)}</span>
                </span>
              </div>
              <p>{chunk.content.length > 280 ? `${chunk.content.slice(0, 280)}…` : chunk.content}</p>
            </div>
          ))}
        </div>
      )}
    </>
  );
}
