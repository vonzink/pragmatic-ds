import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, beforeEach, vi } from "vitest";
import Personality from "./Personality";

vi.mock("../api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../api")>();
  return {
    ...actual,
    profileApi: {
      get: vi.fn(),
      update: vi.fn(),
      rotatePublicToken: vi.fn(),
    },
  };
});

import { profileApi } from "../api";
import { BrainProfileDto, Stats } from "../types";

const stats: Stats = {
  brain: { id: "brain-1", companyName: "Acme", slug: "acme" },
  corpus: { activeDocuments: 1, totalDocuments: 2, chunks: 3 },
};

const profile: BrainProfileDto = {
  brainId: "brain-1",
  mode: "PUBLIC_SITE",
  purpose: "Answer questions",
  audience: "borrowers",
  personality: "Clear and direct",
  tone: "professional",
  expertiseLevel: "advanced",
  answerLength: "balanced",
  confidenceTarget: 0.9,
  retrievalConfidenceThreshold: null,
  answerConfidenceFloor: 0.5,
  clarificationPolicy: "Ask one question when facts are missing.",
  escalationPolicy: "Escalate unsupported questions.",
  citationPolicy: "required_when_sources_used",
  ctaPolicy: "Recommend a page when useful.",
  disclaimer: "This answer may be incomplete.",
  publicEnabled: true,
  allowedDomains: ["example.com"],
};

describe("Personality", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("keeps the form locked after a profile load failure", async () => {
    vi.mocked(profileApi.get).mockRejectedValue(new Error("service unavailable"));

    render(<Personality stats={stats} />);

    expect(await screen.findByText("service unavailable")).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Save profile" })).toBeNull();
    expect(screen.queryByLabelText("Purpose")).toBeNull();
    expect(profileApi.update).not.toHaveBeenCalled();
  });

  it("loads profile data, saves changes, and rotates the public token", async () => {
    vi.mocked(profileApi.get).mockResolvedValue(profile);
    vi.mocked(profileApi.update).mockImplementation(async (_brainId, body) => ({
      ...profile,
      ...body,
    }));
    vi.mocked(profileApi.rotatePublicToken).mockResolvedValue({ token: "pub_live_123" });

    render(<Personality stats={stats} />);

    const toneInput = await screen.findByDisplayValue("professional");
    await userEvent.clear(toneInput);
    await userEvent.type(toneInput, "warm");
    await userEvent.click(screen.getByRole("button", { name: "Save profile" }));

    await waitFor(() => {
      expect(profileApi.update).toHaveBeenCalledWith(
        "brain-1",
        expect.objectContaining({ tone: "warm" }),
      );
    });
    expect(await screen.findByText("saved")).toBeTruthy();

    await userEvent.click(screen.getByRole("button", { name: "Rotate public token" }));

    await waitFor(() => expect(profileApi.rotatePublicToken).toHaveBeenCalledWith("brain-1"));
    expect(await screen.findByText("pub_live_123")).toBeTruthy();
  });
});
