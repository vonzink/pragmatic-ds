package com.pragmaticds.rag.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimitFilterTest {

    private final RagProperties props = new RagProperties(
            new RagProperties.Routing("anthropic", "openai"),
            new RagProperties.Retrieval(8, 3, 0.35, 0.65, 0.35, true, 24, true, 0.0),
            new RagProperties.Chunking(1000, 1200, 150),
            new RagProperties.Storage("./data/documents"),
            new RagProperties.Admin("k"),
            new RagProperties.Analyze(null),
            new RagProperties.RateLimit(10, 60, 120));

    @Test
    void limitsTheConfiguredAskPathAndAnyPublicAskSlug() {
        RateLimitFilter filter = new RateLimitFilter(props, "mortgage", false, 1);

        assertFalse(filter.shouldNotFilter(get("/api/ai/mortgage/ask")),
                "the ask path must be rate limited");
        assertFalse(filter.shouldNotFilter(get("/api/ai/public/generic/ask")),
                "generic public ask must be rate limited");
        assertFalse(filter.shouldNotFilter(get("/api/ai/public/other-brain/ask")),
                "other-brain public ask must be rate limited");
        assertFalse(filter.shouldNotFilter(get("/api/ai/documents")),
                "admin document endpoints should be rate limited");
    }

    @Test
    void limitsThePublicFeedbackPathForAnySlug() {
        RateLimitFilter filter = new RateLimitFilter(props, "mortgage", false, 1);

        assertFalse(filter.shouldNotFilter(get("/api/ai/public/generic/feedback")),
                "generic public feedback must be rate limited");
        assertFalse(filter.shouldNotFilter(get("/api/ai/public/other-brain/feedback")),
                "other-brain public feedback must be rate limited");
    }

    @Test
    void publicFeedbackPathIsThrottledLikeAsk() throws Exception {
        // Same bucket size as the public ask path (10 requests/minute per props);
        // the 11th request within the window must be rejected with 429.
        RateLimitFilter filter = new RateLimitFilter(props, "mortgage", false, 1);
        MockHttpServletRequest request = get("/api/ai/public/generic/feedback");
        request.setRemoteAddr("203.0.113.9");

        for (int i = 0; i < 10; i++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, new MockFilterChain());
            assertEquals(200, response.getStatus(),
                    "request " + (i + 1) + " should be within the per-minute budget");
        }

        MockHttpServletResponse throttled = new MockHttpServletResponse();
        filter.doFilter(request, throttled, new MockFilterChain());
        assertEquals(429, throttled.getStatus(),
                "the 11th public feedback request within a minute must be rate limited");
    }

    @Test
    void limitsConnectorAndMcpSurfaces() {
        RateLimitFilter filter = new RateLimitFilter(props, "mortgage", false, 1);

        assertFalse(filter.shouldNotFilter(get("/api/connect/v1/brains/generic/ask")),
                "federation ask must be rate limited");
        assertFalse(filter.shouldNotFilter(get("/api/connect/v1/brains/generic/retrieve")),
                "federation retrieve must be rate limited");
        assertFalse(filter.shouldNotFilter(get("/mcp/tools/rag_brain_ask")),
                "MCP tool calls must be rate limited");
        // The Document Manager instance-run routes ride the same /api/connect/v1 prefix, so they
        // must be covered by the same limiter with no new pattern and no new bypass.
        assertFalse(filter.shouldNotFilter(
                        get("/api/connect/v1/brains/mortgage/instances/income/run-groups")),
                "connector run launches must be rate limited");
        assertFalse(filter.shouldNotFilter(
                        get("/api/connect/v1/brains/mortgage/instance-run-groups/"
                                + "88888888-8888-4888-8888-888888888888")),
                "connector run polling must be rate limited");
        assertFalse(filter.shouldNotFilter(get("/api/ai/admin/connectors")),
                "admin connector management must be rate limited");
    }

    @Test
    void usesSafeDefaultsWhenRateLimitPropertiesAreMissing() {
        RagProperties missingRateLimit = new RagProperties(
                new RagProperties.Routing("anthropic", "openai"),
                new RagProperties.Retrieval(8, 3, 0.35, 0.65, 0.35, true, 24, true, 0.0),
                new RagProperties.Chunking(1000, 1200, 150),
                new RagProperties.Storage("./data/documents"),
                new RagProperties.Admin("k"),
                new RagProperties.Analyze(null),
                null);

        RateLimitFilter filter = new RateLimitFilter(missingRateLimit, "mortgage", false, 1);

        assertFalse(filter.shouldNotFilter(get("/api/connect/v1/brains/generic/ask")));
    }

    @Test
    void percentEncodedAskPathIsStillRateLimited() {
        RateLimitFilter filter = new RateLimitFilter(props, "mortgage", false, 1);
        assertFalse(filter.shouldNotFilter(get("/api/ai/mortgage/%61sk")),
                "encoded ask path must not bypass rate limiting");
    }

    @Test
    void followsTheConfiguredSlug() {
        RateLimitFilter filter = new RateLimitFilter(props, "roofing", false, 1);

        assertFalse(filter.shouldNotFilter(get("/api/ai/roofing/ask")));
        assertTrue(filter.shouldNotFilter(get("/api/ai/mortgage/ask")),
                "the old path is not limited when the slug changes");
    }

    @Test
    void preflightOptionsAreNotRateLimited() {
        RateLimitFilter filter = new RateLimitFilter(props, "mortgage", false, 1);
        MockHttpServletRequest preflight = new MockHttpServletRequest("OPTIONS", "/api/ai/mortgage/ask");
        preflight.setRequestURI("/api/ai/mortgage/ask");
        assertTrue(filter.shouldNotFilter(preflight),
                "preflights must not consume rate budget");
    }

    @Test
    void ignoresSpoofableForwardedForByDefault() {
        RateLimitFilter filter = new RateLimitFilter(props, "mortgage", false, 1);
        MockHttpServletRequest request = get("/api/ai/public/generic/ask");
        request.setRemoteAddr("203.0.113.7");
        request.addHeader("X-Forwarded-For", "1.2.3.4");

        assertEquals("203.0.113.7", filter.clientKey(request),
                "the spoofable forwarded header must be ignored unless explicitly trusted");
    }

    @Test
    void usesProxyAppendedClientIpWhenForwardedForIsTrusted() {
        RateLimitFilter filter = new RateLimitFilter(props, "mortgage", true, 1);
        MockHttpServletRequest request = get("/api/ai/public/generic/ask");
        request.setRemoteAddr("10.0.0.1");
        // A malicious client prepends a fake entry; the trusted proxy appends the
        // real peer IP on the right. With one trusted proxy that real IP wins.
        request.addHeader("X-Forwarded-For", "1.2.3.4, 198.51.100.23");

        assertEquals("198.51.100.23", filter.clientKey(request),
                "the trusted-proxy entry must be used, not the client-controlled leftmost one");
    }

    @Test
    void fallsBackToRemoteAddrWhenForwardedForHasTooFewHops() {
        RateLimitFilter filter = new RateLimitFilter(props, "mortgage", true, 2);
        MockHttpServletRequest request = get("/api/ai/public/generic/ask");
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("X-Forwarded-For", "1.2.3.4");

        assertEquals("10.0.0.1", filter.clientKey(request),
                "with fewer hops than trusted proxies the header cannot be trusted");
    }

    private MockHttpServletRequest get(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setRequestURI(uri);
        return request;
    }
}
