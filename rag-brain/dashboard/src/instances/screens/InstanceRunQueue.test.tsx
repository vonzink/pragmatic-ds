import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";

/**
 * The queue is a read. Its tests say so twice: once for what it shows, and once for what it
 * refuses to become — there is no path from this screen to starting work, because a screen that
 * both shows a backlog and can add to it invites adding to it while reading it.
 *
 * The status filter is asserted as a *server* parameter rather than as client-side filtering,
 * because `GET /run-groups` accepts one and filtering after the fact would page through terminal
 * groups to find the two that are queued.
 */

vi.mock("../../api", () => ({ brainsApi: { list: vi.fn() } }));
vi.mock("../api", () => ({ instanceApi: { get: vi.fn() } }));

import { brainsApi } from "../../api";
import { instanceApi } from "../api";
import InstanceRunQueue from "./InstanceRunQueue";

const MORTGAGE = "11111111-1111-4111-8111-111111111111";
const ASSETS = "22222222-2222-4222-8222-222222222222";

function group(over: Partial<Record<string, unknown>> = {}) {
  return {
    groupId: "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
    brainId: MORTGAGE,
    mode: "INDEPENDENT",
    comparisonDimension: null,
    status: "QUEUED",
    memberCount: 2,
    createdAt: "2026-08-20T10:00:00Z",
    terminalAt: null,
    cancellationRequestedAt: null,
    ...over,
  };
}

function renderQueue() {
  return render(<MemoryRouter><InstanceRunQueue /></MemoryRouter>);
}

beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(brainsApi.list).mockResolvedValue([
    { id: MORTGAGE, slug: "mortgage", displayName: "Mortgage" },
    { id: ASSETS, slug: "assets", displayName: "Assets" },
  ] as never);
});

describe("InstanceRunQueue", () => {
  it("counts what is waiting, running, and done", async () => {
    vi.mocked(instanceApi.get).mockResolvedValue([
      group(),
      group({ groupId: "b", status: "PROCESSING" }),
      group({ groupId: "c", status: "SUCCEEDED", terminalAt: "2026-08-20T11:00:00Z" }),
      group({ groupId: "d", status: "FAILED", terminalAt: "2026-08-20T11:00:00Z" }),
    ] as never);

    renderQueue();

    // Two brains are asked, so each group appears twice; the counts are of what is on screen.
    await waitFor(() => expect(screen.getByText("Queued")).toBeTruthy());
    const counts = screen.getByText("Queued").closest("div")!;
    expect(counts.textContent).toContain("2");
  });

  it("asks the server for a status rather than filtering after the fact", async () => {
    vi.mocked(instanceApi.get).mockResolvedValue([] as never);

    renderQueue();
    await waitFor(() => expect(instanceApi.get).toHaveBeenCalled());
    vi.mocked(instanceApi.get).mockClear();

    await userEvent.selectOptions(screen.getByLabelText("Status"), "PROCESSING");

    await waitFor(() =>
      expect(vi.mocked(instanceApi.get).mock.calls.some(
        (c) => String(c[0]).includes("status=PROCESSING"))).toBe(true));
  });

  it("narrows to one brain when asked, instead of listing them all", async () => {
    vi.mocked(instanceApi.get).mockResolvedValue([] as never);

    renderQueue();
    await waitFor(() => expect(instanceApi.get).toHaveBeenCalledTimes(2));
    vi.mocked(instanceApi.get).mockClear();

    await userEvent.selectOptions(screen.getByLabelText("Brain"), MORTGAGE);

    await waitFor(() => expect(instanceApi.get).toHaveBeenCalledTimes(1));
    expect(String(vi.mocked(instanceApi.get).mock.calls[0][0])).toContain(MORTGAGE);
  });

  it("keeps the brains that answered when one fails", async () => {
    vi.mocked(instanceApi.get).mockImplementation(async (path: string) => {
      if (path.includes(ASSETS)) throw new Error("boom");
      return [group()] as never;
    });

    renderQueue();

    await waitFor(() => expect(screen.getByRole("status").textContent).toContain("Assets"));
    expect(screen.getByRole("table")).toBeTruthy();
  });

  it("shows a requested cancellation that has not finished as its own state", async () => {
    vi.mocked(instanceApi.get).mockResolvedValue([
      group({ status: "PROCESSING", cancellationRequestedAt: "2026-08-20T10:05:00Z" }),
    ] as never);

    renderQueue();

    // "Cancelling" and "cancelled" are different facts and a member may still be mid-provider-call.
    await waitFor(() => expect(screen.getAllByText(/cancelling/).length).toBeGreaterThan(0));
  });

  it("starts nothing — it is visibility, not another execution form", async () => {
    vi.mocked(instanceApi.get).mockResolvedValue([group()] as never);

    renderQueue();

    await waitFor(() => expect(screen.getByRole("table")).toBeTruthy());
    expect(screen.queryAllByRole("button")).toHaveLength(0);
  });

  it("explains an empty result rather than showing an empty table", async () => {
    vi.mocked(instanceApi.get).mockResolvedValue([] as never);

    renderQueue();

    await waitFor(() => expect(screen.getByText(/No run groups match/)).toBeTruthy());
    expect(screen.queryByRole("table")).toBeNull();
  });
});
