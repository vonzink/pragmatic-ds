package com.pragmaticds.rag.controller;

import com.pragmaticds.rag.domain.BrainConnectorClient;
import com.pragmaticds.rag.domain.BrainConnectorEvent;
import com.pragmaticds.rag.dto.ConnectorClientDto;
import com.pragmaticds.rag.dto.ConnectorClientRequest;
import com.pragmaticds.rag.repository.BrainConnectorClientRepository;
import com.pragmaticds.rag.repository.BrainConnectorEventRepository;
import com.pragmaticds.rag.service.connect.ConnectorAuthService;
import com.pragmaticds.rag.service.connect.ConnectorScope;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/ai/admin/connectors")
public class AdminConnectorController {

    private final BrainConnectorClientRepository clients;
    private final BrainConnectorEventRepository events;
    private final ConnectorAuthService auth;

    public AdminConnectorController(BrainConnectorClientRepository clients,
                                    BrainConnectorEventRepository events,
                                    ConnectorAuthService auth) {
        this.clients = clients;
        this.events = events;
        this.auth = auth;
    }

    @GetMapping
    public List<ConnectorClientDto> list() {
        return clients.findAll().stream().map(ConnectorClientDto::from).toList();
    }

    @PostMapping
    public ConnectorClientDto create(@RequestBody ConnectorClientRequest req) {
        requireRequest(req);
        BrainConnectorClient client = new BrainConnectorClient(
                UUID.randomUUID(), requireText("name", req.name()),
                requireType(req.type()), null);
        apply(client, req);
        return ConnectorClientDto.from(clients.save(client));
    }

    @PutMapping("/{id}")
    public ConnectorClientDto update(@PathVariable UUID id, @RequestBody ConnectorClientRequest req) {
        requireRequest(req);
        BrainConnectorClient client = requireClient(id);
        client.setName(requireText("name", req.name()));
        client.setType(requireType(req.type()));
        apply(client, req);
        return ConnectorClientDto.from(clients.save(client));
    }

    @PostMapping("/{id}/token")
    public ConnectorTokenResponse rotateToken(@PathVariable UUID id) {
        String token = auth.rotateToken(id);
        BrainConnectorClient client = requireClient(id);
        return new ConnectorTokenResponse(token, ConnectorClientDto.from(client));
    }

    @PostMapping("/{id}/enable")
    public ConnectorClientDto enable(@PathVariable UUID id) {
        BrainConnectorClient client = requireClient(id);
        client.setEnabled(true);
        return ConnectorClientDto.from(clients.save(client));
    }

    @PostMapping("/{id}/disable")
    public ConnectorClientDto disable(@PathVariable UUID id) {
        BrainConnectorClient client = requireClient(id);
        client.setEnabled(false);
        return ConnectorClientDto.from(clients.save(client));
    }

    @GetMapping("/{id}/events")
    public List<ConnectorEventDto> events(@PathVariable UUID id) {
        return events.findTop25ByConnectorClientIdOrderByCreatedAtDesc(id).stream()
                .map(ConnectorEventDto::from)
                .toList();
    }

    private BrainConnectorClient requireClient(UUID id) {
        return clients.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown connector: " + id));
    }

    private void apply(BrainConnectorClient client, ConnectorClientRequest req) {
        client.setBrainId(req.brainId());
        client.setScopes(cleanScopes(req.scopes()));
        client.setAllowedOrigins(cleanList(req.allowedOrigins()));
        client.setAllowedPeerHosts(cleanList(req.allowedPeerHosts()));
        client.setAllowedTenants(cleanList(req.allowedTenants()));
        client.setGrantedPermissions(cleanList(req.grantedPermissions()));
        client.setEnabled(req.enabled());
    }

    private static void requireRequest(ConnectorClientRequest req) {
        if (req == null) {
            throw new IllegalArgumentException("request body is required");
        }
    }

    private static List<String> cleanScopes(List<String> scopes) {
        List<String> cleaned = cleanList(scopes);
        for (String scope : cleaned) {
            if (!ConnectorScope.MVP_SCOPES.contains(scope)) {
                throw new IllegalArgumentException("Unknown connector scope: " + scope);
            }
        }
        return cleaned;
    }

    private static List<String> cleanList(List<String> values) {
        if (values == null) return List.of();
        return values.stream()
                .filter(v -> v != null && !v.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    private static String requireText(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private static String requireType(String value) {
        String type = requireText("type", value).toUpperCase(java.util.Locale.US);
        if (!List.of("MCP_AGENT", "PEER_BRAIN", "SERVER_API", "INTERNAL_APP").contains(type)) {
            throw new IllegalArgumentException("Unknown connector type: " + value);
        }
        return type;
    }

    public record ConnectorTokenResponse(String token, ConnectorClientDto client) {}

    public record ConnectorEventDto(
            UUID id,
            UUID connectorClientId,
            UUID brainId,
            String eventType,
            String scope,
            String requestHost,
            String requestId,
            String status,
            OffsetDateTime createdAt
    ) {
        static ConnectorEventDto from(BrainConnectorEvent event) {
            return new ConnectorEventDto(event.getId(), event.getConnectorClientId(),
                    event.getBrainId(), event.getEventType(), event.getScope(),
                    event.getRequestHost(), event.getRequestId(), event.getStatus(),
                    event.getCreatedAt());
        }
    }
}
