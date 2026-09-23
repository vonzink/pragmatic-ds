package com.pragmaticds.rag.service.connect;

import com.pragmaticds.rag.domain.BrainConnectorClient;
import com.pragmaticds.rag.domain.BrainConnectorEvent;
import com.pragmaticds.rag.repository.BrainConnectorClientRepository;
import com.pragmaticds.rag.repository.BrainConnectorEventRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class ConnectorAuthService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final BrainConnectorClientRepository clients;
    private final BrainConnectorEventRepository events;
    /** Records audit events in their OWN transaction so a rejection's event survives
     *  the rollback of the (RuntimeException-throwing) request transaction. */
    private final TransactionTemplate eventTx;

    public ConnectorAuthService(BrainConnectorClientRepository clients,
                                BrainConnectorEventRepository events,
                                PlatformTransactionManager transactionManager) {
        this.clients = clients;
        this.events = events;
        this.eventTx = new TransactionTemplate(transactionManager);
        this.eventTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Transactional
    public String rotateToken(UUID connectorId) {
        BrainConnectorClient client = clients.findById(connectorId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown connector: " + connectorId));
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        String token = "rb_conn_" + HexFormat.of().formatHex(bytes);
        client.setTokenHash(hashToken(token));
        clients.save(client);
        return token;
    }

    /**
     * Authenticates a connector token only — valid and enabled — with no scope,
     * brain, origin, or peer-host checks. Used by the connector-surface interceptor
     * as a fail-closed gate that runs before any brain/tool resolution, so a new
     * endpoint that forgets {@link #require} is still gated and unauthenticated
     * probes get 401 (recorded) instead of a 400 that leaks whether a brain/tool
     * exists. Fine-grained authorization still happens per-endpoint via {@link #require}.
     * Records an AUTH_FAILURE and throws 401 on failure; on success it does not
     * record an event or touch lastUsedAt — the endpoint's {@link #require} owns that.
     */
    @Transactional
    public BrainConnectorClient authenticate(String authorizationHeader, String requestHost) {
        String token = bearerToken(authorizationHeader);
        if (token == null) {
            record(null, null, "AUTH_FAILURE", null, requestHost, "401");
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Connector token is required");
        }
        BrainConnectorClient client = clients.findByTokenHash(hashToken(token)).orElse(null);
        if (client == null) {
            record(null, null, "AUTH_FAILURE", null, requestHost, "401");
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Connector token rejected");
        }
        if (!client.isEnabled()) {
            record(client, null, "AUTH_FAILURE", null, requestHost, "401");
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Connector is disabled");
        }
        return client;
    }

    @Transactional
    public BrainConnectorClient require(String authorizationHeader, String requiredScope,
                                        UUID brainId, String requestHost, String eventType) {
        return require(authorizationHeader, requiredScope, brainId, requestHost, null, eventType);
    }

    @Transactional
    public BrainConnectorClient require(String authorizationHeader, String requiredScope,
                                        UUID brainId, String requestHost, String origin, String eventType) {
        BrainConnectorClient client = checkedClient(
                authorizationHeader, requiredScope, brainId, requestHost, origin, eventType);
        client.setLastUsedAt(OffsetDateTime.now());
        clients.save(client);
        record(client, brainId, eventType, requiredScope, requestHost, "200");
        return client;
    }

    /**
     * Token, enabled, brain, scope, peer host and origin — the checks {@code require} has always
     * made, in the order it has always made them, with the same events and the same reasons.
     * Shared with {@link #requireForTenant} so the strict path can never drift weaker than this
     * one. Does not touch {@code lastUsedAt} and records no success event; the caller owns both.
     */
    private BrainConnectorClient checkedClient(String authorizationHeader, String requiredScope,
                                               UUID brainId, String requestHost, String origin,
                                               String eventType) {
        String token = bearerToken(authorizationHeader);
        if (token == null) {
            record(null, brainId, "AUTH_FAILURE", requiredScope, requestHost, "401");
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Connector token is required");
        }

        BrainConnectorClient client = clients.findByTokenHash(hashToken(token)).orElse(null);
        if (client == null) {
            record(null, brainId, "AUTH_FAILURE", requiredScope, requestHost, "401");
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Connector token rejected");
        }
        if (!client.isEnabled()) {
            record(client, brainId, "AUTH_FAILURE", requiredScope, requestHost, "401");
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Connector is disabled");
        }
        if (client.getBrainId() != null && brainId != null && !client.getBrainId().equals(brainId)) {
            record(client, brainId, eventType, requiredScope, requestHost, "403");
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Connector is not allowed for this brain");
        }
        if (requiredScope != null && !client.getScopes().contains(requiredScope)) {
            record(client, brainId, eventType, requiredScope, requestHost, "403");
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Connector scope is required: " + requiredScope);
        }
        if (!isPeerHostAllowed(client.getAllowedPeerHosts(), requestHost)) {
            record(client, brainId, eventType, requiredScope, requestHost, "403");
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Connector peer host is not allowed");
        }
        if (!isOriginAllowed(client.getAllowedOrigins(), origin)) {
            record(client, brainId, eventType, requiredScope, requestHost, "403");
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Connector origin is not allowed");
        }
        return client;
    }

    /**
     * The strict authorization path for instance runs: everything {@link #require} checks, plus a
     * mandatory tenant and a mandatory permission.
     *
     * <p>The composition is deliberate. Token, enabled, brain, scope, peer host and origin are the
     * same checks in the same order as {@code require}, so this path can never be <em>weaker</em>
     * than the surfaces that spend nothing. The tenant and permission checks come after, through
     * {@link ConnectorPrincipal}, whose strict form refuses a blank tenant outright — an
     * unattributed run would be one nobody is ever allowed to poll back.
     *
     * <p>On success it updates {@code lastUsedAt} and records exactly one event, like
     * {@code require}. On rejection it records 401/403 in the event's own transaction and throws a
     * stable reason that never echoes the token, the tenant, the host allow-list, the permission
     * list, or anything from the request body.
     *
     * @return the client, with the trimmed tenant available via {@code principal.requireTenant}
     */
    @Transactional
    public AuthorizedConnector requireForTenant(String authorizationHeader,
                                                String requiredScope,
                                                String requiredPermission,
                                                UUID brainId,
                                                String tenantId,
                                                String requestHost,
                                                String origin,
                                                String eventType) {
        BrainConnectorClient client = checkedClient(
                authorizationHeader, requiredScope, brainId, requestHost, origin, eventType);

        ConnectorPrincipal principal = ConnectorPrincipal.from(client);
        String trimmedTenant;
        try {
            trimmedTenant = principal.requireTenant(tenantId);
            principal.requirePermission(requiredPermission);
        } catch (ResponseStatusException refused) {
            record(client, brainId, eventType, requiredScope, requestHost, "403");
            throw refused;
        }

        client.setLastUsedAt(OffsetDateTime.now());
        clients.save(client);
        record(client, brainId, eventType, requiredScope, requestHost, "200");
        return new AuthorizedConnector(client, trimmedTenant);
    }

    /**
     * An authenticated connector together with the tenant it is acting for on this request.
     *
     * <p>The tenant travels with the client rather than being re-derived, because the trimmed
     * value here is the one every later comparison — context row, polling authorization — must
     * use. Re-trimming in three places is how one of them eventually diverges.
     */
    public record AuthorizedConnector(BrainConnectorClient client, String tenantId) {}

    public static String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private void record(BrainConnectorClient client, UUID brainId, String eventType,
                        String scope, String requestHost, String status) {
        UUID clientId = client == null ? null : client.getId();
        // REQUIRES_NEW: commit the event independently, so an AUTH_FAILURE recorded on
        // a rejection path is not discarded when the request transaction rolls back.
        eventTx.executeWithoutResult(tx -> events.save(new BrainConnectorEvent(
                UUID.randomUUID(), clientId, brainId,
                eventType == null ? "AUTH_FAILURE" : eventType, scope, requestHost, status)));
    }

    private static boolean isPeerHostAllowed(List<String> allowedPeerHosts, String requestHost) {
        if (allowedPeerHosts == null || allowedPeerHosts.isEmpty()) {
            return true;
        }
        String host = normalizedHost(requestHost);
        return host != null && allowedPeerHosts.stream()
                .map(ConnectorAuthService::normalizedHost)
                .anyMatch(host::equals);
    }

    private static boolean isOriginAllowed(List<String> allowedOrigins, String origin) {
        if (allowedOrigins == null || allowedOrigins.isEmpty() || origin == null || origin.isBlank()) {
            return true;
        }
        String host = normalizedHost(origin);
        return host != null && allowedOrigins.stream()
                .map(ConnectorAuthService::normalizedHost)
                .anyMatch(host::equals);
    }

    private static String normalizedHost(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.strip();
        if (trimmed.contains("://")) {
            try {
                String host = URI.create(trimmed).getHost();
                return host == null ? null : host.toLowerCase(Locale.US);
            } catch (IllegalArgumentException ex) {
                return null;
            }
        }
        int slash = trimmed.indexOf('/');
        if (slash >= 0) {
            trimmed = trimmed.substring(0, slash);
        }
        int colon = trimmed.indexOf(':');
        if (colon > 0) {
            trimmed = trimmed.substring(0, colon);
        }
        trimmed = trimmed.strip();
        return trimmed.isEmpty() ? null : trimmed.toLowerCase(Locale.US);
    }

    private static String bearerToken(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        String trimmed = header.trim();
        if (trimmed.regionMatches(true, 0, "Bearer ", 0, 7)) {
            trimmed = trimmed.substring(7).trim();
        }
        return trimmed.isBlank() ? null : trimmed;
    }
}
