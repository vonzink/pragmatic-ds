CREATE TABLE brain_connector_clients (
    id UUID PRIMARY KEY,
    name VARCHAR(160) NOT NULL,
    type VARCHAR(40) NOT NULL,
    brain_id UUID REFERENCES brains(id),
    token_hash VARCHAR(128) UNIQUE,
    scopes JSONB NOT NULL DEFAULT '[]'::jsonb,
    allowed_origins JSONB NOT NULL DEFAULT '[]'::jsonb,
    allowed_peer_hosts JSONB NOT NULL DEFAULT '[]'::jsonb,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    last_used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_connector_clients_brain ON brain_connector_clients(brain_id);
CREATE INDEX idx_connector_clients_enabled ON brain_connector_clients(enabled);

CREATE TABLE brain_connector_events (
    id UUID PRIMARY KEY,
    connector_client_id UUID REFERENCES brain_connector_clients(id),
    brain_id UUID REFERENCES brains(id),
    event_type VARCHAR(40) NOT NULL,
    scope VARCHAR(80),
    request_host VARCHAR(255),
    request_id VARCHAR(120),
    status VARCHAR(40) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_connector_events_client_created
    ON brain_connector_events(connector_client_id, created_at DESC);
CREATE INDEX idx_connector_events_brain_created
    ON brain_connector_events(brain_id, created_at DESC);
