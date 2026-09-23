package com.pragmaticds.rag.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Gates the internal folder-brains analyze AND chat endpoints with a dedicated
 * static key (X-Analyze-Api-Key). Separate from the admin key so the suite's
 * server-to-server caller holds ONLY this scope. Fail-closed: if the key is
 * unset the whole analyze/chat surface is DISABLED (503 BRAIN_DISABLED) — you
 * cannot accidentally run an unauthenticated analyze or chat in a
 * mis-provisioned environment. Interim measure for local/MVP — replace with
 * Cognito JWT auth at deployment.
 */
@Component
public class AnalyzeApiKeyFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-Analyze-Api-Key";
    private static final UrlPathHelper PATH_HELPER = new UrlPathHelper();

    private final String analyzeApiKey;

    public AnalyzeApiKeyFilter(RagProperties properties) {
        this.analyzeApiKey = properties.analyze() == null ? null : properties.analyze().apiKey();
    }

    @Override
    public boolean shouldNotFilter(HttpServletRequest request) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        // Gate ONLY the analyze, chat, and extract sub-paths: /api/ai/{brain}/analyze/{slug},
        // /api/ai/{brain}/chat, and /api/ai/{brain}/extract/{extractorSlug}. Match the
        // decoded within-application path so a percent-encoded letter cannot skip the gate.
        String path = PATH_HELPER.getPathWithinApplication(request);
        return !path.matches("/api/ai/[^/]+/(analyze|chat|extract)(/.*)?");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        // Fail-closed: no configured key ⇒ the analyze surface is disabled.
        if (analyzeApiKey == null || analyzeApiKey.isBlank()) {
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(
                    "{\"error\":\"BRAIN_DISABLED\",\"message\":\"Folder-brains analyze is not enabled on this engine\"}");
            return;
        }

        String provided = request.getHeader(HEADER);
        if (provided == null || !constantTimeEquals(provided, analyzeApiKey)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"error\":\"Missing or invalid analyze API key\"}");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
