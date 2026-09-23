package com.pragmaticds.rag.lab.run;

import java.util.UUID;

/**
 * Who is creating a run group, carried into persistence so ownership is written in the same
 * transaction as the group itself.
 *
 * <p>Sealed on purpose: every origin either writes a connector context row or deliberately does
 * not, and an origin the switch has never heard of should fail compilation rather than silently
 * create groups nobody can authorize polling for.
 *
 * <p>This lives in {@code lab.run} rather than {@code lab.connect} because {@link RunGroupService}
 * consumes it, and the run package depending on the connector package would invert the layering —
 * execution must not know how connectors authenticate.
 */
public sealed interface RunOrigin permits RunOrigin.Admin, RunOrigin.Connector {

    /** A dashboard administrator. No context row: admin groups are authorized by the admin key. */
    record Admin(String actorId) implements RunOrigin {}

    /**
     * A server-to-server connector acting for one tenant.
     *
     * <p>{@code externalRequestSha256} digests the connector-visible request — what the caller
     * actually sent, not what it resolved to. The group's own request hash covers the resolved
     * members, release id included, so it changes when the live pointer moves; replay decisions
     * for connectors must not, which is why this second digest exists and is stored on the
     * context row.
     */
    record Connector(UUID connectorClientId, String tenantId, String externalRequestId,
                     String externalRequestSha256) implements RunOrigin {}
}
