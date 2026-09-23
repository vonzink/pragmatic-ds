package com.pragmaticds.rag.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminApiKeyFilterTest {

    private final AdminApiKeyFilter filter = new AdminApiKeyFilter(new RagProperties(
            new RagProperties.Routing("anthropic", "openai"),
            new RagProperties.Retrieval(8, 3, 0.35, 0.65, 0.35, true, 24, true, 0.0),
            new RagProperties.Chunking(1000, 1200, 150),
            new RagProperties.Storage("./data/documents"),
            new RagProperties.Admin("k"),
            new RagProperties.Analyze(null),
            new RagProperties.RateLimit(10, 60, 120)));

    @Test
    void gatesDocumentsAndAdminSurfaces() {
        assertFalse(filter.shouldNotFilter(get("/api/ai/documents")));
        assertFalse(filter.shouldNotFilter(get("/api/ai/admin/settings")));
        assertFalse(filter.shouldNotFilter(get("/api/ai/admin/brains")));
        assertFalse(filter.shouldNotFilter(get("/api/ai/admin/brains/00000000-0000-0000-0000-000000000001/profile")));
        assertTrue(filter.shouldNotFilter(get("/api/ai/mortgage/ask")));
        assertTrue(filter.shouldNotFilter(get("/api/ai/conversations/abc")));
    }

    @Test
    void percentEncodedPathsCannotBypassTheGate() {
        // %61 = 'a' — Spring decodes and routes these to the gated controllers.
        assertFalse(filter.shouldNotFilter(get("/api/ai/%61dmin/settings")),
                "encoded admin path must still be gated");
        assertFalse(filter.shouldNotFilter(get("/api/ai/%64ocuments")),
                "encoded documents path must still be gated");
    }

    @Test
    void preflightOptionsAreNeverGated() {
        MockHttpServletRequest preflight = new MockHttpServletRequest("OPTIONS", "/api/ai/admin/settings");
        preflight.setRequestURI("/api/ai/admin/settings");
        assertTrue(filter.shouldNotFilter(preflight),
                "browsers cannot send the admin key on preflight");
    }

    private MockHttpServletRequest get(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRequestURI(uri);
        return request;
    }
}
