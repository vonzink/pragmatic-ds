package com.pragmaticds.rag.config;

import com.pragmaticds.rag.config.RagProperties.Analyze;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AnalyzeApiKeyFilterTest {

    private RagProperties propsWithAnalyzeKey(String key) {
        RagProperties p = mock(RagProperties.class);
        when(p.analyze()).thenReturn(new Analyze(key));
        return p;
    }

    private MockHttpServletRequest analyzeRequest(String header) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/ai/mortgage/analyze/income");
        req.setServletPath("/api/ai/mortgage/analyze/income");
        if (header != null) {
            req.addHeader("X-Analyze-Api-Key", header);
        }
        return req;
    }

    private MockHttpServletRequest chatRequest(String header) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/ai/mortgage/chat");
        req.setServletPath("/api/ai/mortgage/chat");
        if (header != null) {
            req.addHeader("X-Analyze-Api-Key", header);
        }
        return req;
    }

    private MockHttpServletRequest extractRequest(String header) {
        MockHttpServletRequest req =
                new MockHttpServletRequest("POST", "/api/ai/mortgage/extract/income-schedule-c");
        req.setServletPath("/api/ai/mortgage/extract/income-schedule-c");
        if (header != null) {
            req.addHeader("X-Analyze-Api-Key", header);
        }
        return req;
    }

    @Test
    void unsetKeyReturns503BrainDisabled() throws Exception {
        AnalyzeApiKeyFilter filter = new AnalyzeApiKeyFilter(propsWithAnalyzeKey(""));
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(analyzeRequest("anything"), res, chain);

        assertEquals(503, res.getStatus());
        assertTrue(res.getContentAsString().contains("BRAIN_DISABLED"));
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void wrongKeyReturns401() throws Exception {
        AnalyzeApiKeyFilter filter = new AnalyzeApiKeyFilter(propsWithAnalyzeKey("secret"));
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(analyzeRequest("wrong"), res, chain);

        assertEquals(401, res.getStatus());
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void correctKeyPassesThrough() throws Exception {
        AnalyzeApiKeyFilter filter = new AnalyzeApiKeyFilter(propsWithAnalyzeKey("secret"));
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(analyzeRequest("secret"), res, chain);

        verify(chain).doFilter(any(), any());
    }

    @Test
    void nonAnalyzePathIsNotGated() throws Exception {
        AnalyzeApiKeyFilter filter = new AnalyzeApiKeyFilter(propsWithAnalyzeKey(""));
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/ai/mortgage/ask");
        req.setServletPath("/api/ai/mortgage/ask");

        filter.doFilter(req, res, chain);

        verify(chain).doFilter(any(), any());  // ask endpoint is not analyze-gated
    }

    // ---- /chat matrix (widened regex; same posture as /analyze) ------------

    @Test
    void chatPathUnsetKeyReturns503BrainDisabled() throws Exception {
        AnalyzeApiKeyFilter filter = new AnalyzeApiKeyFilter(propsWithAnalyzeKey(""));
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(chatRequest("anything"), res, chain);

        assertEquals(503, res.getStatus());
        assertTrue(res.getContentAsString().contains("BRAIN_DISABLED"));
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void chatPathNoHeaderReturns401() throws Exception {
        AnalyzeApiKeyFilter filter = new AnalyzeApiKeyFilter(propsWithAnalyzeKey("secret"));
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(chatRequest(null), res, chain);

        assertEquals(401, res.getStatus());
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void chatPathWrongKeyReturns401() throws Exception {
        AnalyzeApiKeyFilter filter = new AnalyzeApiKeyFilter(propsWithAnalyzeKey("secret"));
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(chatRequest("wrong"), res, chain);

        assertEquals(401, res.getStatus());
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void chatPathCorrectKeyPassesThrough() throws Exception {
        AnalyzeApiKeyFilter filter = new AnalyzeApiKeyFilter(propsWithAnalyzeKey("secret"));
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(chatRequest("secret"), res, chain);

        verify(chain).doFilter(any(), any());
    }

    @Test
    void chatSubPathIsAlsoGated() throws Exception {
        AnalyzeApiKeyFilter filter = new AnalyzeApiKeyFilter(propsWithAnalyzeKey("secret"));
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/ai/mortgage/chat/extra");
        req.setServletPath("/api/ai/mortgage/chat/extra");

        filter.doFilter(req, res, chain);

        assertEquals(401, res.getStatus());
        verify(chain, never()).doFilter(any(), any());
    }

    // ---- /extract matrix (widened regex; same posture as /analyze and /chat) ----

    @Test
    void extractPathUnsetKeyReturns503BrainDisabled() throws Exception {
        AnalyzeApiKeyFilter filter = new AnalyzeApiKeyFilter(propsWithAnalyzeKey(""));
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(extractRequest("anything"), res, chain);

        assertEquals(503, res.getStatus());
        assertTrue(res.getContentAsString().contains("BRAIN_DISABLED"));
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void extractPathNoHeaderReturns401() throws Exception {
        AnalyzeApiKeyFilter filter = new AnalyzeApiKeyFilter(propsWithAnalyzeKey("secret"));
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(extractRequest(null), res, chain);

        assertEquals(401, res.getStatus());
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void extractPathWrongKeyReturns401() throws Exception {
        AnalyzeApiKeyFilter filter = new AnalyzeApiKeyFilter(propsWithAnalyzeKey("secret"));
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(extractRequest("wrong"), res, chain);

        assertEquals(401, res.getStatus());
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void extractPathCorrectKeyPassesThrough() throws Exception {
        AnalyzeApiKeyFilter filter = new AnalyzeApiKeyFilter(propsWithAnalyzeKey("secret"));
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(extractRequest("secret"), res, chain);

        verify(chain).doFilter(any(), any());
    }
}
