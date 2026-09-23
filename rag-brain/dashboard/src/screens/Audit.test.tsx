import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import Audit from "./Audit";

vi.mock("../api", () => ({
  api: { get: vi.fn() },
  brainsApi: { list: vi.fn() },
  learningApi: {
    pending: vi.fn(),
    weights: vi.fn(),
    approve: vi.fn(),
    reject: vi.fn(),
    reset: vi.fn(),
  },
}));

import { api, brainsApi, learningApi } from "../api";

const brain = {
  id: "brain-1",
  slug: "generic",
  displayName: "Generic Brain",
  packRef: null,
  sourceType: null,
  s3Bucket: null,
  s3Prefix: null,
  s3Region: null,
  localPath: null,
  answerProvider: null,
  answerModel: null,
  utilityProvider: null,
  utilityModel: null,
  isDefault: true,
  isActive: true,
  learningEnabled: true,
  dailyCostBudgetUsd: null,
};

const otherBrain = {
  ...brain,
  id: "brain-2",
  slug: "other-brain",
  displayName: "Other Brain",
  isDefault: false,
};

const pendingEvent = {
  id: "event-1",
  brainId: "brain-1",
  documentId: "doc-abcdef01-0000-0000-0000-000000000000",
  oldWeight: 1.0,
  newWeight: null,
  proposedWeight: 1.15,
  evidenceCount: 8,
  status: "PENDING" as const,
  reason: "up-voted repeatedly",
  actor: "learning-job",
  createdAt: "2026-07-06T00:00:00Z",
};

const weight = {
  brainId: "brain-1",
  documentId: "doc-abcdef01-0000-0000-0000-000000000000",
  weight: 1.1,
  feedbackCount: 12,
  updatedAt: "2026-07-06T00:00:00Z",
  updatedBy: "learning-job",
};

describe("Audit learning panel", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    // Audit's own paged list endpoint
    vi.mocked(api.get).mockResolvedValue({ items: [], page: 0, size: 20, total: 0 });
    vi.mocked(brainsApi.list).mockResolvedValue([brain]);
    vi.mocked(learningApi.pending).mockResolvedValue([pendingEvent]);
    vi.mocked(learningApi.weights).mockResolvedValue([weight]);
    vi.mocked(learningApi.approve).mockResolvedValue({ approved: true, eventId: "event-1" });
    vi.mocked(learningApi.reject).mockResolvedValue({ rejected: true, eventId: "event-1" });
    vi.mocked(learningApi.reset).mockResolvedValue({ reset: true, brainId: "brain-1" });
  });

  it("loads pending events and current weights for the default brain, keyed by slug", async () => {
    render(<Audit />);

    expect(await screen.findByText("up-voted repeatedly")).toBeTruthy();
    await waitFor(() => expect(learningApi.pending).toHaveBeenCalledWith("generic"));
    expect(learningApi.weights).toHaveBeenCalledWith("generic");
    expect(screen.getByText("1.150")).toBeTruthy(); // proposed weight, 3dp
  });

  it("approves a pending event scoped to the selected brain's slug and reloads the queue", async () => {
    render(<Audit />);

    await screen.findByText("up-voted repeatedly");
    await userEvent.click(screen.getByRole("button", { name: "Approve" }));

    // R8: approve must carry the selected brain's slug so the backend can verify
    // the event actually belongs to that brain before mutating it.
    await waitFor(() => expect(learningApi.approve).toHaveBeenCalledWith("event-1", "generic"));
    // reload after approve
    await waitFor(() => expect(learningApi.pending).toHaveBeenCalledTimes(2));
  });

  it("rejects a pending event scoped to the selected brain's slug", async () => {
    render(<Audit />);

    await screen.findByText("up-voted repeatedly");
    await userEvent.click(screen.getByRole("button", { name: "Reject" }));

    await waitFor(() => expect(learningApi.reject).toHaveBeenCalledWith("event-1", "generic"));
  });

  it("resets all weights for the brain after confirmation", async () => {
    vi.spyOn(window, "confirm").mockReturnValue(true);
    render(<Audit />);

    await screen.findByText("up-voted repeatedly");
    await userEvent.click(screen.getByRole("button", { name: "Reset weights" }));

    await waitFor(() => expect(learningApi.reset).toHaveBeenCalledWith("generic"));
  });

  it("loads pending events and weights by slug (not id) after switching brains", async () => {
    // Regression: Audit.tsx must forward the selected brain's slug to learningApi,
    // since the backend's ?brain= param is resolved via BrainRepository.findBySlug,
    // not by id. A fixture where id !== slug for the non-default brain would have
    // caught the bug where Audit.tsx passed brainId (a UUID) instead.
    vi.spyOn(window, "confirm").mockReturnValue(true);
    vi.mocked(brainsApi.list).mockResolvedValue([brain, otherBrain]);
    render(<Audit />);

    await screen.findByText("up-voted repeatedly");
    await waitFor(() => expect(learningApi.pending).toHaveBeenCalledWith("generic"));

    await userEvent.selectOptions(screen.getByRole("combobox"), "brain-2");

    await waitFor(() => expect(learningApi.pending).toHaveBeenCalledWith("other-brain"));
    expect(learningApi.weights).toHaveBeenCalledWith("other-brain");
    expect(learningApi.pending).not.toHaveBeenCalledWith("brain-2");
    expect(learningApi.weights).not.toHaveBeenCalledWith("brain-2");

    await userEvent.click(screen.getByRole("button", { name: "Approve" }));
    await waitFor(() => expect(learningApi.approve).toHaveBeenCalledWith("event-1", "other-brain"));
    expect(learningApi.approve).not.toHaveBeenCalledWith("event-1", "brain-2");

    await userEvent.click(screen.getByRole("button", { name: "Reset weights" }));
    await waitFor(() => expect(learningApi.reset).toHaveBeenCalledWith("other-brain"));
  });
});
