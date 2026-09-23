-- Bind connector tokens to the tenants they may act for and the dashboard permissions
-- they are granted. Before this, the tool gateway trusted tenantId/permissions supplied
-- in the request body, so any connector-token holder could impersonate any tenant and
-- self-assert any permission. These columns move that authority onto the token itself.
ALTER TABLE brain_connector_clients
    ADD COLUMN allowed_tenants JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN granted_permissions JSONB NOT NULL DEFAULT '[]'::jsonb;
