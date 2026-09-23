import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import ReleaseHistoryPanel from "./ReleaseHistoryPanel";
import type { PointerEventView, ReleaseSummary } from "../types";

/**
 * The pointer log is an audit record, so the tests are about the two ways it could lie.
 *
 * It could state something it does not know. "Nothing has ever been promoted" is a claim about
 * production, and a read that failed is not evidence for it — an empty list and an unread list
 * look identical and mean opposite things.
 *
 * And it could quietly drop what it was not given. An event with no actor or no reason is a real
 * row, and rendering it as blank space reads as an event nobody needed to explain rather than one
 * whose explanation is missing.
 */

const RELEASES: ReleaseSummary[] = [];

function event(over: Partial<PointerEventView> = {}): PointerEventView {
  return {
    action: "PROMOTE",
    fromReleaseId: null,
    toReleaseId: "22222222-2222-4222-8222-222222222222",
    pointerVersion: 1,
    actorId: "operator@example.test",
    changeReason: "first promotion of the income analyzer",
    occurredAt: "2026-08-01T00:00:00Z",
    ...over,
  };
}

describe("the pointer log", () => {
  it("distinguishes an empty log from one it could not read", () => {
    const { rerender } = render(
      <ReleaseHistoryPanel releases={RELEASES} events={[]} eventsError={null}
                           selectedReleaseId={null} onSelect={() => undefined} />);
    expect(screen.getByText(/Nothing has ever been promoted/)).toBeTruthy();

    rerender(
      <ReleaseHistoryPanel releases={RELEASES} events={[]} eventsError="503"
                           selectedReleaseId={null} onSelect={() => undefined} />);

    // The claim about production is withdrawn, because it was never established.
    expect(screen.queryByText(/Nothing has ever been promoted/)).toBeNull();
    expect(screen.getByText(/could not be read/i)).toBeTruthy();
  });

  it("says an absent actor or reason was not recorded", () => {
    render(
      <ReleaseHistoryPanel
        releases={RELEASES}
        events={[event({ actorId: null as never, changeReason: "" })]}
        eventsError={null}
        selectedReleaseId={null}
        onSelect={() => undefined}
      />);

    // Twice: the actor and the reason are separately missing and separately worth knowing about.
    expect(screen.getAllByText("Not recorded").length).toBe(2);
  });
});
