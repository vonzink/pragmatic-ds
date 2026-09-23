import { renderHook, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

/**
 * The three properties worth testing are the ones that cost money or mislead people if wrong.
 *
 * Polling that never stops bills the admin API forever from a tab somebody forgot. Polling that
 * blanks its panel on one dropped request tells an operator their run vanished. And polling at a
 * fixed interval treats a group queued for an hour the same as one submitted two seconds ago.
 */

vi.mock("../api", () => ({ instanceApi: { get: vi.fn() } }));

import { instanceApi } from "../api";
import { useRunGroupPolling } from "./useRunGroupPolling";

const BRAIN = "11111111-1111-4111-8111-111111111111";
const GROUP = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";

function detail(status: string) {
  return {
    group: {
      groupId: GROUP,
      brainId: BRAIN,
      mode: "INDEPENDENT",
      comparisonDimension: null,
      status,
      memberCount: 1,
      createdAt: "2026-08-20T10:00:00Z",
      terminalAt: null,
      cancellationRequestedAt: null,
    },
    members: [],
  };
}

beforeEach(() => {
  vi.clearAllMocks();
  vi.useFakeTimers({ shouldAdvanceTime: true });
});

afterEach(() => {
  vi.useRealTimers();
});

describe("useRunGroupPolling", () => {
  it("stops once the group reaches a status it cannot leave", async () => {
    vi.mocked(instanceApi.get).mockResolvedValue(detail("SUCCEEDED") as never);

    const { result } = renderHook(() => useRunGroupPolling(BRAIN, GROUP));

    await waitFor(() => expect(result.current.settled).toBe(true));
    const afterSettle = vi.mocked(instanceApi.get).mock.calls.length;

    // Two minutes of wall clock: a group that is done stays done, and asking again cannot
    // produce a different answer.
    await vi.advanceTimersByTimeAsync(120_000);
    expect(vi.mocked(instanceApi.get).mock.calls.length).toBe(afterSettle);
  });

  it("keeps polling while the group is still moving", async () => {
    vi.mocked(instanceApi.get).mockResolvedValue(detail("PROCESSING") as never);

    renderHook(() => useRunGroupPolling(BRAIN, GROUP));

    await waitFor(() => expect(instanceApi.get).toHaveBeenCalledTimes(1));
    await vi.advanceTimersByTimeAsync(5_000);
    await waitFor(() => expect(vi.mocked(instanceApi.get).mock.calls.length).toBeGreaterThan(1));
  });

  it("backs off rather than hammering a long-queued group", async () => {
    vi.mocked(instanceApi.get).mockResolvedValue(detail("QUEUED") as never);

    renderHook(() => useRunGroupPolling(BRAIN, GROUP));
    await waitFor(() => expect(instanceApi.get).toHaveBeenCalledTimes(1));

    await vi.advanceTimersByTimeAsync(60_000);
    const inFirstMinute = vi.mocked(instanceApi.get).mock.calls.length;

    await vi.advanceTimersByTimeAsync(60_000);
    const inSecondMinute = vi.mocked(instanceApi.get).mock.calls.length - inFirstMinute;

    // A fixed one-second interval would make these equal at sixty apiece.
    expect(inSecondMinute).toBeLessThan(inFirstMinute);
  });

  it("retains the last good response through a dropped request", async () => {
    vi.mocked(instanceApi.get)
      .mockResolvedValueOnce(detail("PROCESSING") as never)
      .mockRejectedValue(new Error("503"));

    const { result } = renderHook(() => useRunGroupPolling(BRAIN, GROUP));

    await waitFor(() => expect(result.current.detail).not.toBeNull());
    await vi.advanceTimersByTimeAsync(5_000);

    // The error is reported, and the run is still on screen. Blanking here would read as
    // "your run disappeared" when nothing about the run changed.
    await waitFor(() => expect(result.current.transientError).toBe("503"));
    expect(result.current.detail?.group.status).toBe("PROCESSING");
  });

  it("recovers when the next poll succeeds", async () => {
    vi.mocked(instanceApi.get)
      .mockRejectedValueOnce(new Error("503"))
      .mockResolvedValue(detail("SUCCEEDED") as never);

    const { result } = renderHook(() => useRunGroupPolling(BRAIN, GROUP));

    await waitFor(() => expect(result.current.transientError).toBe("503"));
    await vi.advanceTimersByTimeAsync(5_000);

    await waitFor(() => expect(result.current.transientError).toBeNull());
    expect(result.current.settled).toBe(true);
  });

  it("stops when the component goes away", async () => {
    vi.mocked(instanceApi.get).mockResolvedValue(detail("PROCESSING") as never);

    const { unmount } = renderHook(() => useRunGroupPolling(BRAIN, GROUP));
    await waitFor(() => expect(instanceApi.get).toHaveBeenCalledTimes(1));

    unmount();
    const atUnmount = vi.mocked(instanceApi.get).mock.calls.length;
    await vi.advanceTimersByTimeAsync(60_000);

    // A closed tab that keeps polling is the version of this bug nobody notices for a month.
    expect(vi.mocked(instanceApi.get).mock.calls.length).toBe(atUnmount);
  });

  it("asks for nothing until there is a group to ask about", async () => {
    renderHook(() => useRunGroupPolling(BRAIN, null));

    await vi.advanceTimersByTimeAsync(10_000);
    expect(instanceApi.get).not.toHaveBeenCalled();
  });

  it("scopes the request to the brain and group from its arguments", async () => {
    vi.mocked(instanceApi.get).mockResolvedValue(detail("SUCCEEDED") as never);

    renderHook(() => useRunGroupPolling(BRAIN, GROUP));

    await waitFor(() => expect(instanceApi.get).toHaveBeenCalled());
    const path = String(vi.mocked(instanceApi.get).mock.calls[0][0]);
    expect(path).toContain(`run-groups/${GROUP}`);
    expect(path).toContain(`brain=${BRAIN}`);
  });
});
