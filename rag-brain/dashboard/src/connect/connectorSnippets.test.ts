import { describe, expect, it } from "vitest";
import { federationCurlSnippet, mcpConfigSnippet, peerRegistrationSnippet } from "./connectorSnippets";

describe("connector snippet builders", () => {
  const params = {
    apiBase: "https://brain.example.com/",
    slug: "mortgage",
    token: "rb_conn_secret",
  };

  it("builds an MCP tool config using bearer auth", () => {
    const snippet = mcpConfigSnippet(params);

    expect(snippet).toContain('"url": "https://brain.example.com/mcp/tools"');
    expect(snippet).toContain('"Authorization": "Bearer rb_conn_secret"');
  });

  it("builds a federation ask curl snippet", () => {
    const snippet = federationCurlSnippet(params);

    expect(snippet).toContain("https://brain.example.com/api/connect/v1/brains/mortgage/ask");
    expect(snippet).toContain("Authorization: Bearer rb_conn_secret");
    expect(snippet).toContain('"message":"What can you help me with?"');
  });

  it("builds a peer registration snippet with the discovery manifest", () => {
    const snippet = peerRegistrationSnippet(params);

    expect(snippet).toContain("https://brain.example.com/.well-known/rag-brain.json");
    expect(snippet).toContain("mortgage");
  });
});
