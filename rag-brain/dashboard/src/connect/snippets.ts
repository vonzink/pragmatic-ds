export interface SnippetParams {
  apiBase: string;
  slug: string;
  token: string;
  title?: string;
}

export const TOKEN_PLACEHOLDER = "rb_pub_YOUR_TOKEN";
export const ORIGIN_PLACEHOLDER = "https://your-site.example";

function clean(p: SnippetParams): Required<SnippetParams> {
  return {
    apiBase: (p.apiBase || "").replace(/\/+$/, ""),
    slug: p.slug || "",
    token: p.token || TOKEN_PLACEHOLDER,
    title: p.title?.trim() || "Ask the assistant",
  };
}

/**
 * Extracts a bare host from a user-entered origin or domain. Accepts
 * "https://www.example.com/path", "example.com", "http://localhost:3000".
 * Returns null when nothing host-like can be derived.
 */
export function toHost(input: string): string | null {
  const raw = (input || "").trim();
  if (!raw) return null;
  const withScheme = /^[a-z][a-z0-9+.-]*:\/\//i.test(raw) ? raw : `https://${raw}`;
  try {
    const host = new URL(withScheme).hostname.toLowerCase();
    return host || null;
  } catch {
    return null;
  }
}

/** Parses a comma/newline/space separated list of origins into unique hosts. */
export function parseDomains(input: string): string[] {
  const hosts = (input || "")
    .split(/[\s,]+/)
    .map(toHost)
    .filter((h): h is string => !!h);
  return Array.from(new Set(hosts));
}

export function widgetSnippet(p: SnippetParams): string {
  const c = clean(p);
  return [
    `<script src="${c.apiBase}/widget/rag-brain-widget.js"`,
    `        data-rag-api="${c.apiBase}"`,
    `        data-rag-slug="${c.slug}"`,
    `        data-rag-token="${c.token}"`,
    `        data-rag-title="${c.title}"`,
    `        defer></script>`,
  ].join("\n");
}

export function curlSnippet(p: SnippetParams): string {
  const c = clean(p);
  const body =
    `{"sessionId":"visitor-123","message":"What can you help me with?",` +
    `"surface":"PUBLIC","pageRoute":"/","facts":{}}`;
  return [
    `curl -X POST "${c.apiBase}/api/ai/public/${c.slug}/ask" \\`,
    `  -H "Content-Type: application/json" \\`,
    `  -H "X-Public-Brain-Token: ${c.token}" \\`,
    `  -H "Origin: ${ORIGIN_PLACEHOLDER}" \\`,
    `  -d '${body}'`,
  ].join("\n");
}

export function jsFetchSnippet(p: SnippetParams): string {
  const c = clean(p);
  return [
    `const res = await fetch("${c.apiBase}/api/ai/public/${c.slug}/ask", {`,
    `  method: "POST",`,
    `  headers: {`,
    `    "Content-Type": "application/json",`,
    `    "X-Public-Brain-Token": "${c.token}",`,
    `  },`,
    `  body: JSON.stringify({`,
    `    sessionId: "visitor-123",`,
    `    message: "What can you help me with?",`,
    `    surface: "PUBLIC",`,
    `    pageRoute: "/",`,
    `    facts: {},`,
    `  }),`,
    `});`,
    `const data = await res.json();`,
    `// Always render data.disclaimer; show a human-handoff CTA when`,
    `// data.humanEscalationRequired is true; render data.citations if present.`,
  ].join("\n");
}

export function pythonSnippet(p: SnippetParams): string {
  const c = clean(p);
  return [
    `import requests`,
    ``,
    `res = requests.post(`,
    `    "${c.apiBase}/api/ai/public/${c.slug}/ask",`,
    `    headers={"X-Public-Brain-Token": "${c.token}"},`,
    `    json={`,
    `        "sessionId": "visitor-123",`,
    `        "message": "What can you help me with?",`,
    `        "surface": "PUBLIC",`,
    `        "pageRoute": "/",`,
    `        "facts": {},`,
    `    },`,
    `    timeout=60,`,
    `)`,
    `data = res.json()`,
    `# Always show data["disclaimer"]; honor data["humanEscalationRequired"].`,
  ].join("\n");
}
