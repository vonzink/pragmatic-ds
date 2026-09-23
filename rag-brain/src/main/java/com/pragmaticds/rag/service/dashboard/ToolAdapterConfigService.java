package com.pragmaticds.rag.service.dashboard;

import com.pragmaticds.rag.domain.BrainToolAdapterConfig;
import com.pragmaticds.rag.dto.ToolAdapterConfigDto;
import com.pragmaticds.rag.dto.ToolAdapterConfigRequest;
import com.pragmaticds.rag.repository.BrainToolAdapterConfigRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ToolAdapterConfigService {

    private static final int DEFAULT_TIMEOUT_MS = 5000;
    private static final int MIN_TIMEOUT_MS = 500;
    private static final int MAX_TIMEOUT_MS = 30000;
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");
    private static final Set<String> AUTH_MODES = Set.of("NONE", "BEARER_TOKEN", "API_KEY_HEADER");
    // Hop-by-hop headers plus credential headers. Credentials must go through
    // auth_mode + secretRef (resolved from env, redacted from responses), never
    // pasted into static_headers where they would sit in the DB/backups in plaintext.
    private static final Set<String> BLOCKED_HEADERS = Set.of(
            "host", "connection", "transfer-encoding", "content-length",
            "authorization", "proxy-authorization", "cookie", "set-cookie", "x-api-key", "api-key");

    private final BrainToolAdapterConfigRepository repo;

    public ToolAdapterConfigService(BrainToolAdapterConfigRepository repo) {
        this.repo = repo;
    }

    public ToolAdapterConfigDto get(UUID brainId, String toolName) {
        return repo.findByBrainIdAndToolName(brainId, required(toolName, "toolName"))
                .map(ToolAdapterConfigDto::from)
                .orElse(null);
    }

    public Optional<BrainToolAdapterConfig> findEnabled(UUID brainId, String toolName) {
        return repo.findByBrainIdAndToolNameAndEnabledTrue(brainId, required(toolName, "toolName"));
    }

    @Transactional
    public ToolAdapterConfigDto upsert(UUID brainId,
                                       String toolName,
                                       ToolAdapterConfigRequest req,
                                       String actor) {
        if (req == null) {
            throw new IllegalArgumentException("request body is required");
        }
        String cleanToolName = required(toolName, "toolName");
        String method = oneOf(required(req.httpMethod(), "httpMethod"), METHODS, "httpMethod");
        String urlTemplate = required(req.urlTemplate(), "urlTemplate");
        String authMode = authMode(req.authMode());
        String secretRef = optional(req.secretRef());
        String apiKeyHeader = optional(req.apiKeyHeader());
        validateAuth(authMode, secretRef, apiKeyHeader);
        if (apiKeyHeader != null) {
            rejectBlockedHeader(apiKeyHeader);
        }
        Map<String, Object> staticHeaders = copyMap(req.staticHeaders());
        for (String header : staticHeaders.keySet()) {
            rejectBlockedHeader(header);
        }
        Map<String, Object> requestBodyTemplate = copyMap(req.requestBodyTemplate());
        int timeoutMs = timeout(req.timeoutMs());
        List<String> allowedHosts = cleanHosts(req.allowedHosts());
        if (req.enabled() && allowedHosts.isEmpty()) {
            throw new IllegalArgumentException("allowedHosts is required when adapter is enabled");
        }
        if (req.enabled()) {
            staticHost(urlTemplate).ifPresent(host -> {
                if (!allowedHosts.contains(host)) {
                    throw new IllegalArgumentException("urlTemplate host is not allowlisted: " + host);
                }
            });
        }

        BrainToolAdapterConfig config = repo.findByBrainIdAndToolName(brainId, cleanToolName)
                .orElseGet(() -> new BrainToolAdapterConfig(
                        brainId,
                        cleanToolName,
                        req.enabled(),
                        method,
                        urlTemplate,
                        authMode,
                        secretRef,
                        apiKeyHeader,
                        staticHeaders,
                        requestBodyTemplate,
                        timeoutMs,
                        allowedHosts,
                        actor));

        config.setEnabled(req.enabled());
        config.setHttpMethod(method);
        config.setUrlTemplate(urlTemplate);
        config.setAuthMode(authMode);
        config.setSecretRef(secretRef);
        config.setApiKeyHeader(apiKeyHeader);
        config.setStaticHeaders(staticHeaders);
        config.setRequestBodyTemplate(requestBodyTemplate);
        config.setTimeoutMs(timeoutMs);
        config.setAllowedHosts(allowedHosts);
        config.setUpdatedBy(actor);

        return ToolAdapterConfigDto.from(repo.save(config));
    }

    @Transactional
    public void delete(UUID brainId, String toolName) {
        repo.findByBrainIdAndToolName(brainId, required(toolName, "toolName"))
                .ifPresent(repo::delete);
    }

    private static String authMode(String value) {
        if (value == null || value.isBlank()) {
            return "NONE";
        }
        return oneOf(value, AUTH_MODES, "authMode");
    }

    private static void validateAuth(String authMode, String secretRef, String apiKeyHeader) {
        if ("BEARER_TOKEN".equals(authMode) && secretRef == null) {
            throw new IllegalArgumentException("secretRef is required for BEARER_TOKEN");
        }
        if ("API_KEY_HEADER".equals(authMode)) {
            if (secretRef == null) {
                throw new IllegalArgumentException("secretRef is required for API_KEY_HEADER");
            }
            if (apiKeyHeader == null) {
                throw new IllegalArgumentException("apiKeyHeader is required for API_KEY_HEADER");
            }
        }
    }

    private static int timeout(Integer timeoutMs) {
        int value = timeoutMs == null ? DEFAULT_TIMEOUT_MS : timeoutMs;
        if (value < MIN_TIMEOUT_MS || value > MAX_TIMEOUT_MS) {
            throw new IllegalArgumentException("timeoutMs must be between 500 and 30000");
        }
        return value;
    }

    private static String oneOf(String value, Set<String> allowed, String field) {
        String normalized = required(value, field).toUpperCase(Locale.US);
        if (!allowed.contains(normalized)) {
            throw new IllegalArgumentException(field + " is invalid: " + value);
        }
        return normalized;
    }

    private static void rejectBlockedHeader(String header) {
        String cleanHeader = required(header, "header");
        if (BLOCKED_HEADERS.contains(cleanHeader.toLowerCase(Locale.US))) {
            throw new IllegalArgumentException("static header is blocked: " + cleanHeader);
        }
    }

    private static Map<String, Object> copyMap(Map<String, Object> values) {
        return values == null ? new LinkedHashMap<>() : new LinkedHashMap<>(values);
    }

    private static final Pattern AUTHORITY = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*://([^/?#]*)");

    /**
     * Best-effort static host extracted from a URL template. Empty when the host portion is
     * templated (contains a {placeholder}) or the template can't be parsed — those cases are
     * left to {@link ToolAdapterUrlValidator} at request time.
     */
    private static Optional<String> staticHost(String urlTemplate) {
        Matcher matcher = AUTHORITY.matcher(urlTemplate);
        if (!matcher.find()) {
            return Optional.empty();
        }
        String authority = matcher.group(1);
        int at = authority.lastIndexOf('@');
        if (at >= 0) {
            authority = authority.substring(at + 1);
        }
        int colon = authority.indexOf(':');
        String host = colon >= 0 ? authority.substring(0, colon) : authority;
        if (host.isBlank() || host.contains("{") || host.contains("}")) {
            return Optional.empty();
        }
        return Optional.of(host.toLowerCase(Locale.US));
    }

    private static List<String> cleanHosts(List<String> hosts) {
        if (hosts == null) {
            return new ArrayList<>();
        }
        List<String> out = new ArrayList<>();
        for (String host : hosts) {
            if (host == null || host.isBlank()) {
                continue;
            }
            String clean = host.strip().toLowerCase(Locale.US);
            if (clean.contains("://") || clean.contains("/") || clean.contains("?")) {
                throw new IllegalArgumentException("allowedHosts entries must be host names only: " + host);
            }
            if (!out.contains(clean)) {
                out.add(clean);
            }
        }
        return out;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.strip();
    }

    private static String optional(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip();
    }
}
