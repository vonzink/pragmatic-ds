export interface ConnectorSnippetParams {
  apiBase: string;
  slug: string;
  token: string;
}

export const CONNECTOR_TOKEN_PLACEHOLDER = "rb_conn_YOUR_TOKEN";

function clean(p: ConnectorSnippetParams): Required<ConnectorSnippetParams> {
  return {
    apiBase: (p.apiBase || "").replace(/\/+$/, ""),
    slug: p.slug || "generic",
    token: p.token || CONNECTOR_TOKEN_PLACEHOLDER,
  };
}

export function mcpConfigSnippet(p: ConnectorSnippetParams): string {
  const c = clean(p);
  return JSON.stringify({
    mcpServers: {
      "rag-brain": {
        url: `${c.apiBase}/mcp/tools`,
        headers: {
          Authorization: `Bearer ${c.token}`,
        },
      },
    },
  }, null, 2);
}

export function federationCurlSnippet(p: ConnectorSnippetParams): string {
  const c = clean(p);
  const body = `{"sessionId":"connector-test","message":"What can you help me with?","pageRoute":"/","facts":{}}`;
  return [
    `curl -X POST "${c.apiBase}/api/connect/v1/brains/${c.slug}/ask" \\`,
    `  -H "Content-Type: application/json" \\`,
    `  -H "Authorization: Bearer ${c.token}" \\`,
    `  -d '${body}'`,
  ].join("\n");
}

export function peerRegistrationSnippet(p: ConnectorSnippetParams): string {
  const c = clean(p);
  return JSON.stringify({
    manifest: `${c.apiBase}/.well-known/rag-brain.json`,
    preferredBrain: c.slug,
    authorization: `Bearer ${c.token}`,
  }, null, 2);
}
