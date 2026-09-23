import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

/**
 * This is the only control in the product that changes what customers get, so the tests are about
 * the three ways it could do that wrongly.
 *
 * It could ship a release the server would refuse — so the gate is the server's and the button
 * stays shut until the server says otherwise, including when the gate cannot be reached at all.
 *
 * It could ship against a picture of production that is no longer true — so the move carries a
 * compare-and-set on both the live release and the pointer version, and a lost race is never
 * retried automatically. Retrying with a refreshed version would apply a decision made against a
 * world that stopped existing, which is precisely what the check was added to prevent.
 *
 * And it could ship without anyone knowing why — so the reason is required, bounded, and long
 * enough to be an explanation.
 */

vi.mock("../api", () => ({
  instanceApi: { get: vi.fn(), post: vi.fn(), postIdempotent: vi.fn() },
}));

import { instanceApi } from "../api";
import PromotionPanel from "./PromotionPanel";

const BRAIN = "11111111-1111-4111-8111-111111111111";
const CANDIDATE = "22222222-2222-4222-8222-222222222222";
const LIVE = "33333333-3333-4333-8333-333333333333";

function release(over: Partial<Record<string, unknown>> = {}) {
  return {
    releaseId: CANDIDATE, releaseNumber: 4, provenance: "WIZARD", live: false,
    manifestVersion: 2, provider: "anthropic", model: "claude-opus-5", collectionCount: 1,
    limitationCode: null, limitationFlags: [], createdAt: "2026-08-01T00:00:00Z",
    ...over,
  } as never;
}

function pointer(over: Partial<Record<string, unknown>> = {}) {
  return {
    liveReleaseId: LIVE, pointerVersion: 4, promotionEnabled: true, events: [], ...over,
  } as never;
}

const onMoved = vi.fn();

beforeEach(() => {
  vi.clearAllMocks();
  vi.stubGlobal("crypto", { randomUUID: () => "move-key" });
  vi.mocked(instanceApi.get).mockResolvedValue(pointer());
  vi.mocked(instanceApi.post).mockResolvedValue({ allowed: true, blockingCodes: [] } as never);
});

function renderPanel(over: Partial<Record<string, unknown>> = {}) {
  return render(
    <PromotionPanel
      brainId={BRAIN}
      instanceSlug="income"
      candidate={release(over)}
      live={release({ releaseId: LIVE, releaseNumber: 3, live: true, model: "claude-sonnet-5" })}
      action="promote"
      onMoved={onMoved}
    />);
}

/** Open the confirmation and give it a usable reason. */
async function confirmWith(reason: string) {
  await userEvent.click(
    await screen.findByRole("button", { name: "Review and apply to live" }));
  await userEvent.type(screen.getByLabelText(/^Change reason/), reason);
}

describe("PromotionPanel", () => {
  it("shows every gate the server returned and refuses to act", async () => {
    vi.mocked(instanceApi.post).mockResolvedValue({
      allowed: false, blockingCodes: ["EVALUATION_NOT_RUN", "CORPUS_COLLECTION_VERSION_STALE"],
    } as never);

    renderPanel();

    await screen.findByText("EVALUATION_NOT_RUN");
    expect(screen.getByText("CORPUS_COLLECTION_VERSION_STALE")).toBeTruthy();
    // Every reason, not just the first: fixing one at a time across four round trips is how a
    // promotion takes an afternoon.
    expect(screen.getByRole("button", { name: "Review and apply to live" }))
      .toHaveProperty("disabled", true);
  });

  it("stays shut when the gate cannot be reached", async () => {
    vi.mocked(instanceApi.post).mockRejectedValue(new Error("503"));

    renderPanel();

    // An unreachable gate has allowed nothing. Treating silence as consent is the one failure
    // mode that ships something nobody approved.
    await screen.findByText("503");
    expect(screen.getByRole("button", { name: "Review and apply to live" }))
      .toHaveProperty("disabled", true);
  });

  it("asserts both halves of what it believes about production", async () => {
    renderPanel();
    await confirmWith("rolling forward the income fix");
    await userEvent.click(screen.getByRole("button", { name: "Apply to live" }));

    await waitFor(() => expect(instanceApi.postIdempotent).toHaveBeenCalled());
    const body = vi.mocked(instanceApi.postIdempotent).mock.calls[0][1] as Record<string, unknown>;
    // The release alone is not enough: promote A, roll back to B, promote A again, and a
    // release-only check would let a stale caller succeed against a pointer that moved twice.
    expect(body.expectedLiveReleaseId).toBe(LIVE);
    expect(body.expectedPointerVersion).toBe(4);
    expect(body.changeReason).toBe("rolling forward the income fix");
    expect(String(vi.mocked(instanceApi.postIdempotent).mock.calls[0][0]))
      .toContain("apply-to-live");
  });

  it("will not accept a reason too short to be one", async () => {
    renderPanel();
    await confirmWith("fix");

    // The pointer history is the record of every change to what customers were answered with,
    // and "fix" tells whoever reads it in six months nothing at all.
    // The trigger and the confirm are named differently on purpose; this is the confirm.
    expect(screen.getByRole("button", { name: "Apply to live" }))
      .toHaveProperty("disabled", true);
    expect(instanceApi.postIdempotent).not.toHaveBeenCalled();
  });

  it("names both sides of the change before it happens", async () => {
    renderPanel();
    await userEvent.click(
      await screen.findByRole("button", { name: "Review and apply to live" }));

    const dialog = screen.getByRole("dialog");
    expect(dialog.textContent).toContain("r3");
    expect(dialog.textContent).toContain("claude-sonnet-5");
    expect(dialog.textContent).toContain("r4");
    expect(dialog.textContent).toContain("claude-opus-5");
    // Said plainly, because everything else on the page is reversible and this is not.
    expect(dialog.textContent).toContain("Production callers will switch");
  });

  it("does not retry a lost race, and re-reads what is live now", async () => {
    renderPanel();
    await confirmWith("promoting the reviewed candidate");

    vi.mocked(instanceApi.postIdempotent).mockRejectedValueOnce(
      Object.assign(new Error("LIVE_POINTER_CHANGED"), { code: "LIVE_POINTER_CHANGED" }));
    vi.mocked(instanceApi.get).mockResolvedValue(pointer({ pointerVersion: 5 }));
    await userEvent.click(screen.getByRole("button", { name: "Apply to live" }));

    await screen.findByText(/Production changed while this page was open/);
    // Exactly one attempt. An automatic retry would apply a decision made against a world that
    // no longer exists.
    expect(instanceApi.postIdempotent).toHaveBeenCalledTimes(1);
    // And the dialog is gone, so confirming again is a new deliberate act against the new state.
    expect(screen.queryByRole("dialog")).toBeNull();
    await waitFor(() => expect(screen.getByText("5")).toBeTruthy());
  });

  it("rolls back through the same gate and the same confirmation", async () => {
    render(
      <PromotionPanel
        brainId={BRAIN}
        instanceSlug="income"
        candidate={release({ releaseNumber: 2 })}
        live={release({ releaseId: LIVE, releaseNumber: 3, live: true })}
        action="rollback"
        onMoved={onMoved}
      />);

    // Never labelled safe until the server's gate says it is executable: a historical release can
    // be as unpromotable as a new one if its corpus or credential moved.
    await userEvent.click(
      await screen.findByRole("button", { name: "Review and roll back" }));
    await userEvent.type(screen.getByLabelText(/^Change reason/), "reverting the bad promotion");
    await userEvent.click(screen.getByRole("button", { name: "Roll back" }));

    await waitFor(() => expect(instanceApi.postIdempotent).toHaveBeenCalled());
    expect(String(vi.mocked(instanceApi.postIdempotent).mock.calls[0][0])).toContain("rollback");
  });

  it("reads the pointer before asking whether the gate passes", async () => {
    renderPanel();

    await waitFor(() => expect(instanceApi.post).toHaveBeenCalled());
    // The gate check asserts a view of live, so there is nothing to ask until the page has one.
    expect(String(vi.mocked(instanceApi.get).mock.calls[0][0])).toContain("/pointer");
    const check = vi.mocked(instanceApi.post).mock.calls[0][1] as Record<string, unknown>;
    expect(check.expectedPointerVersion).toBe(4);
  });
  it("keeps a gate refusal and an unreadable gate as different answers", async () => {
    vi.mocked(instanceApi.post).mockRejectedValue(new Error("503"));

    renderPanel();

    // The gate did not refuse this release; nobody asked it successfully. Listing an HTTP failure
    // among the blocking codes says the release was examined and found wanting, which sends
    // someone to re-evaluate a release when the thing to fix is the deployment.
    await screen.findByText(/could not be checked/i);
    expect(screen.queryByText(/cannot go live yet/i)).toBeNull();
  });

  it("says so plainly when promotion is switched off rather than burying it in a list",
    async () => {
      vi.mocked(instanceApi.post).mockResolvedValue({
        allowed: false, blockingCodes: ["INSTANCE_PROMOTION_DISABLED"],
      } as never);

      renderPanel();

      // Nothing about this release is wrong, and no amount of evaluating it will help. That is a
      // different errand from every other blocking code.
      await screen.findByText(/promotion is switched off/i);
      // Not filed among "this release cannot go live yet". That list sends someone to fix the
      // release; this one sends them to fix the deployment, and they are not the same errand.
      expect(screen.queryByText(/cannot go live yet/i)).toBeNull();
      expect(screen.getByRole("button", { name: "Review and apply to live" }))
        .toHaveProperty("disabled", true);
    });

  it("never stands a number in for a pointer version it could not read", async () => {
    vi.mocked(instanceApi.get).mockRejectedValue(new Error("500"));

    renderPanel();

    // Zero is a read value, not a way of saying "unknown". A fabricated version either fails the
    // compare-and-set or, far worse, succeeds against a state the operator never saw.
    await screen.findByText("Unknown");
    expect(screen.queryByText("0")).toBeNull();
    expect(screen.getByRole("button", { name: "Review and apply to live" }))
      .toHaveProperty("disabled", true);
  });
  it("says promotion is off without composing a move to find out", async () => {
    vi.mocked(instanceApi.get).mockResolvedValue(pointer({ promotionEnabled: false }));

    renderPanel();

    // The pointer read carries the switch, so an operator learns it from the state of the
    // deployment rather than from the failure of an attempt they should not have been offered.
    await screen.findByText(/promotion is switched off/i);
    expect(instanceApi.post).not.toHaveBeenCalled();
    expect(screen.getByRole("button", { name: "Review and apply to live" }))
      .toHaveProperty("disabled", true);
  });
});
