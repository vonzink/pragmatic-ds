import { useEffect, useRef, useState } from "react";
import { instanceApi } from "../api";
import type { DiscussionView } from "../types";

/**
 * Follow-up questions about one run's answer.
 *
 * A turn is not a small re-run. It reads what the run already saved — the parsed facts, the
 * evidence it cited, the result it produced — and answers from that. Nothing is reparsed,
 * re-retrieved or re-tooled, and no live pointer moves. That is why the routes take no package,
 * revision or release: a question needing different inputs is a different run, and the API gives
 * you no way to smuggle one in here.
 *
 * The paragraph above is on the screen, not only in this comment, because the alternative is an
 * operator reading a follow-up answer as evidence that the *instance* now behaves that way.
 *
 * ### The key
 *
 * One idempotency key per Send, reused through every retry of that Send. A turn calls a provider
 * and costs money; a key minted per attempt turns one interrupted question into two billed turns
 * and two transcript entries. The key is only replaced once a send has actually landed, so the
 * retry button below carries the same key the failed attempt did.
 */
export default function RunDiscussionPanel(
  { brainId, groupId, runId }: { brainId: string; groupId: string; runId: string },
) {
  const [discussion, setDiscussion] = useState<DiscussionView | null>(null);
  const [question, setQuestion] = useState("");
  const [sending, setSending] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Held across renders and across failures: this identifies the *question*, not the attempt.
  const sendKey = useRef<string | null>(null);

  const path = `/api/ai/admin/instances/run-groups/${encodeURIComponent(groupId)}`
    + `/members/${encodeURIComponent(runId)}/messages`
    + `?brain=${encodeURIComponent(brainId)}`;

  useEffect(() => {
    let cancelled = false;
    instanceApi
      .get<DiscussionView>(path)
      .then((view) => { if (!cancelled) setDiscussion(view); })
      // A transcript that has never been opened is not an error worth a red box; it is empty.
      .catch(() => { if (!cancelled) setDiscussion(null); });
    return () => { cancelled = true; };
  }, [path]);

  async function send() {
    const asked = question.trim();
    if (!asked || sending) return;
    if (sendKey.current === null) sendKey.current = crypto.randomUUID();

    setSending(true);
    setError(null);
    try {
      const view = await instanceApi.postIdempotent<DiscussionView>(
        path, { question: asked }, sendKey.current);
      setDiscussion(view);
      setQuestion("");
      // Landed. The next question is a new action and earns its own key.
      sendKey.current = null;
    } catch (e) {
      // Deliberately keeps the key and the text: pressing Send again retries *this* question.
      setError((e as Error).message);
    } finally {
      setSending(false);
    }
  }

  const turns = discussion?.turns ?? [];

  return (
    <section className="card workbench-card">
      <h2>Discussion</h2>
      <p className="muted">
        Answers from what this run saved — its parsed facts, its evidence, and its result. Nothing
        is reparsed, re-retrieved or re-run, and the live release does not change. A question that
        needs different inputs is a new run.
      </p>

      {turns.length > 0 && (
        <ol className="turn-list">
          {turns.map((turn) => (
            <li key={turn.sequenceNumber}>
              {/* A failed turn is kept rather than hidden: it was asked, and it cost the attempt.
                  Both bodies are absent because nothing was stored for it. */}
              {turn.question !== null && <p className="turn-question">{turn.question}</p>}
              {turn.answer !== null && <p className="turn-answer">{turn.answer}</p>}
              {turn.status !== "SUCCEEDED" && (
                <p className="error-note">
                  {turn.status}
                  {turn.failureCode && <span className="mono"> {turn.failureCode}</span>}
                </p>
              )}
            </li>
          ))}
        </ol>
      )}

      {discussion && (
        <p className="muted">
          {discussion.provider && discussion.model
            ? `Answered by ${discussion.provider} / ${discussion.model}. `
            : ""}
          {discussion.estimatedCostUsd === null || discussion.estimatedCostUsd === undefined
            // Never zero — and never a bare "$" either. The wire sends null, but a response shaped
            // differently than expected must degrade to "Unavailable", not to a dollar sign with
            // nothing after it. Screenshot verification caught exactly that.
            ? <>Turn cost <span className="qualifier">Unavailable</span></>
            : <>Turns so far ${discussion.estimatedCostUsd}
              <span className="qualifier"> Estimated</span></>}
          {discussion.usageQuality === "UNAVAILABLE" && (
            <span className="qualifier"> · a turn went unmeasured, so this total understates</span>
          )}
        </p>
      )}

      {error && <div className="error-note" role="alert">{error}</div>}

      <form className="parse-form" onSubmit={(e) => { e.preventDefault(); void send(); }}>
        <label>
          Question
          <textarea
            rows={2}
            value={question}
            onChange={(e) => setQuestion(e.target.value)}
            placeholder="Ask about this run's answer"
          />
        </label>
        <button className="btn" type="submit" disabled={sending || !question.trim()}>
          {sending ? "Asking…" : error ? "Retry" : "Send"}
        </button>
      </form>
    </section>
  );
}
