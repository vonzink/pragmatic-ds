import { ToolDefinitionDto } from "../types";

export interface ToolCallSnippetParams {
  apiBase: string;
  slug: string;
  tool: ToolDefinitionDto;
}

function cleanBase(apiBase: string) {
  return (apiBase || "").replace(/\/+$/, "");
}

export function sampleArgumentsFromSchema(schema: Record<string, unknown>): Record<string, string> {
  const properties = schema.properties;
  if (!properties || typeof properties !== "object" || Array.isArray(properties)) {
    return {};
  }
  return Object.fromEntries(Object.keys(properties).map((key) => [key, "example"]));
}

export function dashboardToolCallCurlSnippet(params: ToolCallSnippetParams): string {
  const apiBase = cleanBase(params.apiBase);
  const body = {
    sessionId: "dashboard-tool-test",
    user: {
      userId: "local-user",
      tenantId: "local-tenant",
      roles: ["admin"],
      permissions: params.tool.requiredPermissions,
    },
    arguments: sampleArgumentsFromSchema(params.tool.inputSchema),
    confirmed: false,
  };
  return [
    `curl -X POST "${apiBase}/api/connect/v1/brains/${params.slug}/dashboard/tools/${params.tool.name}/call" \\`,
    `  -H "Content-Type: application/json" \\`,
    `  -H "Authorization: Bearer $RAG_BRAIN_CONNECTOR_TOKEN" \\`,
    `  -d '${JSON.stringify(body)}'`,
  ].join("\n");
}
