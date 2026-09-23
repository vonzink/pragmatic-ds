import type { PointerEventView, ReleaseSummary } from "../types";

/**
 * Every release, and every time production changed which one it answered with.
 *
 * Two different histories, deliberately shown together. The releases are what was *authored*; the
 * pointer events are what was *shipped*. They diverge constantly — a candidate that was never
 * promoted, a release promoted twice with a rollback between — and reading either alone gives a
 * wrong account of what customers actually got.
 *
 * Nothing here is editable and nothing pretends to be. A rollback does not rewrite the release it
 * points back at: the release stays exactly as authored, and the pointer moves. That is why the
 * events carry their own version numbers rather than the releases carrying a "current" flag.
 *
 * An empty log and an unread log look identical and mean opposite things, so they are never shown
 * the same way. "Nothing has ever been promoted" is a claim about production, and a read that
 * failed is not evidence for it.
 */
export default function ReleaseHistoryPanel(
  { releases, events, eventsError, selectedReleaseId, onSelect }: {
    releases: ReleaseSummary[];
    events: PointerEventView[];
    /** Why the log is unknown, as opposed to known to be empty. */
    eventsError: string | null;
    selectedReleaseId: string | null;
    onSelect: (releaseId: string) => void;
  },
) {
  return (
    <>
      <section className="card workbench-card">
        <h3>Releases</h3>
        {releases.length === 0 && <p className="muted">No releases have been authored yet.</p>}

        {releases.length > 0 && (
          <div className="table-scroll">
            <table className="data-table responsive-table">
              <caption className="sr-only">Every release of this instance, newest first</caption>
              <thead>
                <tr>
                  <th scope="col">Release</th>
                  <th scope="col">State</th>
                  <th scope="col">Provider / model</th>
                  <th scope="col">Collections</th>
                  <th scope="col">Provenance</th>
                  <th scope="col">Authored</th>
                  <th scope="col"><span className="sr-only">Select</span></th>
                </tr>
              </thead>
              <tbody>
                {releases.map((release) => (
                  <tr key={release.releaseId}
                      aria-selected={release.releaseId === selectedReleaseId}>
                    <th scope="row" data-label="Release">r{release.releaseNumber}</th>
                    <td data-label="State">
                      <span className={`badge ${release.live ? "badge-live" : "badge-candidate"}`}>
                        {release.live ? "Live" : "Candidate"}
                      </span>
                    </td>
                    <td data-label="Provider / model">
                      {release.provider && release.model
                        ? `${release.provider} / ${release.model}`
                        : <span className="qualifier">Unavailable</span>}
                    </td>
                    <td data-label="Collections" className="num">
                      {release.collectionCount ?? <span className="qualifier">Unavailable</span>}
                    </td>
                    {/* How the release came to exist. A wizard-authored release and one appended
                        by a configuration edit are different provenance, and the distinction
                        survives in the record. */}
                    <td data-label="Provenance">{release.provenance}</td>
                    <td data-label="Authored">{release.createdAt.slice(0, 10)}</td>
                    <td data-label="Select">
                      <button type="button" className="link-button"
                              onClick={() => onSelect(release.releaseId)}>
                        Inspect r{release.releaseNumber}
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      <section className="card workbench-card">
        <h3>What production answered with</h3>
        <p className="muted">
          Every movement of the live pointer, newest first. This is the record of what customers
          were actually given, which is not the same as the list of releases above.
        </p>

        {eventsError !== null && (
          <div className="warn-note" role="status">
            <p>
              The pointer history could not be read, so what production has answered with is
              unknown here. It has not been established that nothing was ever promoted.
            </p>
            <p><span className="mono">{eventsError}</span></p>
          </div>
        )}

        {eventsError === null && events.length === 0 && (
          <p className="muted">Nothing has ever been promoted, so production answers with nothing.</p>
        )}

        {eventsError === null && events.length > 0 && (
          <ol className="pointer-events">
            {events.map((event) => (
              <li key={event.pointerVersion}>
                <p className="event-head">
                  <span className={`badge ${event.action === "ROLLBACK"
                    ? "badge-candidate" : "badge-live"}`}>
                    {event.action === "ROLLBACK" ? "Rolled back" : "Promoted"}
                  </span>
                  <span className="mono">
                    {event.fromReleaseId ? `${event.fromReleaseId.slice(0, 8)} ` : "nothing "}
                    {"-> "}
                    {event.toReleaseId.slice(0, 8)}
                  </span>
                  <span className="muted"> v{event.pointerVersion}</span>
                </p>
                {/* Written by the person who moved it, and kept verbatim. Paraphrasing an audit
                    record is how it stops being one. */}
                <p className="event-reason">
                  {event.changeReason?.trim()
                    ? event.changeReason
                    : <span className="qualifier">Not recorded</span>}
                </p>
                <p className="muted">
                  {event.actorId?.trim()
                    ? event.actorId
                    : <span className="qualifier">Not recorded</span>}
                  {event.occurredAt ? ` on ${event.occurredAt.slice(0, 10)}` : ""}
                </p>
              </li>
            ))}
          </ol>
        )}
      </section>
    </>
  );
}
