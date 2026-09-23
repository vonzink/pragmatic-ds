import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { describe, expect, it, vi } from "vitest";
import ConfirmLiveChangeDialog from "./ConfirmLiveChangeDialog";
import type { ReleaseSummary } from "../types";

/**
 * The modal contract, asserted as behaviour rather than as markup.
 *
 * A test that greps the DOM for `aria-modal` proves nothing: it passes just as happily when focus
 * is left behind on the page underneath, when Tab walks a keyboard user straight out of a
 * confirmation they have not answered, and when dismissing the dialog strands focus on the body.
 * So every assertion here is about where focus actually is — after opening, after Tab, after
 * Escape, and after the dialog closes.
 *
 * **This dialog is deliberately not like a data-entry one.** Its submit button moves production
 * traffic, so opening it must not put a single keystroke between an operator and a promotion.
 * Focus lands on the dialog itself, which a screen reader announces by its title and its text, and
 * never on the confirm control.
 */

const LIVE: ReleaseSummary = {
  releaseId: "33333333-3333-4333-8333-333333333333",
  releaseNumber: 3,
  provenance: "WIZARD",
  live: true,
  manifestVersion: 2,
  provider: "synthetic-provider-b",
  model: "synthetic-model-b2",
  collectionCount: 1,
  limitationCode: null,
  limitationFlags: [],
  createdAt: "2026-08-01T00:00:00Z",
};

const CANDIDATE: ReleaseSummary = {
  ...LIVE,
  releaseId: "22222222-2222-4222-8222-222222222222",
  releaseNumber: 4,
  live: false,
  provider: "synthetic-provider-a",
  model: "synthetic-model-a1",
};

/** A trigger and the confirmation it opens — the pair the focus contract is about. */
function Harness(
  { busy = false, onConfirm = () => undefined }:
  { busy?: boolean; onConfirm?: (reason: string) => void },
) {
  const [open, setOpen] = useState(false);
  return (
    <div>
      <button type="button" onClick={() => setOpen(true)}>Review and apply to live</button>
      {/* Something focusable behind the dialog, so "Tab escaped" is a reachable outcome and the
          trap is being asserted rather than the absence of anywhere to go. */}
      <button type="button">Behind the dialog</button>
      <ConfirmLiveChangeDialog
        open={open}
        action="promote"
        candidate={CANDIDATE}
        current={LIVE}
        busy={busy}
        error={null}
        onConfirm={(reason) => { onConfirm(reason); setOpen(false); }}
        onCancel={() => setOpen(false)}
      />
    </div>
  );
}

const TRIGGER = "Review and apply to live";

describe("the live-change confirmation", () => {
  it("moves focus into the dialog and never onto the control that moves live traffic",
    async () => {
      const user = userEvent.setup();
      render(<Harness />);
      await user.click(screen.getByRole("button", { name: TRIGGER }));

      const dialog = screen.getByRole("dialog");
      await waitFor(() => expect(document.activeElement).toBe(dialog));
      expect(document.activeElement)
        .not.toBe(within(dialog).getByRole("button", { name: "Apply to live" }));
      // Nor is it left outside, on the page the operator is being asked to stop reading.
      expect(document.activeElement).not.toBe(document.body);
    });

  it("promotes nothing when Enter is pressed on the dialog it has just opened", async () => {
    const onConfirm = vi.fn();
    const user = userEvent.setup();
    render(<Harness onConfirm={onConfirm} />);
    await user.click(screen.getByRole("button", { name: TRIGGER }));
    await waitFor(() => expect(document.activeElement).toBe(screen.getByRole("dialog")));

    await user.keyboard("{Enter}");

    // An operator who presses Enter out of habit has promoted nothing.
    expect(onConfirm).not.toHaveBeenCalled();
    expect(screen.getByRole("dialog")).toBeTruthy();
  });

  it("keeps Tab inside the dialog, forwards and backwards", async () => {
    const user = userEvent.setup();
    render(<Harness />);
    await user.click(screen.getByRole("button", { name: TRIGGER }));
    const dialog = screen.getByRole("dialog");
    await waitFor(() => expect(document.activeElement).toBe(dialog));

    // Enough tabs to walk past the end of the dialog's own controls several times over. If the
    // trap is missing, focus lands on the page behind instead of cycling.
    for (let i = 0; i < 8; i += 1) {
      await user.tab();
      expect(dialog.contains(document.activeElement)).toBe(true);
    }
    for (let i = 0; i < 8; i += 1) {
      await user.tab({ shift: true });
      expect(dialog.contains(document.activeElement)).toBe(true);
    }
    expect(screen.getByRole("button", { name: "Behind the dialog" }))
      .not.toBe(document.activeElement);
  });

  it("gives focus back to whatever opened it", async () => {
    const user = userEvent.setup();
    render(<Harness />);
    const trigger = screen.getByRole("button", { name: TRIGGER });
    await user.click(trigger);
    await waitFor(() => expect(document.activeElement).toBe(screen.getByRole("dialog")));

    await user.click(screen.getByRole("button", { name: "Cancel" }));

    // Not the body: for anyone driving this from the keyboard, focus on the body means starting
    // the whole page again.
    await waitFor(() => expect(document.activeElement).toBe(trigger));
  });

  it("dismisses on Escape, which is what its own Cancel does", async () => {
    const user = userEvent.setup();
    render(<Harness />);
    await user.click(screen.getByRole("button", { name: TRIGGER }));
    await waitFor(() => expect(document.activeElement).toBe(screen.getByRole("dialog")));

    await user.keyboard("{Escape}");

    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
  });

  it("goes inert on Escape while the move is in flight", async () => {
    const user = userEvent.setup();
    render(<Harness busy />);
    await user.click(screen.getByRole("button", { name: TRIGGER }));
    await waitFor(() => expect(document.activeElement).toBe(screen.getByRole("dialog")));

    await user.keyboard("{Escape}");

    // Cancel is disabled mid-flight, so Escape must not do more than the buttons on screen. It
    // also drops the idempotency key the in-flight attempt is retrying under.
    expect(screen.getByRole("dialog")).toBeTruthy();
  });
});
