package com.pragmaticds.rag.service.connect;

import com.pragmaticds.rag.TestBrains;
import com.pragmaticds.rag.domain.BrainConnectorClient;
import com.pragmaticds.rag.domain.BrainConnectorEvent;
import com.pragmaticds.rag.repository.BrainConnectorClientRepository;
import com.pragmaticds.rag.repository.BrainConnectorEventRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConnectorAuthServiceTest {

    private final BrainConnectorClientRepository clients = mock(BrainConnectorClientRepository.class);
    private final BrainConnectorEventRepository events = mock(BrainConnectorEventRepository.class);
    private final ConnectorAuthService service = new ConnectorAuthService(clients, events,
            mock(org.springframework.transaction.PlatformTransactionManager.class));

    @Test
    void rotateTokenStoresHashOnly() {
        UUID id = UUID.randomUUID();
        BrainConnectorClient client = new BrainConnectorClient(id, "Agent", "MCP_AGENT", null);
        when(clients.findById(id)).thenReturn(Optional.of(client));
        when(clients.save(any())).thenAnswer(inv -> inv.getArgument(0));

        String token = service.rotateToken(id);

        assertTrue(token.startsWith("rb_conn_"));
        assertNotEquals(token, client.getTokenHash());
        assertEquals(64, client.getTokenHash().length());
        verify(clients).save(client);
    }

    @Test
    void requireRejectsMissingTokenAsUnauthorized() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.require(null, ConnectorScope.ASK_PUBLIC, TestBrains.DEFAULT_ID, "peer.local", "ASK"));

        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatusCode());
    }

    @Test
    void requireRejectsInvalidTokenAsUnauthorizedAndRecordsFailure() {
        when(clients.findByTokenHash(anyString())).thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.require("Bearer rb_conn_bad", ConnectorScope.ASK_PUBLIC,
                        TestBrains.DEFAULT_ID, "peer.local", "ASK"));

        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatusCode());
        ArgumentCaptor<BrainConnectorEvent> event = ArgumentCaptor.forClass(BrainConnectorEvent.class);
        verify(events).save(event.capture());
        assertEquals("AUTH_FAILURE", event.getValue().getEventType());
        assertEquals("401", event.getValue().getStatus());
    }

    @Test
    void authenticateRejectsMissingTokenAsUnauthorizedAndRecordsFailure() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.authenticate(null, "peer.local"));

        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatusCode());
        ArgumentCaptor<BrainConnectorEvent> event = ArgumentCaptor.forClass(BrainConnectorEvent.class);
        verify(events).save(event.capture());
        assertEquals("AUTH_FAILURE", event.getValue().getEventType());
        assertEquals("401", event.getValue().getStatus());
    }

    @Test
    void authenticateRejectsInvalidTokenAsUnauthorized() {
        when(clients.findByTokenHash(anyString())).thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.authenticate("Bearer rb_conn_bad", "peer.local"));

        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatusCode());
    }

    @Test
    void authenticateRejectsDisabledClientAsUnauthorized() {
        String token = "rb_conn_known";
        BrainConnectorClient client = client(token, false, List.of(ConnectorScope.ASK_PUBLIC));
        when(clients.findByTokenHash(ConnectorAuthService.hashToken(token))).thenReturn(Optional.of(client));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.authenticate("Bearer " + token, "peer.local"));

        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatusCode());
    }

    @Test
    void authenticateAcceptsValidTokenWithoutRecordingSuccessOrTouchingLastUsed() {
        String token = "rb_conn_known";
        BrainConnectorClient client = client(token, true, List.of(ConnectorScope.BRAINS_LIST));
        when(clients.findByTokenHash(ConnectorAuthService.hashToken(token))).thenReturn(Optional.of(client));

        BrainConnectorClient authenticated = service.authenticate("Bearer " + token, "peer.local");

        assertEquals(client, authenticated);
        // Authentication is a gate only — the endpoint's require() records the scoped
        // event and updates lastUsedAt, so authenticate() must not.
        assertEquals(null, client.getLastUsedAt());
        verify(events, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void requireRejectsMissingScopeAsForbidden() {
        String token = "rb_conn_known";
        BrainConnectorClient client = client(token, true, List.of(ConnectorScope.BRAINS_LIST));
        when(clients.findByTokenHash(ConnectorAuthService.hashToken(token))).thenReturn(Optional.of(client));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.require("Bearer " + token, ConnectorScope.ASK_PUBLIC,
                        TestBrains.DEFAULT_ID, "peer.local", "ASK"));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    void requireRejectsDisabledClientAsUnauthorized() {
        String token = "rb_conn_known";
        BrainConnectorClient client = client(token, false, List.of(ConnectorScope.ASK_PUBLIC));
        when(clients.findByTokenHash(ConnectorAuthService.hashToken(token))).thenReturn(Optional.of(client));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.require("Bearer " + token, ConnectorScope.ASK_PUBLIC,
                        TestBrains.DEFAULT_ID, "peer.local", "ASK"));

        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatusCode());
    }

    @Test
    void requireAcceptsBearerTokenUpdatesLastUsedAndRecordsEvent() {
        String token = "rb_conn_known";
        BrainConnectorClient client = client(token, true, List.of(ConnectorScope.ASK_PUBLIC));
        when(clients.findByTokenHash(ConnectorAuthService.hashToken(token))).thenReturn(Optional.of(client));
        when(clients.save(any())).thenAnswer(inv -> inv.getArgument(0));

        BrainConnectorClient validated = service.require("Bearer " + token, ConnectorScope.ASK_PUBLIC,
                TestBrains.DEFAULT_ID, "peer.local", "ASK");

        assertEquals(client, validated);
        assertNotNull(client.getLastUsedAt());
        verify(clients).save(client);
        ArgumentCaptor<BrainConnectorEvent> event = ArgumentCaptor.forClass(BrainConnectorEvent.class);
        verify(events).save(event.capture());
        assertEquals("ASK", event.getValue().getEventType());
        assertEquals("200", event.getValue().getStatus());
    }

    @Test
    void requireRejectsDisallowedPeerHostWhenPeerAllowlistConfigured() {
        String token = "rb_conn_known";
        BrainConnectorClient client = client(token, true, List.of(ConnectorScope.ASK_PUBLIC));
        client.setAllowedPeerHosts(List.of("trusted.peer.local"));
        when(clients.findByTokenHash(ConnectorAuthService.hashToken(token))).thenReturn(Optional.of(client));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.require("Bearer " + token, ConnectorScope.ASK_PUBLIC,
                        TestBrains.DEFAULT_ID, "evil.peer.local", "ASK"));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    void requireRejectsDisallowedBrowserOriginWhenOriginAllowlistConfigured() {
        String token = "rb_conn_known";
        BrainConnectorClient client = client(token, true, List.of(ConnectorScope.ASK_PUBLIC));
        client.setAllowedOrigins(List.of("https://trusted.example.com"));
        when(clients.findByTokenHash(ConnectorAuthService.hashToken(token))).thenReturn(Optional.of(client));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.require("Bearer " + token, ConnectorScope.ASK_PUBLIC,
                        TestBrains.DEFAULT_ID, "api.example.com", "https://evil.example.com", "ASK"));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    // ============================================================ requireForTenant
    //
    // The strict path composes everything require() checks and then demands a tenant and a
    // permission. The composition tests matter most: a token that fails require() for any reason
    // must fail requireForTenant() identically, because a strict path that skipped a shared check
    // would be the weaker one on exactly the surface that spends money.

    @Test
    void requireForTenantAcceptsAndReturnsTheTrimmedTenant() {
        BrainConnectorClient granted = client("rb_conn_run", true,
                List.of(ConnectorScope.INSTANCE_RUN));
        granted.setAllowedTenants(List.of("tenant-a"));
        granted.setGrantedPermissions(List.of(ConnectorPermission.INSTANCE_RUN_LIVE));
        when(clients.findByTokenHash(ConnectorAuthService.hashToken("rb_conn_run")))
                .thenReturn(Optional.of(granted));

        ConnectorAuthService.AuthorizedConnector authorized = service.requireForTenant(
                "Bearer rb_conn_run", ConnectorScope.INSTANCE_RUN,
                ConnectorPermission.INSTANCE_RUN_LIVE, TestBrains.DEFAULT_ID,
                "  tenant-a  ", "peer.local", null, "INSTANCE_RUN");

        // The trimmed value is what every later comparison uses — the context row and the
        // polling authorization — so it travels with the client rather than being re-derived.
        assertEquals("tenant-a", authorized.tenantId());
        assertNotNull(granted.getLastUsedAt());
        ArgumentCaptor<BrainConnectorEvent> event = ArgumentCaptor.forClass(BrainConnectorEvent.class);
        verify(events).save(event.capture());
        assertEquals("200", event.getValue().getStatus());
    }

    @Test
    void requireForTenantRefusesABlankTenantOutright() {
        BrainConnectorClient granted = client("rb_conn_run", true,
                List.of(ConnectorScope.INSTANCE_RUN));
        granted.setAllowedTenants(List.of("tenant-a"));
        granted.setGrantedPermissions(List.of(ConnectorPermission.INSTANCE_RUN_LIVE));
        when(clients.findByTokenHash(anyString())).thenReturn(Optional.of(granted));

        // On the ask surface a blank tenant asserts no identity and reads public material. A run
        // spends money and is polled back by tenant, so an unattributed run is not an option.
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.requireForTenant("Bearer rb_conn_run", ConnectorScope.INSTANCE_RUN,
                        ConnectorPermission.INSTANCE_RUN_LIVE, TestBrains.DEFAULT_ID,
                        "   ", "peer.local", null, "INSTANCE_RUN"));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        assertNull(granted.getLastUsedAt());
    }

    @Test
    void requireForTenantRefusesATenantOutsideTheAllowListWithoutEchoingEither() {
        BrainConnectorClient granted = client("rb_conn_run", true,
                List.of(ConnectorScope.INSTANCE_RUN));
        granted.setAllowedTenants(List.of("tenant-a"));
        granted.setGrantedPermissions(List.of(ConnectorPermission.INSTANCE_RUN_LIVE));
        when(clients.findByTokenHash(anyString())).thenReturn(Optional.of(granted));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.requireForTenant("Bearer rb_conn_run", ConnectorScope.INSTANCE_RUN,
                        ConnectorPermission.INSTANCE_RUN_LIVE, TestBrains.DEFAULT_ID,
                        "tenant-b", "peer.local", null, "INSTANCE_RUN"));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        // The reason is stable and names nothing: not the asserted tenant, not the allow-list.
        String reason = String.valueOf(ex.getReason());
        assertTrue(!reason.contains("tenant-a") && !reason.contains("tenant-b"), reason);

        ArgumentCaptor<BrainConnectorEvent> event = ArgumentCaptor.forClass(BrainConnectorEvent.class);
        verify(events).save(event.capture());
        assertEquals("403", event.getValue().getStatus());
    }

    @Test
    void requireForTenantDoesNotCaseFoldTenants() {
        BrainConnectorClient granted = client("rb_conn_run", true,
                List.of(ConnectorScope.INSTANCE_RUN));
        granted.setAllowedTenants(List.of("tenant-a"));
        granted.setGrantedPermissions(List.of(ConnectorPermission.INSTANCE_RUN_LIVE));
        when(clients.findByTokenHash(anyString())).thenReturn(Optional.of(granted));

        // Tenant ids are opaque identifiers; folding Tenant-A onto tenant-a would merge
        // identities the caller considers distinct.
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.requireForTenant("Bearer rb_conn_run", ConnectorScope.INSTANCE_RUN,
                        ConnectorPermission.INSTANCE_RUN_LIVE, TestBrains.DEFAULT_ID,
                        "Tenant-A", "peer.local", null, "INSTANCE_RUN"));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    void requireForTenantRefusesAMissingPermissionEvenWithTheScope() {
        BrainConnectorClient scopedOnly = client("rb_conn_run", true,
                List.of(ConnectorScope.INSTANCE_RUN));
        scopedOnly.setAllowedTenants(List.of("tenant-a"));
        scopedOnly.setGrantedPermissions(List.of());
        when(clients.findByTokenHash(anyString())).thenReturn(Optional.of(scopedOnly));

        // The scope opens the surface; the permission authorizes the spend. Granting one is a
        // separate, visible act from granting the other, and the check honours the split.
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.requireForTenant("Bearer rb_conn_run", ConnectorScope.INSTANCE_RUN,
                        ConnectorPermission.INSTANCE_RUN_LIVE, TestBrains.DEFAULT_ID,
                        "tenant-a", "peer.local", null, "INSTANCE_RUN"));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        assertNull(scopedOnly.getLastUsedAt());
    }

    @Test
    void requireForTenantIsNeverWeakerThanRequire() {
        // Same rejections as require(), through the same shared core: a missing token, a wrong
        // brain, and a missing scope all fail before any tenant or permission is considered.
        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ResponseStatusException.class,
                () -> service.requireForTenant(null, ConnectorScope.INSTANCE_RUN,
                        ConnectorPermission.INSTANCE_RUN_LIVE, TestBrains.DEFAULT_ID,
                        "tenant-a", "peer.local", null, "INSTANCE_RUN")).getStatusCode());

        BrainConnectorClient otherBrain = client("rb_conn_run", true,
                List.of(ConnectorScope.INSTANCE_RUN));
        otherBrain.setAllowedTenants(List.of("tenant-a"));
        otherBrain.setGrantedPermissions(List.of(ConnectorPermission.INSTANCE_RUN_LIVE));
        when(clients.findByTokenHash(anyString())).thenReturn(Optional.of(otherBrain));
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> service.requireForTenant("Bearer rb_conn_run", ConnectorScope.INSTANCE_RUN,
                        ConnectorPermission.INSTANCE_RUN_LIVE, UUID.randomUUID(),
                        "tenant-a", "peer.local", null, "INSTANCE_RUN")).getStatusCode());

        BrainConnectorClient unscoped = client("rb_conn_run", true, List.of());
        unscoped.setAllowedTenants(List.of("tenant-a"));
        unscoped.setGrantedPermissions(List.of(ConnectorPermission.INSTANCE_RUN_LIVE));
        when(clients.findByTokenHash(anyString())).thenReturn(Optional.of(unscoped));
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> service.requireForTenant("Bearer rb_conn_run", ConnectorScope.INSTANCE_RUN,
                        ConnectorPermission.INSTANCE_RUN_LIVE, TestBrains.DEFAULT_ID,
                        "tenant-a", "peer.local", null, "INSTANCE_RUN")).getStatusCode());
    }

    private static BrainConnectorClient client(String token, boolean enabled, List<String> scopes) {
        BrainConnectorClient client = new BrainConnectorClient(
                UUID.randomUUID(), "Agent", "MCP_AGENT", ConnectorAuthService.hashToken(token));
        client.setBrainId(TestBrains.DEFAULT_ID);
        client.setScopes(scopes);
        client.setEnabled(enabled);
        return client;
    }
}
