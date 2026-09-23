import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import Analyzers from "./Analyzers";

vi.mock("../api", () => ({
  api: { get: vi.fn(), put: vi.fn(), post: vi.fn(), del: vi.fn() },
  brainsApi: { list: vi.fn() },
}));

import { api, brainsApi } from "../api";
import { AnalyzerAssemblyDto, AnalyzerPromptState } from "../types";

const brain = {
  id: "brain-1", slug: "mortgage", displayName: "Mortgage", packRef: null, sourceType: null,
  s3Bucket: null, s3Prefix: null, s3Region: null, localPath: null, answerProvider: null,
  answerModel: null, utilityProvider: null, utilityModel: null, isDefault: true, isActive: true,
  learningEnabled: false, dailyCostBudgetUsd: null,
};

const income: AnalyzerPromptState = {
  slug: "income", displayName: "Income Analyzer", corpusScope: "income",
  retrievalQueryTemplate: "income calculation and documentation guidelines", retrievalTopK: 8,
  envelope: "v1", v2: false, packDefault: "PACK INCOME PROMPT",
  published: { content: "PACK INCOME PROMPT", source: "pack", updatedAt: null, updatedBy: null },
  draft: null,
};
const credit: AnalyzerPromptState = {
  ...income, slug: "credit", displayName: "Credit Analyzer", corpusScope: "credit",
  packDefault: "PACK CREDIT PROMPT",
  published: { content: "CUSTOM CREDIT", source: "custom", updatedAt: "2026-09-21T00:00:00Z", updatedBy: "admin-api" },
  draft: { content: "CREDIT DRAFT", savedAt: "2026-09-21T01:00:00Z", savedBy: "admin-api" },
};

function assembly(base: string): AnalyzerAssemblyDto {
  return {
    source: "published",
    sections: [
      { id: "base-prompt", title: "Analyzer base prompt", text: `${base}\n\n`, kind: "BASE_PROMPT" },
      { id: "guidelines", title: "Guideline excerpts", text: "[Filled at run time: up to 8 guideline excerpts]\n\n", kind: "DYNAMIC" },
      { id: "output-contract", title: "Output contract", text: "Return ONLY valid JSON in EXACTLY this shape\n", kind: "STATIC" },
    ],
  };
}

function mockGets(states: AnalyzerPromptState[]) {
  vi.mocked(api.get).mockImplementation(async (path: string) => {
    if (path.startsWith("/api/ai/admin/analyzers?") || path === "/api/ai/admin/analyzers") return states;
    if (path.includes("/assembly")) {
      const slug = path.split("/api/ai/admin/analyzers/")[1].split("/")[0];
      const st = states.find((s) => s.slug === slug)!;
      return assembly(path.includes("source=draft") && st.draft ? st.draft.content : st.published.content);
    }
    if (path.includes("/history")) return [];
    throw new Error(`unexpected GET ${path}`);
  });
}

describe("Analyzers", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(brainsApi.list).mockResolvedValue([brain]);
    vi.spyOn(window, "confirm").mockReturnValue(true);
  });

  it("lists analyzers with source and draft badges and shows the assembled prompt", async () => {
    mockGets([income, credit]);
    render(<Analyzers />);

    const list = await screen.findByRole("list", { name: "Analyzers" });
    expect(within(list).getByText("Income Analyzer")).toBeTruthy();
    const creditRow = within(list).getByText("Credit Analyzer").closest("li")!;
    expect(within(creditRow).getByText("custom")).toBeTruthy();
    expect(within(creditRow).getByText("draft")).toBeTruthy();

    expect(await screen.findByLabelText("Base prompt")).toBeTruthy();
    expect((screen.getByLabelText("Base prompt") as HTMLTextAreaElement).value).toBe("PACK INCOME PROMPT");
    expect(screen.getByText("Guideline excerpts")).toBeTruthy();
    expect(screen.getByText("Output contract")).toBeTruthy();
    expect(screen.getByText(/Instance releases carry their own prompt/)).toBeTruthy();
  });

  it("saves a draft, then publishes it after confirm", async () => {
    mockGets([income]);
    vi.mocked(api.put).mockResolvedValue({
      ...income, draft: { content: "EDITED", savedAt: "2026-09-21T02:00:00Z", savedBy: "admin-api" },
    });
    vi.mocked(api.post).mockResolvedValue({
      ...income, published: { content: "EDITED", source: "custom", updatedAt: "2026-09-21T03:00:00Z", updatedBy: "admin-api" },
    });
    render(<Analyzers />);
    const box = await screen.findByLabelText("Base prompt");

    expect(screen.getByRole("button", { name: "Publish" }).hasAttribute("disabled")).toBe(true);
    await userEvent.clear(box);
    await userEvent.type(box, "EDITED");
    await userEvent.click(screen.getByRole("button", { name: "Save draft" }));
    await waitFor(() => expect(api.put).toHaveBeenCalledWith(
      "/api/ai/admin/analyzers/income/prompt/draft?brain=mortgage", { content: "EDITED" }));

    await waitFor(() => expect(screen.getByRole("button", { name: "Publish" }).hasAttribute("disabled")).toBe(false));
    await userEvent.click(screen.getByRole("button", { name: "Publish" }));
    expect(window.confirm).toHaveBeenCalled();
    await waitFor(() => expect(api.post).toHaveBeenCalledWith(
      "/api/ai/admin/analyzers/income/prompt/publish?brain=mortgage", {}));
  });

  it("reverts a custom prompt to the pack default", async () => {
    mockGets([credit]);
    vi.mocked(api.post).mockResolvedValue({ ...credit, published: { ...income.published }, draft: credit.draft });
    render(<Analyzers />);
    await screen.findByLabelText("Base prompt");

    await userEvent.click(screen.getByRole("button", { name: "Revert to pack default" }));
    await waitFor(() => expect(api.post).toHaveBeenCalledWith(
      "/api/ai/admin/analyzers/credit/prompt/revert?brain=mortgage", {}));
  });

  it("discards a draft", async () => {
    mockGets([credit]);
    vi.mocked(api.del).mockResolvedValue({ ...credit, draft: null });
    render(<Analyzers />);
    await screen.findByLabelText("Base prompt");

    await userEvent.click(screen.getByRole("button", { name: "Discard draft" }));
    await waitFor(() => expect(api.del).toHaveBeenCalledWith(
      "/api/ai/admin/analyzers/credit/prompt/draft?brain=mortgage"));
  });
});
