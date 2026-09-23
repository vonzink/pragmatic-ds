import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

/**
 * A batch is not one submission. A run group belongs to one brain, so a batch spanning three
 * brains is three groups, and almost everything worth testing here follows from that: each group
 * is priced against its own brain, carries its own key, and fails on its own.
 *
 * The failure containment is the part that would be silently wrong. If two brains succeed and one
 * fails, an implementation that replaced its results with the failure, or retried all three, would
 * look fine on a green day and lose two running groups — or bill them twice — on a bad one.
 *
 * The member editor is stubbed. It is six selects and a parse-and-freeze flow already covered by
 * the workbench and `useRunInputs`; driving all of it here would test those again and bury what
 * this screen actually decides.
 */

const MORTGAGE = "11111111-1111-4111-8111-111111111111";
const ASSETS = "22222222-2222-4222-8222-222222222222";

vi.mock("../../api", () => ({ brainsApi: { list: vi.fn() } }));
vi.mock("../api", () => ({
  instanceApi: { get: vi.fn(), post: vi.fn(), postIdempotent: vi.fn() },
}));

vi.mock("../components/BatchMemberEditor", () => ({
  default: ({ index, member, onChange, onRemove, canRemove }: {
    index: number;
    member: Record<string, unknown>;
    onChange: (next: Record<string, unknown>) => void;
    onRemove: () => void;
    canRemove: boolean;
  }) => (
    <li>
      <span>{`Member ${index + 1} brain ${member.brainId || "unset"}`}</span>
      <button type="button" onClick={() => onChange({
        ...member, brainId: MORTGAGE, instanceSlug: "income",
        releaseId: `rel-${index}`, registrationId: `reg-${index}`,
        corpusSnapshotId: `snap-${index}`,
      })}>{`Complete ${index + 1} in Mortgage`}</button>
      <button type="button" onClick={() => onChange({
        ...member, brainId: ASSETS, instanceSlug: "assets",
        releaseId: `rel-${index}`, registrationId: `reg-${index}`,
        corpusSnapshotId: `snap-${index}`,
      })}>{`Complete ${index + 1} in Assets`}</button>
      {canRemove && (
        <button type="button" onClick={onRemove}>{`Remove ${index + 1}`}</button>
      )}
    </li>
  ),
}));

vi.mock("../components/BatchGroupProgress", () => ({
  default: ({ brainName, groupId }: { brainName: string; groupId: string }) => (
    <div>{`Watching ${brainName} ${groupId}`}</div>
  ),
}));

import { brainsApi } from "../../api";
import { instanceApi } from "../api";
import IndependentRunBuilder, { partitionByBrain, ready } from "./IndependentRunBuilder";

function preflight(over: Partial<Record<string, unknown>> = {}) {
  return {
    requestSha256: "c".repeat(64), comparisonBasisSha256: null,
    members: [], reservedMaximumUsd: 0.03, committedTodayUsd: 0, alreadyReservedUsd: 0,
    dailyBudgetUsd: 10, withinBudget: true, acceptable: true, blockingCodes: [], ...over,
  } as never;
}

let keySeq = 0;

beforeEach(() => {
  vi.clearAllMocks();
  keySeq = 0;
  vi.stubGlobal("crypto", { randomUUID: () => `key-${++keySeq}` });
  vi.mocked(brainsApi.list).mockResolvedValue([
    { id: MORTGAGE, slug: "mortgage", displayName: "Mortgage" },
    { id: ASSETS, slug: "assets", displayName: "Assets" },
  ] as never);
  vi.mocked(instanceApi.post).mockResolvedValue(preflight());
});

/** Two members, one in each brain — the smallest batch that has to be partitioned. */
async function crossBrainBatch() {
  render(<IndependentRunBuilder />);
  await screen.findByText(/Member 1 brain unset/);
  await userEvent.click(screen.getByRole("button", { name: "Add member" }));
  await userEvent.click(screen.getByRole("button", { name: "Complete 1 in Mortgage" }));
  await userEvent.click(screen.getByRole("button", { name: "Complete 2 in Assets" }));
}

describe("partitionByBrain", () => {
  const brains = [
    { id: MORTGAGE, displayName: "Mortgage" },
    { id: ASSETS, displayName: "Assets" },
  ] as never;

  function member(over: Partial<Record<string, unknown>> = {}) {
    return {
      key: "k", brainId: MORTGAGE, instanceSlug: "income", releaseId: "r",
      registrationId: "g", corpusSnapshotId: "s", ...over,
    } as never;
  }

  it("makes one group per brain, not one per member", () => {
    const parts = partitionByBrain(
      [member({ key: "a" }), member({ key: "b" }), member({ key: "c", brainId: ASSETS })], brains);

    expect(parts.map((p) => p.brainName)).toEqual(["Mortgage", "Assets"]);
    expect(parts[0].members).toHaveLength(2);
    expect(parts[1].members).toHaveLength(1);
  });

  it("leaves out members the server could not resolve", () => {
    // Every one of these is required by the run-group command; a member missing one is not a
    // member with a default, it is a submission that would be refused.
    for (const missing of ["releaseId", "registrationId", "corpusSnapshotId", "instanceSlug"]) {
      expect(ready(member({ [missing]: null }))).toBe(false);
    }
    expect(partitionByBrain([member({ registrationId: null })], brains)).toEqual([]);
  });
});

describe("IndependentRunBuilder", () => {
  it("prices each brain against its own budget, with no shared scope", async () => {
    await crossBrainBatch();

    await waitFor(() => expect(vi.mocked(instanceApi.post).mock.calls.length).toBeGreaterThan(0));
    const paths = vi.mocked(instanceApi.post).mock.calls.map((c) => String(c[0]));
    // Two preflights, each naming its brain explicitly. There is no global active brain to fall
    // back on and no call that omits one.
    expect(paths.some((p) => p.includes(`brain=${MORTGAGE}`))).toBe(true);
    expect(paths.some((p) => p.includes(`brain=${ASSETS}`))).toBe(true);
    expect(paths.every((p) => p.includes("brain="))).toBe(true);
  });

  it("says a cross-brain batch becomes one group per brain", async () => {
    await crossBrainBatch();
    expect(await screen.findByText(/spans 2 brains, so it becomes 2 run/)).toBeTruthy();
  });

  it("refuses to start a batch with a half-filled member rather than dropping it", async () => {
    render(<IndependentRunBuilder />);
    await screen.findByText(/Member 1 brain unset/);
    await userEvent.click(screen.getByRole("button", { name: "Add member" }));
    await userEvent.click(screen.getByRole("button", { name: "Complete 1 in Mortgage" }));

    await screen.findByText(/1 member still needs a brain/);
    // Pricing the complete member is fine — it costs nothing and shows what the batch would cost.
    await waitFor(() => expect(instanceApi.post).toHaveBeenCalledTimes(1));
    const body = vi.mocked(instanceApi.post).mock.calls[0][1] as { members: unknown[] };
    expect(body.members).toHaveLength(1);

    // Starting it is not. Spending on one member because the second was half-filled is not
    // something an operator can undo.
    expect(screen.getByRole("button", { name: /^Run \d+ group/ }))
      .toHaveProperty("disabled", true);
  });

  it("gives each brain its own idempotency key", async () => {
    await crossBrainBatch();
    const run = await screen.findByRole("button", { name: "Run 2 groups" });
    await waitFor(() => expect(run).toHaveProperty("disabled", false));

    vi.mocked(instanceApi.postIdempotent).mockImplementation(
      async (path: string) => ({
        groupId: path.includes(MORTGAGE) ? "g-mortgage" : "g-assets",
        created: true, memberRunIds: [],
      } as never));
    await userEvent.click(run);

    await waitFor(() => expect(instanceApi.postIdempotent).toHaveBeenCalledTimes(2));
    const keys = vi.mocked(instanceApi.postIdempotent).mock.calls.map((c) => c[2]);
    // One key across two brains would make the second group a replay of the first, and the second
    // brain would never run at all.
    expect(new Set(keys).size).toBe(2);
  });

  it("keeps the brains that started when one fails to start", async () => {
    await crossBrainBatch();
    const run = await screen.findByRole("button", { name: "Run 2 groups" });
    await waitFor(() => expect(run).toHaveProperty("disabled", false));

    vi.mocked(instanceApi.postIdempotent).mockImplementation(async (path: string) => {
      if (path.includes(ASSETS)) throw new Error("503");
      return { groupId: "g-mortgage", created: true, memberRunIds: [] } as never;
    });
    await userEvent.click(run);

    // Mortgage is running and stays on screen; Assets reports its own failure beside it.
    await screen.findByText("Watching Mortgage g-mortgage");
    expect(screen.getByText(/Assets could not be started/)).toBeTruthy();
    expect(screen.getByText(/Other brains in this batch are unaffected/)).toBeTruthy();
  });

  it("retries only the brain that failed, under the key its attempt used", async () => {
    await crossBrainBatch();
    const run = await screen.findByRole("button", { name: "Run 2 groups" });
    await waitFor(() => expect(run).toHaveProperty("disabled", false));

    vi.mocked(instanceApi.postIdempotent).mockImplementation(async (path: string) => {
      if (path.includes(ASSETS)) throw new Error("503");
      return { groupId: "g-mortgage", created: true, memberRunIds: [] } as never;
    });
    await userEvent.click(run);
    await screen.findByText("Watching Mortgage g-mortgage");

    const firstAssetsKey = vi.mocked(instanceApi.postIdempotent).mock.calls
      .find((c) => String(c[0]).includes(ASSETS))![2];

    vi.mocked(instanceApi.postIdempotent).mockReset();
    vi.mocked(instanceApi.postIdempotent).mockResolvedValue(
      { groupId: "g-assets", created: false, memberRunIds: [] } as never);
    await userEvent.click(await screen.findByRole("button", { name: "Retry 1 group" }));

    await waitFor(() => expect(instanceApi.postIdempotent).toHaveBeenCalledTimes(1));
    const retried = vi.mocked(instanceApi.postIdempotent).mock.calls[0];
    // Only Assets, and under the same key — so if its first attempt did create a group, this
    // resolves to that one rather than starting a second.
    expect(String(retried[0])).toContain(ASSETS);
    expect(retried[2]).toBe(firstAssetsKey);
    await screen.findByText("Watching Assets g-assets");
  });

  it("will not start a batch that was priced before it was edited", async () => {
    await crossBrainBatch();
    const run = await screen.findByRole("button", { name: "Run 2 groups" });
    await waitFor(() => expect(run).toHaveProperty("disabled", false));

    // A third member changes what would be submitted, so the estimate that enabled Run no longer
    // describes it.
    await userEvent.click(screen.getByRole("button", { name: "Add member" }));

    await waitFor(() => expect(
      screen.getByRole("button", { name: /^Run \d+ group/ })).toHaveProperty("disabled", true));
  });

  it("shows one brain's estimate failing without hiding the other's", async () => {
    vi.mocked(instanceApi.post).mockImplementation(async (path: string) => {
      if (path.includes(ASSETS)) throw new Error("503");
      return preflight();
    });

    await crossBrainBatch();

    await screen.findByText(/Assets could not be estimated/);
    // The Mortgage estimate still rendered; a single failed price must not blank the batch.
    expect(screen.getByRole("table")).toBeTruthy();
  });
});
