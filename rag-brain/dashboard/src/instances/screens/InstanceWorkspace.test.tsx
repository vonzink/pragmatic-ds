import { render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";

/**
 * The workspace's contract is that its scope comes from the path and from nowhere else.
 *
 * That is what makes a workspace URL shareable: paste it to a colleague and they see the same
 * instance, regardless of what their sidebar was last set to. So the tests drive it through a real
 * route with real params, and assert the request carried those params rather than anything ambient.
 */

vi.mock("../api", () => ({ instanceApi: { get: vi.fn() } }));

import { instanceApi } from "../api";
import InstanceWorkspace from "./InstanceWorkspace";

const BRAIN = "11111111-1111-4111-8111-111111111111";

function detail(over: Partial<Record<string, unknown>> = {}) {
  return {
    brainId: BRAIN,
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
    liveRelease: null,
    ...over,
  };
}

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/instances/:brainId/:instanceSlug/*" element={<InstanceWorkspace />} />
      </Routes>
    </MemoryRouter>,
  );
}

beforeEach(() => vi.clearAllMocks());

describe("InstanceWorkspace", () => {
  it("reads its brain and instance from the path", async () => {
    vi.mocked(instanceApi.get).mockResolvedValue(detail() as never);

    renderAt(`/instances/${BRAIN}/income/workbench`);

    await waitFor(() => expect(instanceApi.get).toHaveBeenCalled());
    const path = vi.mocked(instanceApi.get).mock.calls[0][0];
    expect(path).toContain("/instances/income");
    expect(path).toContain(`brain=${BRAIN}`);
  });

  it("offers the four sections", async () => {
    vi.mocked(instanceApi.get).mockResolvedValue(detail() as never);

    renderAt(`/instances/${BRAIN}/income/workbench`);

    await waitFor(() => expect(screen.getByText("Income")).toBeTruthy());
    for (const tab of ["Workbench", "Compare", "Configuration", "Releases"]) {
      expect(screen.getByRole("link", { name: tab })).toBeTruthy();
    }
  });

  it("shows the live release and model in the breadcrumb summary", async () => {
    vi.mocked(instanceApi.get).mockResolvedValue(detail() as never);

    renderAt(`/instances/${BRAIN}/income/workbench`);

    await waitFor(() => expect(screen.getByText("Live r3")).toBeTruthy());
    expect(screen.getByText("anthropic / claude-opus-5")).toBeTruthy();
  });

  it("badges a candidate that production is not answering with", async () => {
    vi.mocked(instanceApi.get).mockResolvedValue(
      detail({ hasCandidateRelease: true, candidateCount: 1 }) as never);

    renderAt(`/instances/${BRAIN}/income/workbench`);

    // Someone who built a candidate and forgot to promote it would otherwise read a run's output
    // as the live behaviour.
    await waitFor(() => expect(screen.getByText("Candidate not live")).toBeTruthy());
  });

  it("sends a bare workspace URL to the workbench", async () => {
    vi.mocked(instanceApi.get).mockResolvedValue(detail() as never);

    renderAt(`/instances/${BRAIN}/income`);

    await waitFor(() => expect(screen.getByRole("heading", { name: "Workbench" })).toBeTruthy());
  });

  it("reports a load failure without pretending the instance is fine", async () => {
    vi.mocked(instanceApi.get).mockRejectedValue(new Error("404"));

    renderAt(`/instances/${BRAIN}/income/workbench`);

    await waitFor(() => expect(screen.getByRole("alert")).toBeTruthy());
    expect(screen.queryByText("Live r3")).toBeNull();
  });
});
