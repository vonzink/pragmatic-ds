import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import Brains from "./Brains";

vi.mock("../api", () => ({
  api: { get: vi.fn() },
  brainsApi: {
    list: vi.fn(), create: vi.fn(), update: vi.fn(), activate: vi.fn(),
    sync: vi.fn(), remove: vi.fn(), restore: vi.fn(),
  },
  learningApi: { toggle: vi.fn() },
}));

import { api, brainsApi, learningApi } from "../api";

const brain = (over: Record<string, unknown> = {}) => ({
  id: "brain-1",
  slug: "generic",
  displayName: "Generic Brain",
  packRef: null,
  sourceType: null,
  s3Bucket: null,
  s3Prefix: null,
  s3Region: null,
  localPath: null,
  answerProvider: "anthropic",
  answerModel: null,
  utilityProvider: "openai",
  utilityModel: null,
  isDefault: true,
  isActive: true,
  learningEnabled: false,
  dailyCostBudgetUsd: null,
  ...over,
});

describe("Brains learning toggle", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(brainsApi.list).mockResolvedValue([brain()]);
    vi.mocked(api.get).mockResolvedValue({ providers: [] });
    vi.mocked(learningApi.toggle).mockResolvedValue(brain({ learningEnabled: true }));
  });

  it("shows learning off and enables it when the operator clicks the toggle", async () => {
    render(<Brains />);

    await screen.findByText("Generic Brain");
    expect(screen.getByText("learning off")).toBeTruthy();

    await userEvent.click(screen.getByRole("button", { name: "Enable learning" }));

    await waitFor(() => expect(learningApi.toggle).toHaveBeenCalledWith("brain-1", true));
    expect(await screen.findByText("learning on")).toBeTruthy();
  });

  it("shows a disable action when learning is already on", async () => {
    vi.mocked(brainsApi.list).mockResolvedValue([brain({ learningEnabled: true })]);
    vi.mocked(learningApi.toggle).mockResolvedValue(brain({ learningEnabled: false }));

    render(<Brains />);

    await screen.findByText("Generic Brain");
    await userEvent.click(screen.getByRole("button", { name: "Disable learning" }));

    await waitFor(() => expect(learningApi.toggle).toHaveBeenCalledWith("brain-1", false));
  });
});

describe("Brains edit", () => {
  const lending = brain({
    id: "brain-2", slug: "lending", displayName: "Lending", isDefault: false,
    packRef: "packs/lending", sourceType: "s3", s3Bucket: "example-bucket",
    s3Prefix: "rag-brain-lending/", s3Region: "us-west-1",
    dailyCostBudgetUsd: 2.5,
  });

  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(brainsApi.list).mockResolvedValue([lending]);
    vi.mocked(api.get).mockResolvedValue({ providers: [] });
  });

  it("prefills the form, saves via PUT, and round-trips the daily budget", async () => {
    vi.mocked(brainsApi.update).mockResolvedValue({ ...lending, displayName: "Lending v2" });

    render(<Brains />);
    await screen.findByText("Lending");
    await userEvent.click(screen.getByRole("button", { name: "Edit" }));

    expect(screen.getByText("Edit brain — Lending")).toBeTruthy();
    const name = screen.getByDisplayValue("Lending");
    await userEvent.clear(name);
    await userEvent.type(name, "Lending v2");
    await userEvent.click(screen.getByRole("button", { name: "Save brain" }));

    await waitFor(() => expect(brainsApi.update).toHaveBeenCalledWith("brain-2",
      expect.objectContaining({
        slug: "lending",
        displayName: "Lending v2",
        packRef: "packs/lending",
        sourceType: "s3",
        s3Bucket: "example-bucket",
        dailyCostBudgetUsd: 2.5,
      })));
    // Save returns the card to create mode.
    expect(await screen.findByRole("heading", { name: "Create brain" })).toBeTruthy();
  });

  it("cancel restores the create form without calling the API", async () => {
    render(<Brains />);
    await screen.findByText("Lending");
    await userEvent.click(screen.getByRole("button", { name: "Edit" }));
    await userEvent.click(screen.getByRole("button", { name: "Cancel" }));

    expect(screen.getByRole("heading", { name: "Create brain" })).toBeTruthy();
    expect(brainsApi.update).not.toHaveBeenCalled();
  });
});

describe("Brains delete and restore", () => {
  const idle = brain({ id: "brain-2", slug: "lending", displayName: "Lending", isDefault: false });

  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(api.get).mockResolvedValue({ providers: [] });
  });

  it("soft-deletes an idle brain after confirmation", async () => {
    vi.mocked(brainsApi.list).mockResolvedValue([idle]);
    vi.mocked(brainsApi.remove).mockResolvedValue(brain({ ...idle, isActive: false }));
    const confirmSpy = vi.spyOn(window, "confirm").mockReturnValue(true);

    render(<Brains />);
    await screen.findByText("Lending");
    await userEvent.click(screen.getByRole("button", { name: "Delete" }));

    await waitFor(() => expect(brainsApi.remove).toHaveBeenCalledWith("brain-2"));
    confirmSpy.mockRestore();
  });

  it("does not delete when the confirmation is declined", async () => {
    vi.mocked(brainsApi.list).mockResolvedValue([idle]);
    const confirmSpy = vi.spyOn(window, "confirm").mockReturnValue(false);

    render(<Brains />);
    await screen.findByText("Lending");
    await userEvent.click(screen.getByRole("button", { name: "Delete" }));

    expect(brainsApi.remove).not.toHaveBeenCalled();
    confirmSpy.mockRestore();
  });

  it("disables Delete for the default brain", async () => {
    vi.mocked(brainsApi.list).mockResolvedValue([brain()]);   // default brain

    render(<Brains />);
    await screen.findByText("Generic Brain");
    expect((screen.getByRole("button", { name: "Delete" }) as HTMLButtonElement).disabled).toBe(true);
  });

  it("offers Restore instead of Delete for a disabled brain", async () => {
    vi.mocked(brainsApi.list).mockResolvedValue([brain({ ...idle, isActive: false })]);
    vi.mocked(brainsApi.restore).mockResolvedValue(idle);

    render(<Brains />);
    await screen.findByText("Lending");
    expect(screen.queryByRole("button", { name: "Delete" })).toBeNull();
    await userEvent.click(screen.getByRole("button", { name: "Restore" }));

    await waitFor(() => expect(brainsApi.restore).toHaveBeenCalledWith("brain-2"));
  });
});
