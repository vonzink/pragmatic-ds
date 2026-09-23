package com.pragmaticds.rag.service.connect;

/**
 * Fine-grained permissions checked against a connector token's {@code granted_permissions}.
 *
 * <p>Scopes and permissions answer different questions and both are required for an instance run.
 * A scope says which surface a token may call at all; a permission says what the organization
 * behind the token authorized it to do there. The split exists so that granting a Document Manager
 * deployment the run surface is a separate, visible act from authorizing it to spend money against
 * live releases — one administrator can do the first without silently doing the second.
 */
public final class ConnectorPermission {
    private ConnectorPermission() {}

    /** May launch a run of the current live release. This is the permission that spends money. */
    public static final String INSTANCE_RUN_LIVE = "INSTANCE_RUN_LIVE";

    /** May poll run groups this connector created. Spends nothing and reads only its own. */
    public static final String INSTANCE_RUN_READ = "INSTANCE_RUN_READ";
}
