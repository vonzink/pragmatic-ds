import { describe, expect, it } from "vitest";
import { dashboardToolCallCurlSnippet, sampleArgumentsFromSchema } from "./toolSnippets";
import { ToolDefinitionDto } from "../types";

const tool: ToolDefinitionDto = {
  id: "tool-1",
  brainId: "brain-1",
  name: "searchVisibleLoans",
  description: "Search loans visible to the current user.",
  mode: "READ",
  confirmationRequired: false,
  requiredPermissions: ["dashboard.loans.read"],
  inputSchema: {
    type: "object",
    properties: {
      query: { type: "string" },
      limit: { type: "number" },
    },
  },
  active: true,
  createdAt: "2026-07-03T00:00:00Z",
  updatedAt: "2026-07-03T00:00:00Z",
};

describe("tool snippet builders", () => {
  it("derives simple sample arguments from object properties", () => {
    expect(sampleArgumentsFromSchema(tool.inputSchema)).toEqual({
      query: "example",
      limit: "example",
    });
  });

  it("builds a dashboard tool call curl snippet", () => {
    const snippet = dashboardToolCallCurlSnippet({
      apiBase: "https://brain.example.com/",
      slug: "dashboard-brain",
      tool,
    });

    expect(snippet).toContain("https://brain.example.com/api/connect/v1/brains/dashboard-brain/dashboard/tools/searchVisibleLoans/call");
    expect(snippet).toContain("Authorization: Bearer $RAG_BRAIN_CONNECTOR_TOKEN");
    expect(snippet).toContain('"permissions":["dashboard.loans.read"]');
    expect(snippet).toContain('"confirmed":false');
  });
});
