import { useState } from "react";
import type { CatalogModelView, ConfigurationView } from "../types";

/**
 * Changing the model, which means authoring a release rather than editing one.
 *
 * A release is immutable. Nothing on this screen edits one, and the panel is written to make that
 * impossible to misread: choosing a different model does not change anything until a new candidate
 * exists, and even then production keeps answering with whatever it answered with before.
 *
 * ### Why this hands off to the wizard
 *
 * A candidate carries a complete manifest, prompts included, and prompt text is never served to
 * the dashboard — so this panel can show what the current release is configured with, and can
 * carry every part of it forward except the two prompts, which have to be written again.
 *
 * That is a real cost of never serving prompts, and it is stated here rather than discovered after
 * someone has filled in five steps.
 */
export default function ModelCandidatePanel(
  { configuration, models, onAuthorCandidate }: {
    configuration: ConfigurationView | null;
    models: CatalogModelView[];
    onAuthorCandidate: (provider: string, model: string) => void;
  },
) {
  const [chosen, setChosen] = useState("");

  if (!configuration) {
    return (
      <section className="card workbench-card">
        <h3>Model</h3>
        <p className="muted">No release to read a model from yet.</p>
      </section>
    );
  }

  const current = `${configuration.model.provider} ${configuration.model.model}`;
  const target = chosen || current;
  const changed = target !== current;
  const offered = models.some(
    (m) => `${m.provider} ${m.model}` === current);

  return (
    <section className="card workbench-card">
      <h3>Model</h3>

      <dl className="parsed-facts">
        <div>
          <dt>Currently configured</dt>
          <dd>{configuration.model.provider} / {configuration.model.model}</dd>
        </div>
        <div>
          <dt>If unavailable</dt>
          <dd>{configuration.model.fallbackPolicy === "NONE"
            ? "Fail the run" : "Use the configured fallback"}</dd>
        </div>
      </dl>

      {!offered && (
        // Not hypothetical: a credential removed after a release was authored leaves a live
        // release naming a model nothing can dispatch to, and the promotion gate will say so.
        <p className="warn-note" role="status">
          This deployment no longer offers that model. Runs of this release will fail at dispatch
          until a candidate naming a configured model is promoted.
        </p>
      )}

      <label>
        Change the model to
        <select value={target} onChange={(e) => setChosen(e.target.value)}>
          {!offered && <option value={current}>{configuration.model.model} (not configured)</option>}
          {models.map((m) => (
            <option key={`${m.provider}/${m.model}`} value={`${m.provider} ${m.model}`}>
              {m.provider} / {m.model}
            </option>
          ))}
        </select>
      </label>

      <p className="field-hint">
        Changing a model authors a new candidate release carrying it. It does not edit this release
        and it does not change what production answers with. The candidate has to be evaluated and
        promoted, which are separate acts.
      </p>

      <p className="warn-note" role="status">
        Prompts are never served back to this dashboard, so a new candidate needs its system and
        task prompts written again. Everything else carries forward.
      </p>

      <button
        type="button"
        className="btn-primary"
        disabled={!changed}
        onClick={() => {
          const [provider, model] = target.split(" ");
          onAuthorCandidate(provider ?? "", model ?? "");
        }}
      >
        {changed ? "Author a candidate with this model" : "Choose a different model"}
      </button>
    </section>
  );
}
