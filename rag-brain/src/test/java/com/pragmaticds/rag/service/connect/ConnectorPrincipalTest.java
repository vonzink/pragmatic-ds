package com.pragmaticds.rag.service.connect;

import com.pragmaticds.rag.domain.BrainConnectorClient;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two tenant checks with deliberately different semantics, tested side by side so the difference
 * stays a decision rather than becoming an accident.
 *
 * <p>{@code assertMayActForTenant} is the ask/tool surfaces' rule: a blank tenant asserts no
 * identity and passes. {@code requireTenant} is the instance-run rule: identity is mandatory,
 * because a run creates rows that polling later authorizes by tenant, and an unattributed run
 * would be one nobody is ever allowed to read back.
 */
class ConnectorPrincipalTest {

    private static ConnectorPrincipal principal(List<String> tenants, List<String> permissions) {
        BrainConnectorClient client = new BrainConnectorClient(
                UUID.randomUUID(), "DM", "SERVER", null);
        client.setAllowedTenants(tenants);
        client.setGrantedPermissions(permissions);
        return ConnectorPrincipal.from(client);
    }

    @Test
    void theLenientCheckStillAllowsABlankTenantAndTheStrictOneRefusesIt() {
        ConnectorPrincipal principal = principal(List.of("tenant-a"), List.of());

        // Existing surfaces keep their existing semantics — this is the characterization half.
        principal.assertMayActForTenant("");
        principal.assertMayActForTenant(null);

        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> principal.requireTenant("")).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> principal.requireTenant(null)).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> principal.requireTenant("   ")).getStatusCode());
    }

    @Test
    void requireTenantTrimsButNeverCaseFolds() {
        ConnectorPrincipal principal = principal(List.of("tenant-a"), List.of());

        assertEquals("tenant-a", principal.requireTenant("  tenant-a  "));
        // Opaque identifiers: Tenant-A and tenant-a are different callers until proven otherwise.
        assertThrows(ResponseStatusException.class, () -> principal.requireTenant("Tenant-A"));
    }

    @Test
    void anEmptyAllowListCanActForNoTenant() {
        ConnectorPrincipal principal = principal(List.of(), List.of());

        // The lenient check reads an empty list as "no tenant identity exists here" and lets a
        // blank through; the strict check reads it as "this token may run for nobody".
        assertThrows(ResponseStatusException.class, () -> principal.requireTenant("tenant-a"));
    }

    @Test
    void requireTenantNeverEchoesTheTenantOrTheAllowList() {
        ConnectorPrincipal principal = principal(List.of("tenant-a"), List.of());

        ResponseStatusException refused = assertThrows(ResponseStatusException.class,
                () -> principal.requireTenant("tenant-b"));

        String reason = String.valueOf(refused.getReason());
        assertFalse(reason.contains("tenant-b"), reason);
        assertFalse(reason.contains("tenant-a"), reason);
    }

    @Test
    void requirePermissionIsExactMembership() {
        ConnectorPrincipal principal = principal(List.of(),
                List.of(ConnectorPermission.INSTANCE_RUN_READ));

        principal.requirePermission(ConnectorPermission.INSTANCE_RUN_READ);

        // Read does not imply run: the read permission spends nothing, and inferring the
        // expensive one from the cheap one would invert the reason the split exists.
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> principal.requirePermission(ConnectorPermission.INSTANCE_RUN_LIVE))
                .getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> principal.requirePermission(null)).getStatusCode());
    }

    @Test
    void principalCopiesAreDefensive() {
        BrainConnectorClient client = new BrainConnectorClient(
                UUID.randomUUID(), "DM", "SERVER", null);
        client.setAllowedTenants(new java.util.ArrayList<>(List.of("tenant-a")));
        ConnectorPrincipal principal = ConnectorPrincipal.from(client);

        client.getAllowedTenants().clear();

        // The principal is the trusted context for the whole request; a live view of a mutable
        // entity list would let anything that edits the entity edit the authorization mid-flight.
        assertTrue(principal.allowedTenants().contains("tenant-a"));
        assertEquals("tenant-a", principal.requireTenant("tenant-a"));
    }
}
