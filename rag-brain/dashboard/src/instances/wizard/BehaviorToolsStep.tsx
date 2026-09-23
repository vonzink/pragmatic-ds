import { StepViolations, fieldErrorProps } from "./StepProps";
import type { StepProps } from "./StepProps";

/**
 * What the instance is told to do, and what it may compute before being told.
 *
 * Prompts are the one part of a release the dashboard writes and can never read back. A manifest
 * carries prompt text, and manifests are deliberately not served, so editing an existing release's
 * prompts means authoring them again. That is a real cost and it is the right trade: a route that
 * returned prompts would put them in every reader's reach for the sake of a convenience.
 *
 * Tools are pinned by all four fields of their identity, not by name. A contract matching on name
 * alone would resolve to a different build's tool with the same name and silently compute
 * something else.
 */
export default function BehaviorToolsStep(
  { draft, onEdit, violations, options, stepId, issues }: StepProps,
) {
  const available = options?.tools ?? [];
  const chosen = new Set(draft.tools.map((t) => `${t.name}:${t.version}`));

  return (
    <div className="wizard-step">
      <StepViolations violations={violations} />

      <label>
        System prompt
        {/* One refusal covers both prompts, so both textareas describe the same message rather
            than one of them carrying a sentence that is equally about the other. */}
        <textarea
          rows={4}
          {...fieldErrorProps(stepId, issues, "prompts")}
          value={draft.systemPrompt}
          onChange={(e) => onEdit({ systemPrompt: e.target.value })}
        />
        <span className="field-hint">
          Kept by the release and never served back. Editing it later means writing it again.
        </span>
      </label>

      <label>
        Task prompt
        <textarea
          rows={4}
          {...fieldErrorProps(stepId, issues, "prompts")}
          value={draft.taskPrompt}
          onChange={(e) => onEdit({ taskPrompt: e.target.value })}
        />
      </label>

      <label>
        Retrieval query
        <textarea
          rows={2}
          value={draft.retrievalQuery}
          onChange={(e) => onEdit({ retrievalQuery: e.target.value })}
        />
        <span className="field-hint">
          What this instance looks for in its corpus. Optional; empty retrieves nothing.
        </span>
      </label>

      <label>
        Temperature
        <input
          type="text"
          {...fieldErrorProps(stepId, issues, "temperature")}
          value={draft.temperature}
          onChange={(e) => onEdit({ temperature: e.target.value })}
        />
        <span className="field-hint">
          Between 0 and 1. Zero is the only value that makes two runs of one release comparable.
        </span>
      </label>

      <h3>Deterministic tools</h3>
      {available.length === 0 ? (
        <p className="muted">
          This build ships no pre-computable tools, so a release cannot pin one. That is the normal
          state, not a misconfiguration.
        </p>
      ) : (
        <ul className="collection-choices">
          {available.map((tool) => {
            const key = `${tool.name}:${tool.version}`;
            return (
              <li key={key}>
                <label>
                  <input
                    type="checkbox"
                    checked={chosen.has(key)}
                    onChange={() => onEdit({
                      tools: chosen.has(key)
                        ? draft.tools.filter((t) => `${t.name}:${t.version}` !== key)
                        // Every field of the identity travels, because a contract must match all
                        // four or the release fails with TOOL_NOT_REGISTERED at validation.
                        : [...draft.tools, { ...tool }],
                    })}
                  />
                  {tool.name}
                  <span className="muted"> v{tool.version}</span>
                </label>
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}
