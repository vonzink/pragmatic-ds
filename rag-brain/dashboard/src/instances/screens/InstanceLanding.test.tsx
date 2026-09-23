import { render, screen, waitFor, within } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";

/**
 * The landing page's job is to let someone find an instance without knowing which brain owns it.
 *
 * The state that matters most here is the partial one. A deployment has several brains; one of
 * them failing to list must not blank the page, because the moment somebody most needs this screen
 * is the moment something is wrong. So the partial case asserts both halves: the instances that
 * loaded are on the page, and the brain that did not is named rather than silently missing.
 */

vi.mock("../../api", () => ({
  brainsApi: { list: vi.fn() },
}));
vi.mock("../api", () => ({
  instanceApi: { get: vi.fn() },
}));

import { brainsApi } from "../../api";
import { instanceApi } from "../api";
import InstanceLanding from "./InstanceLanding";

const MORTGAGE = "11111111-1111-4111-8111-111111111111";
const ASSETS = "22222222-2222-4222-8222-222222222222";

function brain(id: string, displayName: string) {
  return { id, slug: displayName.toLowerCase(), displayName } as never;
}

function instance(over: Partial<Record<string, unknown>> = {}) {
  return {
    brainId: MORTGAGE,
    slug: "income",
    displayName: "Income",
    purpose: "Analyze income.",
    state: "ACTIVE",
    liveReleaseNumber: 3,
    candidateCount: 0,
    manifestVersion: 2,
    provider: "anthropic",
    model: "claude-opus-5",
    collectionCount: 2,
    hasCandidateRelease: false,
    limitationCode: null,
    limitationFlags: [],
    createdAt: "2026-08-01T00:00:00Z",
    updatedAt: "2026-08-01T00:00:00Z",
    ...over,
  };
}

function renderLanding() {
  return render(<MemoryRouter><InstanceLanding /></MemoryRouter>);
}

beforeEach(() => {
  vi.clearAllMocks();
});

describe("InstanceLanding", () => {
  it("lists instances from every brain without asking anyone to pick one first", async () => {
    vi.mocked(brainsApi.list).mockResolvedValue(
      [brain(MORTGAGE, "Mortgage"), brain(ASSETS, "Assets")] as never);
    vi.mocked(instanceApi.get).mockImplementation(async (path: string) =>
      (path.includes(MORTGAGE)
        ? [instance()]
        : [instance({ brainId: ASSETS, slug: "deposits", displayName: "Deposits" })]) as never);

    renderLanding();

    await waitFor(() => expect(screen.getByText("Income")).toBeTruthy());
    expect(screen.getByText("Deposits")).toBeTruthy();
    // Each brain is asked by its own explicit UUID; there is no cross-brain listing endpoint.
    expect(vi.mocked(instanceApi.get).mock.calls.map((c) => c[0]))
      .toEqual([expect.stringContaining(MORTGAGE), expect.stringContaining(ASSETS)]);
  });

  it("keeps the brains that answered when one fails, and names the one that did not", async () => {
    vi.mocked(brainsApi.list).mockResolvedValue(
      [brain(MORTGAGE, "Mortgage"), brain(ASSETS, "Assets")] as never);
    vi.mocked(instanceApi.get).mockImplementation(async (path: string) => {
      if (path.includes(ASSETS)) throw new Error("boom");
      return [instance()] as never;
    });

    renderLanding();

    // Both halves. Losing nine instances because the tenth brain errored makes the screen
    // useless exactly when somebody is trying to find out what is wrong.
    await waitFor(() => expect(screen.getByText("Income")).toBeTruthy());
    expect(screen.getByRole("status").textContent).toContain("Assets");
  });

  it("says it is loading rather than showing an empty page", async () => {
    vi.mocked(brainsApi.list).mockReturnValue(new Promise(() => {}) as never);

    renderLanding();

    expect(screen.getByText(/Loading instances/)).toBeTruthy();
    expect(screen.queryByText(/No instances yet/)).toBeNull();
  });

  it("distinguishes an empty deployment from a failed one", async () => {
    vi.mocked(brainsApi.list).mockResolvedValue([brain(MORTGAGE, "Mortgage")] as never);
    vi.mocked(instanceApi.get).mockResolvedValue([] as never);

    renderLanding();

    await waitFor(() => expect(screen.getByText(/No instances yet/)).toBeTruthy());
    expect(screen.queryByRole("alert")).toBeNull();
  });

  it("reports a failure to load brains as an error, not as emptiness", async () => {
    vi.mocked(brainsApi.list).mockRejectedValue(new Error("key rejected"));

    renderLanding();

    await waitFor(() => expect(screen.getByRole("alert")).toBeTruthy());
    expect(screen.getByRole("alert").textContent).toContain("key rejected");
    expect(screen.queryByText(/No instances yet/)).toBeNull();
  });

  // ============================================================ what a card says

  it("links with the brain UUID and slug in the path, not a sidebar selection", async () => {
    vi.mocked(brainsApi.list).mockResolvedValue([brain(MORTGAGE, "Mortgage")] as never);
    vi.mocked(instanceApi.get).mockResolvedValue([instance()] as never);

    renderLanding();

    const link = await waitFor(() => screen.getByRole("link", { name: /Income/ }));
    expect(link.getAttribute("href")).toContain(`/instances/${MORTGAGE}/income`);
  });

  it("shows a disabled instance as disabled rather than as having no release", async () => {
    vi.mocked(brainsApi.list).mockResolvedValue([brain(MORTGAGE, "Mortgage")] as never);
    vi.mocked(instanceApi.get).mockResolvedValue(
      [instance({ state: "DISABLED", liveReleaseNumber: null })] as never);

    renderLanding();

    await waitFor(() => expect(screen.getByText("Disabled")).toBeTruthy());
  });

  it("surfaces a candidate that production is not answering with", async () => {
    vi.mocked(brainsApi.list).mockResolvedValue([brain(MORTGAGE, "Mortgage")] as never);
    vi.mocked(instanceApi.get).mockResolvedValue(
      [instance({ hasCandidateRelease: true, candidateCount: 2 })] as never);

    renderLanding();

    await waitFor(() => expect(screen.getByText(/2 candidates pending/)).toBeTruthy());
    expect(screen.getByText("Live r3")).toBeTruthy();
  });

  it("says a model is not pinned instead of rendering a blank", async () => {
    vi.mocked(brainsApi.list).mockResolvedValue([brain(MORTGAGE, "Mortgage")] as never);
    vi.mocked(instanceApi.get).mockResolvedValue(
      [instance({ liveReleaseNumber: null, provider: null, model: null, collectionCount: null })] as never);

    renderLanding();

    // "Nothing has been promoted" and "the field failed to load" must not look alike.
    const card = await waitFor(() => screen.getByRole("listitem"));
    expect(within(card).getAllByText("Not pinned").length).toBe(2);
    expect(within(card).getByText("No live release")).toBeTruthy();
  });

  it("renders limitation codes verbatim rather than paraphrasing them", async () => {
    vi.mocked(brainsApi.list).mockResolvedValue([brain(MORTGAGE, "Mortgage")] as never);
    vi.mocked(instanceApi.get).mockResolvedValue(
      [instance({ limitationFlags: ["PROTOTYPE_SINGLE_BORROWER"] })] as never);

    renderLanding();

    await waitFor(() =>
      expect(screen.getByText(/PROTOTYPE_SINGLE_BORROWER/)).toBeTruthy());
  });
});
