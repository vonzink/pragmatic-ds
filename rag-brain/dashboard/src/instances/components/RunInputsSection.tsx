import ParsedDataInputCard from "./ParsedDataInputCard";
import CorpusSnapshotCard from "./CorpusSnapshotCard";
import type { RunInputs } from "../hooks/useRunInputs";

/**
 * The parsed revision and the corpus snapshot, in the order they have to happen.
 *
 * The corpus card appears only after a compatible parse, because until then there is nothing to
 * freeze a corpus *for* — and offering the second step while the first is unresolved invites
 * assembling a whole run that the server will refuse.
 *
 * Shared by the workbench and by Compare. A comparison holds these two equal across every member
 * by construction: they are chosen once, here, rather than per member, which is what makes the
 * server's basis check something the screen satisfies rather than something it hopes for.
 */
export default function RunInputsSection(
  { brainId, inputs }: { brainId: string; inputs: RunInputs },
) {
  return (
    <>
      <ParsedDataInputCard
        parsed={inputs.parsed}
        busy={inputs.parsedBusy}
        error={inputs.parsedError}
        onSelectExisting={inputs.pin}
        onUpload={inputs.upload}
      />

      {inputs.awaitingParse && (
        <div className="warn-note" role="status">
          <p>
            The engine has the upload and has not produced a revision yet. Checking again pins the
            newest revision of that package — it does not upload anything a second time.
          </p>
          <button
            type="button"
            className="btn"
            disabled={inputs.parsedBusy}
            onClick={() => inputs.pin(inputs.awaitingParse!, null, [])}
          >
            {inputs.parsedBusy ? "Checking…" : "Check again"}
          </button>
        </div>
      )}

      {inputs.parsed && inputs.parsed.compatibility.compatible && (
        <CorpusSnapshotCard
          brainId={brainId}
          snapshot={inputs.snapshot}
          busy={inputs.snapshotBusy}
          error={inputs.snapshotError}
          onFreeze={inputs.freeze}
        />
      )}
    </>
  );
}
