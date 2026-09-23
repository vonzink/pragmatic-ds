import { describe, expect, it } from "vitest";
import {
  TOKEN_PLACEHOLDER,
  curlSnippet,
  jsFetchSnippet,
  parseDomains,
  pythonSnippet,
  toHost,
  widgetSnippet,
} from "./snippets";

describe("toHost", () => {
  it("extracts host from a full origin", () => {
    expect(toHost("https://www.example.com/path?x=1")).toBe("www.example.com");
  });
  it("accepts a bare domain", () => {
    expect(toHost("Example.COM")).toBe("example.com");
  });
  it("keeps localhost ports out of the host", () => {
    expect(toHost("http://localhost:3000")).toBe("localhost");
  });
  it("returns null for junk", () => {
    expect(toHost("   ")).toBeNull();
  });
});

describe("parseDomains", () => {
  it("splits on commas/whitespace and de-dupes hosts", () => {
    expect(parseDomains("https://a.com, a.com\n b.com  b.com")).toEqual(["a.com", "b.com"]);
  });
});

describe("snippet builders", () => {
  const params = { apiBase: "https://api.example.com/", slug: "lending", token: "rb_pub_abc" };

  it("widget snippet points the script at the api host and carries data attrs", () => {
    const s = widgetSnippet(params);
    expect(s).toContain('src="https://api.example.com/widget/rag-brain-widget.js"');
    expect(s).toContain('data-rag-slug="lending"');
    expect(s).toContain('data-rag-token="rb_pub_abc"');
    expect(s).not.toContain("//widget"); // trailing slash trimmed
  });

  it("curl snippet targets the public ask path with the token header", () => {
    const s = curlSnippet(params);
    expect(s).toContain("https://api.example.com/api/ai/public/lending/ask");
    expect(s).toContain("X-Public-Brain-Token: rb_pub_abc");
  });

  it("falls back to a token placeholder when none is supplied", () => {
    const s = jsFetchSnippet({ apiBase: "https://api.example.com", slug: "lending", token: "" });
    expect(s).toContain(TOKEN_PLACEHOLDER);
  });

  it("python snippet uses requests and reminds about disclaimer", () => {
    const s = pythonSnippet(params);
    expect(s).toContain("import requests");
    expect(s).toContain("disclaimer");
  });
});
