import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import Tools, { parsePermissions, parseSchemaText } from "./Tools";

vi.mock("../api", () => ({
  brainsApi: { list: vi.fn() },
  toolAdaptersApi: {
    get: vi.fn(),
    upsert: vi.fn(),
    remove: vi.fn(),
    runs: vi.fn(),
  },
  toolDefinitionsApi: {
    list: vi.fn(),
    create: vi.fn(),
    update: vi.fn(),
    activate: vi.fn(),
    deactivate: vi.fn(),
    remove: vi.fn(),
  },
}));

import { brainsApi, toolAdaptersApi, toolDefinitionsApi } from "../api";

const brains = [
  {
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
    learningEnabled: false,
    dailyCostBudgetUsd: null,
  },
  {
    id: "brain-2",
    slug: "dashboard-brain",
    displayName: "Dashboard Brain",
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
    isDefault: false,
    isActive: true,
    learningEnabled: false,
    dailyCostBudgetUsd: null,
  },
];

const tools = [
  {
    id: "tool-1",
    brainId: "brain-1",
    name: "searchVisibleLoans",
    description: "Search loans visible to the current user.",
    mode: "READ" as const,
    confirmationRequired: false,
    requiredPermissions: ["dashboard.loans.read"],
    inputSchema: { type: "object", properties: { query: { type: "string" } } },
    active: true,
    createdAt: "2026-07-03T00:00:00Z",
    updatedAt: "2026-07-03T00:00:00Z",
  },
];

describe("Tools", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(brainsApi.list).mockResolvedValue(brains);
    vi.mocked(toolDefinitionsApi.list).mockResolvedValue(tools);
    vi.mocked(toolDefinitionsApi.create).mockResolvedValue(tools[0]);
    vi.mocked(toolDefinitionsApi.update).mockResolvedValue(tools[0]);
    vi.mocked(toolDefinitionsApi.activate).mockResolvedValue(tools[0]);
    vi.mocked(toolDefinitionsApi.deactivate).mockResolvedValue({ ...tools[0], active: false });
    vi.mocked(toolDefinitionsApi.remove).mockResolvedValue({ deleted: true, id: "tool-1" });
    vi.mocked(toolAdaptersApi.get).mockResolvedValue(null);
    vi.mocked(toolAdaptersApi.upsert).mockResolvedValue({
      id: "adapter-1",
      brainId: "brain-1",
      toolName: "searchVisibleLoans",
      enabled: true,
      httpMethod: "POST",
      urlTemplate: "https://dashboard.example.com/api/search",
      authMode: "BEARER_TOKEN",
      secretRef: "dashboard_api",
      apiKeyHeader: "",
      staticHeaders: { "X-App": "rag-brain" },
      requestBodyTemplate: { query: "{query}" },
      timeoutMs: 5000,
      allowedHosts: ["dashboard.example.com"],
      createdAt: "2026-07-03T00:00:00Z",
      updatedAt: "2026-07-03T00:00:00Z",
    });
    vi.mocked(toolAdaptersApi.remove).mockResolvedValue({ deleted: true, toolName: "searchVisibleLoans" });
    vi.mocked(toolAdaptersApi.runs).mockResolvedValue([]);
  });

  it("parses permissions from comma and newline separated text", () => {
    expect(parsePermissions(" dashboard.loans.read, dashboard.tasks.read\n\n")).toEqual([
      "dashboard.loans.read",
      "dashboard.tasks.read",
    ]);
  });

  it("rejects invalid schema text", () => {
    expect(() => parseSchemaText("[1,2,3]")).toThrow("Input schema must be a JSON object");
  });

  it("loads and renders tools for the default brain", async () => {
    render(<Tools />);

    expect(await screen.findByText("Tools")).toBeTruthy();
    expect(await screen.findByText("searchVisibleLoans")).toBeTruthy();
    expect(screen.getByText("1 active")).toBeTruthy();
    expect(toolDefinitionsApi.list).toHaveBeenCalledWith("generic");
  });

  it("creates a read tool from the form", async () => {
    const user = userEvent.setup();
    render(<Tools />);

    await screen.findByText("searchVisibleLoans");
    await user.click(screen.getByRole("button", { name: "New tool" }));
    await user.type(screen.getByLabelText("Name"), "listVisibleTasks");
    await user.type(screen.getByLabelText("Description"), "List visible dashboard tasks.");
    await user.type(screen.getByLabelText("Required permissions"), "dashboard.tasks.read");
    await user.click(screen.getByRole("button", { name: "Create tool" }));

    await waitFor(() => expect(toolDefinitionsApi.create).toHaveBeenCalledWith("generic", expect.objectContaining({
      name: "listVisibleTasks",
      description: "List visible dashboard tasks.",
      mode: "READ",
      requiredPermissions: ["dashboard.tasks.read"],
    })));
  });

  it("edits an existing tool", async () => {
    const user = userEvent.setup();
    render(<Tools />);

    await screen.findByText("searchVisibleLoans");
    await user.click(screen.getByRole("button", { name: "Edit" }));
    await user.clear(screen.getByLabelText("Description"));
    await user.type(screen.getByLabelText("Description"), "Search visible loans and files.");
    await user.click(screen.getByRole("button", { name: "Save tool" }));

    await waitFor(() => expect(toolDefinitionsApi.update).toHaveBeenCalledWith("generic", "tool-1", expect.objectContaining({
      name: "searchVisibleLoans",
      description: "Search visible loans and files.",
      mode: "READ",
    })));
  });

  it("deactivates an active tool", async () => {
    const user = userEvent.setup();
    render(<Tools />);

    await screen.findByText("searchVisibleLoans");
    await user.click(screen.getByRole("button", { name: "Deactivate" }));

    await waitFor(() => expect(toolDefinitionsApi.deactivate).toHaveBeenCalledWith("generic", "tool-1"));
  });

  it("blocks invalid JSON schema before saving", async () => {
    const user = userEvent.setup();
    render(<Tools />);

    await screen.findByText("searchVisibleLoans");
    await user.click(screen.getByRole("button", { name: "New tool" }));
    await user.type(screen.getByLabelText("Name"), "badSchemaTool");
    await user.type(screen.getByLabelText("Description"), "Bad schema.");
    const schemaInput = screen.getByLabelText("Input schema");
    await user.clear(schemaInput);
    fireEvent.change(schemaInput, { target: { value: "[1,2,3]" } });
    await user.click(screen.getByRole("button", { name: "Create tool" }));

    expect(await screen.findByText("Input schema must be a JSON object")).toBeTruthy();
    expect(toolDefinitionsApi.create).not.toHaveBeenCalled();
  });

  it("loads adapter config for the selected tool", async () => {
    vi.mocked(toolAdaptersApi.get).mockResolvedValue({
      id: "adapter-1",
      brainId: "brain-1",
      toolName: "searchVisibleLoans",
      enabled: true,
      httpMethod: "POST",
      urlTemplate: "https://dashboard.example.com/api/search",
      authMode: "BEARER_TOKEN",
      secretRef: "dashboard_api",
      apiKeyHeader: "",
      staticHeaders: { "X-App": "rag-brain" },
      requestBodyTemplate: { query: "{query}" },
      timeoutMs: 5000,
      allowedHosts: ["dashboard.example.com"],
      createdAt: "2026-07-03T00:00:00Z",
      updatedAt: "2026-07-03T00:00:00Z",
    });

    render(<Tools />);

    expect(await screen.findByDisplayValue("https://dashboard.example.com/api/search")).toBeTruthy();
    expect(screen.getByDisplayValue("dashboard_api")).toBeTruthy();
    expect(toolAdaptersApi.get).toHaveBeenCalledWith("generic", "searchVisibleLoans");
  });

  it("saves adapter config with parsed JSON fields", async () => {
    const user = userEvent.setup();
    render(<Tools />);

    await screen.findByText("searchVisibleLoans");
    await user.click(screen.getByLabelText("Adapter enabled"));
    await user.selectOptions(screen.getByLabelText("HTTP method"), "POST");
    await user.type(screen.getByLabelText("URL template"), "https://dashboard.example.com/api/search");
    await user.selectOptions(screen.getByLabelText("Auth mode"), "BEARER_TOKEN");
    await user.type(screen.getByLabelText("Secret ref"), "dashboard_api");
    await user.type(screen.getByLabelText("Allowed hosts (one per line)"), "dashboard.example.com");
    const headers = screen.getByLabelText("Static headers JSON");
    await user.clear(headers);
    fireEvent.change(headers, { target: { value: "{\"X-App\":\"rag-brain\"}" } });
    const body = screen.getByLabelText("Request body template JSON");
    await user.clear(body);
    fireEvent.change(body, { target: { value: "{\"query\":\"{query}\"}" } });
    await user.click(screen.getByRole("button", { name: "Save adapter" }));

    await waitFor(() => expect(toolAdaptersApi.upsert).toHaveBeenCalledWith("generic", "searchVisibleLoans", {
      enabled: true,
      httpMethod: "POST",
      urlTemplate: "https://dashboard.example.com/api/search",
      authMode: "BEARER_TOKEN",
      secretRef: "dashboard_api",
      apiKeyHeader: "",
      staticHeaders: { "X-App": "rag-brain" },
      requestBodyTemplate: { query: "{query}" },
      timeoutMs: 5000,
      allowedHosts: ["dashboard.example.com"],
    }));
  });

  it("blocks enabling an adapter without allowed hosts", async () => {
    const user = userEvent.setup();
    render(<Tools />);

    await screen.findByText("searchVisibleLoans");
    await user.click(screen.getByLabelText("Adapter enabled"));
    await user.type(screen.getByLabelText("URL template"), "https://dashboard.example.com/api/search");
    await user.click(screen.getByRole("button", { name: "Save adapter" }));

    expect(await screen.findByText("Allowed hosts are required when the adapter is enabled")).toBeTruthy();
    expect(toolAdaptersApi.upsert).not.toHaveBeenCalled();
  });

  it("blocks invalid adapter headers JSON before save", async () => {
    const user = userEvent.setup();
    render(<Tools />);

    await screen.findByText("searchVisibleLoans");
    const headers = screen.getByLabelText("Static headers JSON");
    await user.clear(headers);
    fireEvent.change(headers, { target: { value: "[1,2,3]" } });
    await user.click(screen.getByRole("button", { name: "Save adapter" }));

    expect(await screen.findByText("Static headers JSON must be a JSON object")).toBeTruthy();
    expect(toolAdaptersApi.upsert).not.toHaveBeenCalled();
  });

  it("shows secret reference without a secret value", async () => {
    vi.mocked(toolAdaptersApi.get).mockResolvedValue({
      id: "adapter-1",
      brainId: "brain-1",
      toolName: "searchVisibleLoans",
      enabled: true,
      httpMethod: "GET",
      urlTemplate: "https://dashboard.example.com/api/search",
      authMode: "BEARER_TOKEN",
      secretRef: "dashboard_api",
      apiKeyHeader: "",
      staticHeaders: {},
      requestBodyTemplate: {},
      timeoutMs: 5000,
      allowedHosts: ["dashboard.example.com"],
      createdAt: "2026-07-03T00:00:00Z",
      updatedAt: "2026-07-03T00:00:00Z",
    });

    render(<Tools />);

    expect(await screen.findByDisplayValue("dashboard_api")).toBeTruthy();
    expect(screen.queryByDisplayValue("secret-token")).toBeNull();
  });
});
