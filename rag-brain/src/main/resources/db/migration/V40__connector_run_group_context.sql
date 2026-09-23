-- ============================================================ connector run ownership
--
-- One row per run group that a server-to-server connector created, binding the group to
-- the connector client and the tenant it acted for. Polling authorizes against this row:
-- the reading connector and its X-Tenant-Id must match what is stored here, so a second
-- tenant permitted on the same token — or a second connector on the same brain — cannot
-- read a group it did not create.
--
-- external_request_sha256 is the digest of the CONNECTOR-VISIBLE request (brain, instance,
-- tenant, package, revision, sorted sources, external request id). It is deliberately a
-- different digest from lab_run_group.request_sha256, which hashes the RESOLVED members —
-- including the release id the live pointer supplied at submission. A connector retry after
-- a promotion must replay the original group rather than being refused because live moved,
-- and only a digest over what the caller actually sent can decide that.
--
-- What is deliberately absent: loan ids, folder ids, document names, filenames, parsed
-- values, and response payloads. external_request_id is an opaque caller correlation id.
CREATE TABLE lab_connector_run_group_context (
    run_group_id            UUID PRIMARY KEY REFERENCES lab_run_group (id) ON DELETE RESTRICT,
    connector_client_id     UUID NOT NULL REFERENCES brain_connector_clients (id) ON DELETE RESTRICT,
    brain_id                UUID NOT NULL REFERENCES brains (id) ON DELETE RESTRICT,
    tenant_id               VARCHAR(120) NOT NULL,
    external_request_id     VARCHAR(120),
    external_request_sha256 VARCHAR(64) NOT NULL,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_connector_context_tenant
        CHECK (length(btrim(tenant_id)) BETWEEN 1 AND 120),
    CONSTRAINT chk_connector_context_external_id
        CHECK (external_request_id IS NULL OR length(btrim(external_request_id)) BETWEEN 1 AND 120),
    CONSTRAINT chk_connector_context_hash
        CHECK (external_request_sha256 ~ '^[0-9a-f]{64}$'),
    -- Control characters in the caller's correlation id would let one request forge a second
    -- line in any log or export that prints it. Same [[:cntrl:]] class V39's audit rows use.
    CONSTRAINT chk_connector_context_tenant_clean
        CHECK (tenant_id !~ '[[:cntrl:]]'),
    CONSTRAINT chk_connector_context_external_clean
        CHECK (external_request_id IS NULL OR external_request_id !~ '[[:cntrl:]]')
);

CREATE INDEX idx_lab_connector_group_owner
    ON lab_connector_run_group_context (connector_client_id, tenant_id, created_at DESC);

-- The row is who created the group, written once in the group's own transaction. There is
-- no fact on it that legitimately changes afterwards, so UPDATE is refused outright rather
-- than column-by-column: ownership that can be edited is not ownership.
CREATE FUNCTION lab_connector_context_guard() RETURNS TRIGGER
    LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'LAB_CONNECTOR_CONTEXT_IMMUTABLE' USING ERRCODE = '23514';
END;
$$;
CREATE TRIGGER trg_lab_connector_context_guard
    BEFORE UPDATE ON lab_connector_run_group_context
    FOR EACH ROW EXECUTE FUNCTION lab_connector_context_guard();
