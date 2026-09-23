package com.pragmaticds.rag.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Puts a request-correlation id into the logging MDC for the lifetime of each
 * request, so every log line emitted while handling a request carries the same
 * {@code requestId} — and the id is echoed in the {@code X-Request-Id} response
 * header so a client (or reverse proxy) can report it when something goes wrong.
 * An inbound {@code X-Request-Id} is honored so the id can span a proxy hop.
 * Ordered ahead of the rate-limit/admin filters so the id is present for
 * everything downstream, and always clears the MDC in a finally block.
 *
 * <p>When the admin surface is fronted by an SSO proxy (Cloudflare Access,
 * oauth2-proxy, …), the operator's identity forwarded in a configurable header
 * ({@code ragbrain.rag.admin.proxy-user-header}, default {@code X-Auth-Request-Email})
 * is also bound to MDC as {@code user}, so admin actions are attributable per
 * operator in the logs. This is <em>attribution only</em>: authorization is still
 * the admin key + the proxy being the sole ingress, so a spoofed header cannot
 * escalate privilege (the proxy must strip client-supplied copies of the header).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class RequestCorrelationFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";
    public static final String USER_MDC_KEY = "user";

    private final String proxyUserHeader;

    public RequestCorrelationFilter(
            @Value("${ragbrain.rag.admin.proxy-user-header:X-Auth-Request-Email}") String proxyUserHeader) {
        this.proxyUserHeader = proxyUserHeader;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = sanitize(request.getHeader(REQUEST_ID_HEADER));
        if (requestId == null) {
            requestId = UUID.randomUUID().toString();
        }
        MDC.put(MDC_KEY, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);

        String user = sanitizeUser(request.getHeader(proxyUserHeader));
        if (user != null) {
            MDC.put(USER_MDC_KEY, user);
        }
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
            if (user != null) {
                MDC.remove(USER_MDC_KEY);
            }
        }
    }

    /** Accept a proxy-forwarded operator identity (email/username), bounded and log-safe. */
    private static String sanitizeUser(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.length() > 254
                || !trimmed.chars().allMatch(c -> c == '@' || c == '.' || c == '_' || c == '-'
                        || c == '+' || Character.isLetterOrDigit(c))) {
            return null;
        }
        return trimmed;
    }

    /** Accept only a short, safe inbound id so a caller can't inject junk into logs/headers. */
    private static String sanitize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.length() > 64
                || !trimmed.chars().allMatch(c -> c == '-' || Character.isLetterOrDigit(c))) {
            return null;
        }
        return trimmed;
    }
}
