import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { instanceApi } from "../api";
import { asRows } from "../rows";
import CorpusCollectionPanel from "../components/CorpusCollectionPanel";
import ModelCandidatePanel from "../components/ModelCandidatePanel";
import type {
  CatalogModelView,
  CollectionSummary,
  ConfigurationView,
  InstanceDetail,
  PointerHistoryView,
  ReleaseSummary,
} from "../types";

/**
 * What a release is configured to do, and what changing it would mean.
 *
 * ### Nothing here edits a release
 *
 * A release is immutable, which is what makes a run reproducible and an evaluation meaningful. So
 * this screen reads a release and offers to *author a new candidate* from it. Every control says
 * so, because the alternative reading — that changing a select changes production — is the single
 * most expensive misunderstanding this product could invite.
 *
 * ### One thing it cannot carry forward
 *
 * Prompt text is never served to the dashboard. A configuration read carries the parser contract,
 * the model, the pinned collections, the tools, the output schema, the limits and the evaluation
 * gate — everything except `behavior`. So authoring a candidate from an existing release means
 * writing its prompts again, and the screen says that up front rather than after five steps.
 *
 * That is the cost of not putting prompts in reach of every reader, and it is worth paying.
 */
export default function InstanceConfiguration(
  { brainId, instanceSlug, instance }: {
    brainId: string;
    instanceSlug: string;
    instance: InstanceDetail | null;
  },
) {
  const [releases, setReleases] = useState<ReleaseSummary[]>([]);
  const [selected, setSelected] = useState<string | null>(null);
  const [configuration, setConfiguration] = useState<ConfigurationView | null>(null);
  const [collections, setCollections] = useState<CollectionSummary[]>([]);
  const [models, setModels] = useState<CatalogModelView[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [nonce, setNonce] = useState(0);

  const navigate = useNavigate();
  const base = `/api/ai/admin/instances/${encodeURIComponent(instanceSlug)}`;
  const brainQuery = `brain=${encodeURIComponent(brainId)}`;

  useEffect(() => {
    let cancelled = false;
    setError(null);

    Promise.all([
      instanceApi.get<ReleaseSummary[]>(`${base}/releases?${brainQuery}`),
      instanceApi.get<PointerHistoryView>(`${base}/pointer?${brainQuery}`),
      instanceApi.get<CollectionSummary[]>(
        `/api/ai/admin/instances/corpus-collections?${brainQuery}`),
      instanceApi.get<CatalogModelView[]>("/api/ai/admin/instances/model-catalog"),
    ])
      .then(([history, pointer, corpus, catalog]) => {
        if (cancelled) return;
        const rows = asRows(history);
        setReleases(rows);
        setCollections(asRows(corpus));
        setModels(asRows(catalog));
        // Live by default: "what is this instance configured to do" almost always means what
        // production is doing, not what somebody drafted.
        setSelected((current) => current
          ?? pointer?.liveReleaseId
          ?? rows[0]?.releaseId
          ?? null);
      })
      .catch((e) => { if (!cancelled) setError((e as Error).message); });

    return () => { cancelled = true; };
  }, [base, brainQuery, nonce]);

  useEffect(() => {
    if (!selected) { setConfiguration(null); return; }
    let cancelled = false;

    instanceApi
      .get<ConfigurationView>(
        `${base}/releases/${encodeURIComponent(selected)}/configuration?${brainQuery}`)
      .then((view) => { if (!cancelled) setConfiguration(view); })
      .catch((e) => {
        if (cancelled) return;
        setConfiguration(null);
        setError((e as Error).message);
      });

    return () => { cancelled = true; };
  }, [base, brainQuery, selected]);

  return (
    <div className="workbench">
      <h1>Configuration</h1>

      {error && <div className="error-note" role="alert">{error}</div>}

      <section className="card workbench-card">
        <h2>Reading</h2>
        <label>
          Release
          <select value={selected ?? ""} onChange={(e) => setSelected(e.target.value || null)}>
            {releases.map((release) => (
              <option key={release.releaseId} value={release.releaseId}>
                r{release.releaseNumber}{release.live ? " (live)" : " (candidate)"}
              </option>
            ))}
          </select>
        </label>
        <p className="field-hint">
          Releases are immutable. Nothing on this screen changes one; changing configuration means
          authoring a new candidate, which production does not answer with until it is promoted.
        </p>
        {instance?.hasCandidateRelease && (
          <p className="muted">
            This instance has a candidate production is not answering with.
          </p>
        )}
      </section>

      {configuration && (
        <section className="card workbench-card">
          <h3>Parsed data contract</h3>
          <dl className="parsed-facts">
            <div><dt>Envelope</dt><dd>{configuration.parsedData.envelopeVersion}</dd></div>
            <div>
              <dt>Canonicalization</dt>
              <dd>{configuration.parsedData.canonicalizationVersion}</dd>
            </div>
            <div>
              <dt>Allowed types</dt>
              <dd>{configuration.parsedData.allowedDocumentTypes.join(", ") || "None"}</dd>
            </div>
            <div>
              <dt>Required types</dt>
              <dd>{configuration.parsedData.requireAnyDocumentTypes.join(", ") || "None"}</dd>
            </div>
            <div>
              <dt>Minimum documents</dt>
              <dd>{configuration.parsedData.minimumSupportedDocuments}</dd>
            </div>
            <div>
              <dt>Needs review</dt>
              <dd>{configuration.parsedData.reviewRequired === "WARN"
                ? "Warn and continue" : "Refuse the run"}</dd>
            </div>
            <div>
              <dt>Missing fields</dt>
              <dd>{configuration.parsedData.missingFields === "PRESERVE"
                ? "Preserve the gap" : "Refuse the run"}</dd>
            </div>
          </dl>
        </section>
      )}

      <ModelCandidatePanel
        configuration={configuration}
        models={models}
        onAuthorCandidate={() => navigate("/instances/new")}
      />

      {configuration && (
        <CorpusCollectionPanel
          brainId={brainId}
          collections={collections}
          pinned={configuration.corpus.map((ref) => ({
            collectionId: ref.collectionId,
            collectionVersion: ref.collectionVersion,
          }))}
          onChanged={() => setNonce((n) => n + 1)}
        />
      )}

      {configuration && (
        <section className="card workbench-card">
          <h3>Behaviour, output and limits</h3>

          <p className="warn-note" role="status">
            Prompts are not shown here and are served nowhere. Authoring a candidate from this
            release means writing its system and task prompts again; everything else on this screen
            carries forward.
          </p>

          <dl className="parsed-facts">
            <div>
              <dt>Prompts</dt>
              <dd>{configuration.behaviorPresent
                ? "Set, and not readable"
                : <span className="qualifier">Absent</span>}</dd>
            </div>
            <div><dt>Tools</dt><dd>{configuration.tools.length}</dd></div>
            <div><dt>Output schema</dt><dd>{configuration.output.schemaId}</dd></div>
            <div>
              <dt>Max input tokens</dt>
              <dd>{configuration.limits.maximumInputTokens.toLocaleString()}</dd>
            </div>
            <div>
              <dt>Max retrieved tokens</dt>
              <dd>{configuration.limits.maximumRetrievedTokens.toLocaleString()}</dd>
            </div>
            <div>
              <dt>Max output tokens</dt>
              <dd>{configuration.limits.maximumOutputTokens.toLocaleString()}</dd>
            </div>
            <div>
              <dt>Discussion tokens</dt>
              <dd>{configuration.limits.maximumDiscussionTokens === 0
                ? "Follow-up questions are off"
                : configuration.limits.maximumDiscussionTokens.toLocaleString()}</dd>
            </div>
            <div>
              <dt>Concurrent runs</dt>
              <dd>{configuration.limits.maximumConcurrentRuns}</dd>
            </div>
            <div>
              <dt>Max expected cost</dt>
              <dd>${configuration.limits.maximumExpectedCostUsd}</dd>
            </div>
            <div>
              <dt>Evaluation gate</dt>
              <dd>
                {configuration.evaluations.scenarioSetId} v
                {configuration.evaluations.scenarioSetVersion}
                <span className="muted"> at {configuration.evaluations.minimumScore}</span>
              </dd>
            </div>
          </dl>
        </section>
      )}
    </div>
  );
}
