package com.pragmaticds.docengine.platform.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pragmaticds.docengine.platform.security.AuthContext;
import com.pragmaticds.docengine.platform.security.AuthPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Writes {@code audit_event} rows. Any module may call {@link #record}; the actor is read from
 * {@link AuthContext} (never passed in — a caller cannot forge an identity) and the client IP is
 * HASHED before it touches the row.
 *
 * <p>PII contract: {@code metadata} is the caller's to keep PII-free (ids, counts, codes — never a
 * field value); this service never places raw request data anywhere. The client IP is stored as a
 * KEYED hash (HMAC-SHA256 with a server secret) so events correlate to a source without the address
 * being recoverable. A plain SHA-256 would NOT achieve that: the IPv4 space is only 2^32 addresses,
 * so an unsalted digest is reversible with a precomputed table (Phase 7b review finding). When no
 * secret is configured the hash would be reversible, so {@code ip_hash} is left NULL instead — the
 * privacy guarantee holds whenever the column is populated, and fails closed when it cannot.
 */
@Service
public class AuditService {

    private static final String HMAC_ALG = "HmacSHA256";

    private final AuditEventRepository events;
    private final ObjectMapper mapper = new ObjectMapper();
    private final byte[] ipHashKey;

    public AuditService(
            AuditEventRepository events,
            @org.springframework.beans.factory.annotation.Value("${docengine.audit.ip-hash-secret:}")
                    String ipHashSecret) {
        this.events = events;
        this.ipHashKey =
                ipHashSecret == null || ipHashSecret.isBlank()
                        ? null
                        : ipHashSecret.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Records one event for the current principal.
     *
     * @param action a stable {@code AuditEvent.ACTION_*} verb
     * @param subjectType the subject's kind, or null
     * @param subjectId the subject's id, or null
     * @param metadata PII-FREE parameters (ids, counts, codes) — never a field value or content
     */
    public void record(String action, String subjectType, UUID subjectId, Map<String, Object> metadata) {
        AuthPrincipal principal = AuthContext.require();
        HttpServletRequest request = currentRequest();
        AuditEvent event =
                new AuditEvent(
                        principal.actorType().name(),
                        principal.userId(),
                        action,
                        subjectType,
                        subjectId,
                        request == null ? null : request.getHeader("X-Request-Id"),
                        hashedIp(request),
                        toJson(metadata));
        events.save(event);
    }

    private static HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes()
                instanceof ServletRequestAttributes servletAttributes) {
            return servletAttributes.getRequest();
        }
        return null;
    }

    /**
     * A KEYED hash (HMAC-SHA256) of the remote address — never the raw address, and not reversible
     * without the server secret. Null when there is no request, no address, or no configured
     * secret (a keyless hash over the 2^32 IPv4 space would be reversible, so we store nothing
     * rather than a false privacy guarantee).
     */
    private String hashedIp(HttpServletRequest request) {
        if (request == null || ipHashKey == null) {
            return null;
        }
        return keyedIpHash(ipHashKey, request.getRemoteAddr());
    }

    /**
     * Pure, testable HMAC-SHA256 of an IP under a server key. Null for a blank ip or on the
     * (impossible) absence of HMAC-SHA256 — never an unkeyed, reversible digest.
     */
    static String keyedIpHash(byte[] key, String ip) {
        if (key == null || ip == null || ip.isBlank()) {
            return null;
        }
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance(HMAC_ALG);
            mac.init(new javax.crypto.spec.SecretKeySpec(key, HMAC_ALG));
            return HexFormat.of().formatHex(mac.doFinal(ip.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | java.security.InvalidKeyException e) {
            return null;
        }
    }

    private String toJson(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return "{}";
        }
        try {
            return mapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            // ids/codes only — never echo the map (which the caller vouches is PII-free anyway).
            throw new IllegalArgumentException("audit metadata is not serialisable");
        }
    }
}
