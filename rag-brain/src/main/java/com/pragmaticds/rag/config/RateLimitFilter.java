package com.pragmaticds.rag.config;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Per-client token-bucket rate limiting on the public ask endpoint.
 *
 * <p>Client identity comes from {@code getRemoteAddr()} by default. The
 * {@code X-Forwarded-For} header is only honoured when
 * {@code ragbrain.rag.rate-limit.trust-forwarded-for=true}, because a direct caller
 * can set that header to any value and trivially defeat IP-based limiting by
 * rotating it. When enabled, the real client is read from the entry appended by
 * the trusted proxy layer — {@code trusted-proxy-count} entries from the right —
 * not the client-controlled leftmost value.
 *
 * <p>In-memory buckets are fine for a single instance; move to a Redis-backed
 * bucket4j store if we scale horizontally (limits would otherwise be per-pod).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final int MAX_TRACKED_CLIENTS = 100_000;
    private static final UrlPathHelper PATH_HELPER = new UrlPathHelper();
    private static final Pattern PUBLIC_ASK_PATH = Pattern.compile("^/api/ai/public/[^/]+/(ask|feedback)$");
    private static final Pattern CONNECTOR_PATH = Pattern.compile("^/api/connect/v1(?:/.*)?$");
    private static final Pattern MCP_PATH = Pattern.compile("^/mcp/tools(?:/.*)?$");
    private static final Pattern ADMIN_PATH = Pattern.compile("^/api/ai/(admin|documents)(?:/.*)?$");

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final int publicRequestsPerMinute;
    private final int connectorRequestsPerMinute;
    private final int adminRequestsPerMinute;
    private final Set<String> askPaths;
    private final boolean trustForwardedFor;
    private final int trustedProxyCount;

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    public RateLimitFilter(RagProperties properties,
                           @Value("${brain.slug:generic}") String slug,
                           @Value("${ragbrain.rag.rate-limit.trust-forwarded-for:false}") boolean trustForwardedFor,
                           @Value("${ragbrain.rag.rate-limit.trusted-proxy-count:1}") int trustedProxyCount) {
        // The fallback stays (a rate limiter that refuses to start is worse than one
        // on defaults) but it must never again be SILENT: this exact null meant
        // ragbrain.rag.rate-limit had failed to bind, so every configured limit and
        // env override was being ignored while the service looked healthy.
        // RagPropertiesBindingTest now guards the binding itself.
        RagProperties.RateLimit limits = properties.rateLimit();
        if (limits == null) {
            limits = new RagProperties.RateLimit(10, 60, 120);
            log.error("ragbrain.rag.rate-limit did not bind — falling back to {}/{}/{} per minute "
                    + "(public/connector/admin). Configured limits and env overrides are NOT in effect.",
                    limits.requestsPerMinute(), limits.connectorRequestsPerMinute(),
                    limits.adminRequestsPerMinute());
        }
        this.publicRequestsPerMinute = Math.max(1, limits.requestsPerMinute());
        this.connectorRequestsPerMinute = Math.max(1, limits.connectorRequestsPerMinute());
        this.adminRequestsPerMinute = Math.max(1, limits.adminRequestsPerMinute());
        this.askPaths = Set.of(
                "/api/ai/" + slug + "/ask",
                "/api/ai/public/" + slug + "/ask");
        this.trustForwardedFor = trustForwardedFor;
        this.trustedProxyCount = Math.max(1, trustedProxyCount);
    }

    @Override
    public boolean shouldNotFilter(HttpServletRequest request) {
        // Preflights must not consume rate budget — they carry no application
        // payload and never reach the handler; the real POST still gets limited.
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        // Decoded path — percent-encoding must not bypass rate limiting.
        String path = PATH_HELPER.getPathWithinApplication(request);
        return policy(path).isNone();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        // Basic abuse guard: drop the map if it grows unreasonably.
        if (buckets.size() > MAX_TRACKED_CLIENTS) {
            buckets.clear();
        }

        LimitPolicy policy = policy(PATH_HELPER.getPathWithinApplication(request));
        Bucket bucket = buckets.computeIfAbsent(policy.keyPrefix() + ":" + clientKey(request), key ->
                Bucket.builder()
                        .addLimit(Bandwidth.builder()
                                .capacity(policy.requestsPerMinute())
                                .refillGreedy(policy.requestsPerMinute(), Duration.ofMinutes(1))
                                .build())
                        .build());

        if (bucket.tryConsume(1)) {
            filterChain.doFilter(request, response);
        } else {
            response.setStatus(429);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(
                    "{\"error\":\"Too many requests. Please wait a moment and try again.\"}");
        }
    }

    String clientKey(HttpServletRequest request) {
        if (!trustForwardedFor) {
            return request.getRemoteAddr();
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank()) {
            return request.getRemoteAddr();
        }
        String[] parts = forwarded.split(",");
        // The client controls the leftmost entries; only the rightmost entries are
        // appended by trusted proxies. The real client is the entry the outermost
        // trusted proxy observed: count trustedProxyCount back from the end.
        int idx = parts.length - trustedProxyCount;
        if (idx < 0) {
            // Fewer hops than configured — the header can't be trusted for this
            // request, so fall back to the direct peer rather than a spoofable entry.
            return request.getRemoteAddr();
        }
        String ip = parts[idx].strip();
        return ip.isEmpty() ? request.getRemoteAddr() : ip;
    }

    private LimitPolicy policy(String path) {
        if (askPaths.contains(path) || PUBLIC_ASK_PATH.matcher(path).matches()) {
            return new LimitPolicy("public", publicRequestsPerMinute);
        }
        if (CONNECTOR_PATH.matcher(path).matches() || MCP_PATH.matcher(path).matches()) {
            return new LimitPolicy("connector", connectorRequestsPerMinute);
        }
        if (ADMIN_PATH.matcher(path).matches()) {
            return new LimitPolicy("admin", adminRequestsPerMinute);
        }
        return LimitPolicy.NONE;
    }

    private record LimitPolicy(String keyPrefix, int requestsPerMinute) {
        static final LimitPolicy NONE = new LimitPolicy("none", 0);

        boolean isNone() {
            return this == NONE;
        }
    }
}
