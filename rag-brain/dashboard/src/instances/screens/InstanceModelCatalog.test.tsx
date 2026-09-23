import { render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

/**
 * The catalog is the only place model options come from, so what it must never do matters as much
 * as what it shows: no credential, and no free-text way to name a model that is not configured.
 */

vi.mock("../api", () => ({ instanceApi: { get: vi.fn() } }));

import { instanceApi } from "../api";
import InstanceModelCatalog from "./InstanceModelCatalog";

function model(over: Partial<Record<string, unknown>> = {}) {
  return {
    provider: "anthropic",
    model: "claude-opus-5",
    contextTokenCeiling: 200000,
    outputTokenCeiling: 64000,
    tokenizerStrategy: "EXACT",
    inputUsdPerMillion: 5,
    cachedInputUsdPerMillion: 0.5,
    outputUsdPerMillion: 25,
    ...over,
  };
}

beforeEach(() => vi.clearAllMocks());

describe("InstanceModelCatalog", () => {
  it("asks the deployment-wide endpoint, with no brain attached", async () => {
    vi.mocked(instanceApi.get).mockResolvedValue([model()] as never);

    render(<InstanceModelCatalog />);

    await waitFor(() => expect(screen.getByText("claude-opus-5")).toBeTruthy());
    const path = vi.mocked(instanceApi.get).mock.calls[0][0];
    expect(path).toBe("/api/ai/admin/instances/model-catalog");
    expect(path).not.toContain("brain");
  });

  it("shows the rates and ceilings that make an estimate checkable", async () => {
    vi.mocked(instanceApi.get).mockResolvedValue([model()] as never);

    render(<InstanceModelCatalog />);

    await waitFor(() => expect(screen.getByText("200,000")).toBeTruthy());
    expect(screen.getByText("64,000")).toBeTruthy();
    expect(screen.getByText("EXACT")).toBeTruthy();
  });

  it("offers no way to type a model that is not configured", async () => {
    vi.mocked(instanceApi.get).mockResolvedValue([model()] as never);

    render(<InstanceModelCatalog />);

    await waitFor(() => expect(screen.getByText("claude-opus-5")).toBeTruthy());
    // A free-text model field would let someone name something plausible and find out it is not
    // configured only after a dispatch failure. This screen is a read.
    expect(screen.queryAllByRole("textbox")).toHaveLength(0);
    expect(screen.queryAllByRole("button")).toHaveLength(0);
  });

  it("explains an empty catalog rather than showing a blank table", async () => {
    vi.mocked(instanceApi.get).mockResolvedValue([] as never);

    render(<InstanceModelCatalog />);

    await waitFor(() => expect(screen.getByText(/No models are configured/)).toBeTruthy());
    expect(screen.queryByRole("table")).toBeNull();
  });

  it("reports a failure instead of implying nothing is configured", async () => {
    vi.mocked(instanceApi.get).mockRejectedValue(new Error("503"));

    render(<InstanceModelCatalog />);

    await waitFor(() => expect(screen.getByRole("alert")).toBeTruthy());
    expect(screen.queryByText(/No models are configured/)).toBeNull();
  });
});
