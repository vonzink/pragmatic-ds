package com.pragmaticds.rag.service.connect;

import com.pragmaticds.rag.domain.BrainConnectorClient;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * The trusted authorization context derived from an authenticated connector token.
 * The dashboard tool gateway and the dashboard /ask path authorize against this —
 * never against identity or permissions supplied in the request body, which a token
 * holder could forge.
 */
public record ConnectorPrincipal(List<String> allowedTenants, List<String> grantedPermissions) {

    public ConnectorPrincipal {
        allowedTenants = allowedTenants == null ? List.of() : List.copyOf(allowedTenants);
        grantedPermissions = grantedPermissions == null ? List.of() : List.copyOf(grantedPermissions);
    }

    public static ConnectorPrincipal from(BrainConnectorClient client) {
        if (client == null) {
            return new ConnectorPrincipal(List.of(), List.of());
        }
        return new ConnectorPrincipal(client.getAllowedTenants(), client.getGrantedPermissions());
    }

    /**
     * Enforces that this connector may act for the asserted tenant. A blank tenantId
     * asserts no identity and is allowed; any non-blank tenantId must be in the
     * connector's allow-list (an empty allow-list can act for no tenant), so a leaked
     * or over-scoped token cannot claim another tenant. Throws 403 otherwise. Applied
     * identically on the tool-call and /ask surfaces.
     */
    public void assertMayActForTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return;
        }
        if (!allowedTenants.contains(tenantId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Connector is not allowed to act for tenant: " + tenantId);
        }
    }

    /**
     * The strict form the instance-run surface uses: a tenant is <em>required</em>, not optional.
     *
     * <p>{@link #assertMayActForTenant} lets a blank tenant through because on the ask/tool
     * surfaces a blank asserts no identity and reads public material. An instance run spends money
     * and creates rows that polling later authorizes by tenant, so "no identity" is not an option
     * — an unattributed run would be one nobody is ever allowed to read back.
     *
     * <p>Trimmed but never case-folded: tenant ids are opaque caller identifiers, and treating
     * {@code Tenant-A} as {@code tenant-a} would merge identities the caller considers distinct.
     * The 403 deliberately does not echo the tenant or the allow-list.
     */
    public String requireTenant(String tenantId) {
        String trimmed = tenantId == null ? "" : tenantId.strip();
        if (trimmed.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Connector tenant is required");
        }
        if (!allowedTenants.contains(trimmed)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Connector tenant is not allowed");
        }
        return trimmed;
    }

    /** Exact membership in {@code granted_permissions}. The 403 names the requirement, not the list. */
    public void requirePermission(String permission) {
        if (permission == null || !grantedPermissions.contains(permission)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Connector permission is required: " + permission);
        }
    }
}
