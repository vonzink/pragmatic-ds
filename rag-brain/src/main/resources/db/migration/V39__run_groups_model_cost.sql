-- ============================================================================
-- Phase 4: run groups, versioned model pricing, usage, reservations,
-- evaluations, and live-pointer history.
--
-- NUMBERED V39, NOT V38. The Phase 4 plan was written expecting V37 to be the
-- latest applied migration and reserves V38 for this work. Phase 3 shipped
-- V38__parsed_input_registration.sql first, and an applied migration is
-- immutable history, so this takes the next free number exactly as that plan's
-- own global constraint instructs.
--
-- PRIVACY BOUNDARY. Every table below holds identifiers, counts, digests,
-- money, statuses, and timestamps. There is deliberately no text, jsonb, or
-- unbounded varchar column anywhere in this file that a prompt, a question, an
-- answer, evidence, a provider response body, a filename, a URL, or a
-- credential could be written into. The two exceptions are bounded and
-- administrative: an actor id and a change reason on the pointer event, both
-- length-capped and constrained to printable characters.
-- ============================================================================

-- ============================================================ run groups

-- One caller-visible submission. The group owns the external idempotency key;
-- its members own execution. A comparison group additionally records the digest
-- of everything the comparison holds equal, so "these runs differ only by
-- model" is a checkable claim rather than a description.
CREATE TABLE lab_run_group (
    id                        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    brain_id                  UUID         NOT NULL REFERENCES brains (id) ON DELETE RESTRICT,
    mode                      VARCHAR(16)  NOT NULL,
    comparison_dimension      VARCHAR(16),
    idempotency_key           VARCHAR(200) NOT NULL,
    request_sha256            VARCHAR(64)  NOT NULL,
    comparison_basis_sha256   VARCHAR(64),
    status                    VARCHAR(16)  NOT NULL,
    created_at                TIMESTAMPTZ  NOT NULL DEFAULT now(),
    terminal_at               TIMESTAMPTZ,
    cancellation_requested_at TIMESTAMPTZ,
    CONSTRAINT uq_lab_run_group_key UNIQUE (brain_id, idempotency_key),
    -- Lets a member carry a composite foreign key, so a run can never join a group
    -- belonging to another brain. Same pattern as every other brain-scoped table here.
    CONSTRAINT uq_lab_run_group_id_brain UNIQUE (id, brain_id),
    CONSTRAINT chk_lab_run_group_mode
        CHECK (mode IN ('INDEPENDENT', 'COMPARISON')),
    CONSTRAINT chk_lab_run_group_status
        CHECK (status IN ('QUEUED', 'PROCESSING', 'SUCCEEDED', 'PARTIAL', 'FAILED', 'CANCELLED')),
    CONSTRAINT chk_lab_run_group_request_sha
        CHECK (request_sha256 ~ '^[0-9a-f]{64}$'),
    -- A comparison declares what it varies and pins the digest of what it does
    -- not; an independent group declares neither. Each arm is explicitly
    -- null-guarded on both sides: in SQL a comparison against NULL yields NULL,
    -- and a CHECK is satisfied by NULL, so an unguarded arm is a hole.
    CONSTRAINT chk_lab_run_group_comparison CHECK (
        (mode = 'INDEPENDENT'
            AND comparison_dimension IS NULL
            AND comparison_basis_sha256 IS NULL)
        OR
        (mode = 'COMPARISON'
            AND comparison_dimension IS NOT NULL
            AND comparison_dimension IN ('MODEL', 'RELEASE', 'INSTANCE')
            AND comparison_basis_sha256 IS NOT NULL
            AND comparison_basis_sha256 ~ '^[0-9a-f]{64}$')),
    -- A terminal group records when it ended; a live one has not ended.
    CONSTRAINT chk_lab_run_group_terminal CHECK (
        (status IN ('QUEUED', 'PROCESSING') AND terminal_at IS NULL)
        OR (status IN ('SUCCEEDED', 'PARTIAL', 'FAILED', 'CANCELLED')
            AND terminal_at IS NOT NULL))
);
CREATE INDEX idx_lab_run_group_history ON lab_run_group (brain_id, created_at DESC);
CREATE INDEX idx_lab_run_group_open
    ON lab_run_group (brain_id, status) WHERE status IN ('QUEUED', 'PROCESSING');

CREATE FUNCTION lab_run_group_guard() RETURNS TRIGGER
    LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status IN ('SUCCEEDED', 'PARTIAL', 'FAILED', 'CANCELLED') THEN
        RAISE EXCEPTION 'LAB_RUN_GROUP_ALREADY_TERMINAL' USING ERRCODE = '23514';
    END IF;
    IF NEW.id <> OLD.id
        OR NEW.brain_id <> OLD.brain_id
        OR NEW.mode <> OLD.mode
        OR NEW.idempotency_key <> OLD.idempotency_key
        OR NEW.request_sha256 <> OLD.request_sha256
        OR NEW.created_at <> OLD.created_at
        OR NEW.comparison_dimension IS DISTINCT FROM OLD.comparison_dimension
        OR NEW.comparison_basis_sha256 IS DISTINCT FROM OLD.comparison_basis_sha256 THEN
        RAISE EXCEPTION 'LAB_RUN_GROUP_IDENTITY_IMMUTABLE' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_lab_run_group_guard
    BEFORE UPDATE ON lab_run_group
    FOR EACH ROW EXECUTE FUNCTION lab_run_group_guard();

-- ============================================================ model catalog

-- An immutable, configuration-derived price list. A version is appended when
-- the canonical hash of the configured entries changes and never overwritten,
-- so a run priced last month can still be re-derived from the exact numbers it
-- was priced against.
CREATE TABLE lab_model_catalog_version (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    catalog_sha256 VARCHAR(64) NOT NULL,
    entry_count   INT         NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_lab_catalog_sha UNIQUE (catalog_sha256),
    CONSTRAINT chk_lab_catalog_sha   CHECK (catalog_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT chk_lab_catalog_count CHECK (entry_count >= 0)
);
CREATE TRIGGER trg_lab_catalog_version_immutable
    BEFORE UPDATE OR DELETE ON lab_model_catalog_version
    FOR EACH ROW EXECUTE FUNCTION lab_reject_mutation();

-- One priced model within one catalog version. Prices are USD per million
-- tokens so the configured numbers are the stored numbers; no scaling happens
-- on the way in, and rounding is the estimator's business, not the schema's.
CREATE TABLE lab_model_catalog_entry (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    catalog_version_id       UUID           NOT NULL REFERENCES lab_model_catalog_version (id) ON DELETE RESTRICT,
    provider                 VARCHAR(40)    NOT NULL,
    model                    VARCHAR(160)   NOT NULL,
    context_token_ceiling    BIGINT         NOT NULL,
    output_token_ceiling     BIGINT         NOT NULL,
    tokenizer_strategy       VARCHAR(32)    NOT NULL,
    input_usd_per_million    NUMERIC(18, 6) NOT NULL,
    cached_input_usd_per_million NUMERIC(18, 6),
    output_usd_per_million   NUMERIC(18, 6) NOT NULL,
    currency                 VARCHAR(3)     NOT NULL,
    CONSTRAINT uq_lab_catalog_entry UNIQUE (catalog_version_id, provider, model),
    CONSTRAINT chk_lab_catalog_entry_ceilings
        CHECK (context_token_ceiling > 0 AND output_token_ceiling > 0
               AND output_token_ceiling <= context_token_ceiling),
    CONSTRAINT chk_lab_catalog_entry_tokenizer
        CHECK (tokenizer_strategy IN ('EXACT_PROVIDER', 'CONSERVATIVE_RANGE')),
    CONSTRAINT chk_lab_catalog_entry_prices
        CHECK (input_usd_per_million >= 0 AND output_usd_per_million >= 0
               AND (cached_input_usd_per_million IS NULL
                    OR cached_input_usd_per_million >= 0)),
    CONSTRAINT chk_lab_catalog_entry_currency CHECK (currency = 'USD')
);
CREATE INDEX idx_lab_catalog_entry_model
    ON lab_model_catalog_entry (catalog_version_id, provider, model);
CREATE TRIGGER trg_lab_catalog_entry_immutable
    BEFORE UPDATE OR DELETE ON lab_model_catalog_entry
    FOR EACH ROW EXECUTE FUNCTION lab_reject_mutation();

-- The version historical runs are priced against. It carries no entries on
-- purpose: runs that predate versioned pricing have no price, and recording
-- that honestly is the point. Inventing a retroactive price would make every
-- historical cost report quietly wrong instead of visibly absent.
INSERT INTO lab_model_catalog_version (id, catalog_sha256, entry_count)
VALUES ('00000000-0000-4000-8000-00000000f001',
        encode(sha256('legacy-unpriced'::bytea), 'hex'), 0);

-- ============================================================ run membership

ALTER TABLE lab_run
    ADD COLUMN run_group_id       UUID,
    ADD COLUMN member_index       INT,
    ADD COLUMN corpus_snapshot_id UUID,
    ADD COLUMN requested_provider VARCHAR(40),
    ADD COLUMN requested_model    VARCHAR(160),
    ADD COLUMN pricing_version_id UUID REFERENCES lab_model_catalog_version (id) ON DELETE RESTRICT;

-- Composite rather than simple: a run carries its own brain_id, so pairing it into the
-- reference makes cross-brain membership and cross-brain grounding unrepresentable instead
-- of merely forbidden in service code. corpus_snapshot_id stays nullable for historical
-- scope-retrieval runs, and a composite key with a NULL is simply not enforced, which is the
-- behaviour those rows need.
ALTER TABLE lab_run
    ADD CONSTRAINT fk_lab_run_group_same_brain
        FOREIGN KEY (run_group_id, brain_id)
        REFERENCES lab_run_group (id, brain_id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_lab_run_snapshot_same_brain
        FOREIGN KEY (corpus_snapshot_id, brain_id)
        REFERENCES brain_corpus_snapshot (id, brain_id) ON DELETE RESTRICT;

-- Every historical run becomes a one-member INDEPENDENT group whose id is the
-- run's own id, so no existing run, release, registration, or analysis id
-- moves. The group's request digest is the SHA-256 of its generated legacy key
-- — a real digest of a real string, not a fabricated hash of a request that
-- was never canonicalized under this scheme.
INSERT INTO lab_run_group (
    id, brain_id, mode, comparison_dimension, idempotency_key, request_sha256,
    comparison_basis_sha256, status, created_at, terminal_at)
SELECT
    run.id,
    run.brain_id,
    'INDEPENDENT',
    NULL,
    'legacy:' || run.id::text,
    encode(sha256(('legacy:' || run.id::text)::bytea), 'hex'),
    NULL,
    CASE run.status
        WHEN 'SUCCEEDED' THEN 'SUCCEEDED'
        WHEN 'PROCESSING' THEN 'PROCESSING'
        ELSE 'FAILED'
    END,
    run.created_at,
    run.terminal_at
FROM lab_run run;

-- The guard V34 installed rejects ANY update to a run that is not PROCESSING, so this
-- backfill cannot run through it: on an empty database there is nothing to update and the
-- statement is a no-op, but on a database with real run history — production — every
-- terminal run raises LAB_RUN_ALREADY_TERMINAL and the migration fails outright. The
-- trigger is therefore suspended for exactly this statement and restored immediately. That
-- is safe here because this is a single-statement, in-transaction backfill that touches
-- only the three new columns and moves no run's status, brain, release, or identity.
ALTER TABLE lab_run DISABLE TRIGGER trg_lab_run_guard;

UPDATE lab_run
SET run_group_id       = id,
    member_index       = 0,
    pricing_version_id = '00000000-0000-4000-8000-00000000f001';

ALTER TABLE lab_run ENABLE TRIGGER trg_lab_run_guard;

ALTER TABLE lab_run
    ALTER COLUMN run_group_id       SET NOT NULL,
    ALTER COLUMN member_index       SET NOT NULL,
    ALTER COLUMN pricing_version_id SET NOT NULL,
    ADD CONSTRAINT uq_lab_run_group_member UNIQUE (run_group_id, member_index),
    ADD CONSTRAINT chk_lab_run_member_index CHECK (member_index >= 0);

CREATE INDEX idx_lab_run_group_members ON lab_run (run_group_id, member_index);

-- ============================================================ run lifecycle

-- QUEUED and CANCELLED join the lifecycle. Both V34 shape constraints are
-- replaced rather than amended, because the original two-arm form has no way
-- to express a state that is neither PROCESSING nor terminal.
ALTER TABLE lab_run DROP CONSTRAINT chk_lab_run_status;
ALTER TABLE lab_run ADD CONSTRAINT chk_lab_run_status
    CHECK (status IN ('QUEUED', 'PROCESSING', 'SUCCEEDED', 'FAILED', 'INTERRUPTED', 'CANCELLED'));

ALTER TABLE lab_run DROP CONSTRAINT chk_lab_run_lease;
ALTER TABLE lab_run ADD CONSTRAINT chk_lab_run_lease CHECK (
    -- Waiting to be claimed: no lease, not yet ended.
    (status = 'QUEUED' AND lease_expires_at IS NULL AND terminal_at IS NULL)
    OR
    -- Claimed: holds a bounded lease, not yet ended.
    (status = 'PROCESSING' AND lease_expires_at IS NOT NULL AND terminal_at IS NULL)
    OR
    -- Cancelled before dispatch: ended, and never held a lease.
    (status = 'CANCELLED' AND lease_expires_at IS NULL AND terminal_at IS NOT NULL)
    OR
    -- Ran and finished: the lease is released and the end is recorded.
    (status IN ('SUCCEEDED', 'FAILED', 'INTERRUPTED') AND terminal_at IS NOT NULL));

ALTER TABLE lab_run DROP CONSTRAINT chk_lab_run_analysis_link;
ALTER TABLE lab_run ADD CONSTRAINT chk_lab_run_analysis_link CHECK (
    (status IN ('QUEUED', 'PROCESSING', 'CANCELLED') AND analysis_run_id IS NULL)
    OR (status = 'SUCCEEDED' AND analysis_run_id IS NOT NULL)
    OR status IN ('FAILED', 'INTERRUPTED'));

-- The guard now polices transitions as well as identity. Legal moves are
-- QUEUED -> PROCESSING, QUEUED -> CANCELLED, and PROCESSING -> a run terminal
-- state. Everything else — including any move out of a terminal state and any
-- attempt to re-queue — is rejected, which is what makes an expired lease a
-- no-replay boundary rather than a race.
CREATE OR REPLACE FUNCTION lab_run_guard() RETURNS TRIGGER
    LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status NOT IN ('QUEUED', 'PROCESSING') THEN
        RAISE EXCEPTION 'LAB_RUN_ALREADY_TERMINAL' USING ERRCODE = '23514';
    END IF;
    IF NOT (
        (OLD.status = 'QUEUED' AND NEW.status IN ('QUEUED', 'PROCESSING', 'CANCELLED'))
        OR (OLD.status = 'PROCESSING'
            AND NEW.status IN ('PROCESSING', 'SUCCEEDED', 'FAILED', 'INTERRUPTED'))
    ) THEN
        RAISE EXCEPTION 'LAB_RUN_TRANSITION_ILLEGAL' USING ERRCODE = '23514';
    END IF;
    IF NEW.id <> OLD.id
        OR NEW.brain_id <> OLD.brain_id
        OR NEW.instance_slug <> OLD.instance_slug
        OR NEW.idempotency_key <> OLD.idempotency_key
        OR NEW.release_id <> OLD.release_id
        OR NEW.registration_id <> OLD.registration_id
        OR NEW.created_at <> OLD.created_at
        OR NEW.run_group_id <> OLD.run_group_id
        OR NEW.member_index <> OLD.member_index
        OR NEW.pricing_version_id <> OLD.pricing_version_id
        OR NEW.corpus_snapshot_id IS DISTINCT FROM OLD.corpus_snapshot_id
        OR NEW.requested_provider IS DISTINCT FROM OLD.requested_provider
        OR NEW.requested_model IS DISTINCT FROM OLD.requested_model THEN
        RAISE EXCEPTION 'LAB_RUN_IDENTITY_IMMUTABLE' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

-- Claiming scans for the oldest eligible queued member.
CREATE INDEX idx_lab_run_queued ON lab_run (created_at) WHERE status = 'QUEUED';

-- ============================================================ usage and cost

-- What a member was expected to cost, and — once the provider answers — what it
-- actually did. The estimate columns are immutable: an estimate that could be
-- rewritten after the fact would make every budget decision unauditable.
CREATE TABLE lab_model_usage (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id                UUID           NOT NULL REFERENCES lab_run (id) ON DELETE RESTRICT,
    brain_id              UUID           NOT NULL REFERENCES brains (id) ON DELETE RESTRICT,
    pricing_version_id    UUID           NOT NULL REFERENCES lab_model_catalog_version (id) ON DELETE RESTRICT,
    provider              VARCHAR(40)    NOT NULL,
    model                 VARCHAR(160)   NOT NULL,
    expected_input_min    BIGINT         NOT NULL,
    expected_input_max    BIGINT         NOT NULL,
    expected_output_min   BIGINT         NOT NULL,
    expected_output_max   BIGINT         NOT NULL,
    expected_cost_usd_min NUMERIC(18, 6) NOT NULL,
    expected_cost_usd_max NUMERIC(18, 6) NOT NULL,
    estimate_quality      VARCHAR(24)    NOT NULL,
    actual_input_tokens   BIGINT,
    actual_cached_tokens  BIGINT,
    actual_output_tokens  BIGINT,
    actual_total_tokens   BIGINT,
    actual_cost_usd       NUMERIC(18, 6),
    usage_quality         VARCHAR(16)    NOT NULL DEFAULT 'PENDING',
    created_at            TIMESTAMPTZ    NOT NULL DEFAULT now(),
    reported_at           TIMESTAMPTZ,
    CONSTRAINT uq_lab_model_usage_run UNIQUE (run_id),
    CONSTRAINT chk_lab_usage_expected_order
        CHECK (expected_input_min >= 0 AND expected_input_max >= expected_input_min
               AND expected_output_min >= 0 AND expected_output_max >= expected_output_min
               AND expected_cost_usd_min >= 0
               AND expected_cost_usd_max >= expected_cost_usd_min),
    CONSTRAINT chk_lab_usage_estimate_quality
        CHECK (estimate_quality IN ('EXACT_TOKENIZER', 'ESTIMATED_RANGE')),
    CONSTRAINT chk_lab_usage_quality
        CHECK (usage_quality IN ('PENDING', 'REPORTED', 'INFERRED', 'UNAVAILABLE')),
    CONSTRAINT chk_lab_usage_actuals_nonneg
        CHECK ((actual_input_tokens  IS NULL OR actual_input_tokens  >= 0)
           AND (actual_cached_tokens IS NULL OR actual_cached_tokens >= 0)
           AND (actual_output_tokens IS NULL OR actual_output_tokens >= 0)
           AND (actual_total_tokens  IS NULL OR actual_total_tokens  >= 0)
           AND (actual_cost_usd      IS NULL OR actual_cost_usd      >= 0)),
    -- PENDING and UNAVAILABLE carry no numbers; an absent provider usage report
    -- is never converted into a reported zero. REPORTED and INFERRED must carry
    -- both a token count and the moment they were recorded.
    CONSTRAINT chk_lab_usage_shape CHECK (
        (usage_quality IN ('PENDING', 'UNAVAILABLE')
            AND actual_input_tokens IS NULL AND actual_cached_tokens IS NULL
            AND actual_output_tokens IS NULL AND actual_total_tokens IS NULL
            AND actual_cost_usd IS NULL AND reported_at IS NULL)
        OR
        (usage_quality IN ('REPORTED', 'INFERRED')
            AND actual_total_tokens IS NOT NULL
            AND actual_cost_usd IS NOT NULL
            AND reported_at IS NOT NULL))
);
CREATE INDEX idx_lab_model_usage_brain ON lab_model_usage (brain_id, created_at DESC);

-- One terminal transition only: PENDING may become REPORTED, INFERRED, or
-- UNAVAILABLE, and nothing may change afterwards. The estimate never moves at
-- all, in any state.
CREATE FUNCTION lab_model_usage_guard() RETURNS TRIGGER
    LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.usage_quality <> 'PENDING' THEN
        RAISE EXCEPTION 'LAB_USAGE_ALREADY_FINAL' USING ERRCODE = '23514';
    END IF;
    IF NEW.usage_quality NOT IN ('REPORTED', 'INFERRED', 'UNAVAILABLE') THEN
        RAISE EXCEPTION 'LAB_USAGE_TRANSITION_ILLEGAL' USING ERRCODE = '23514';
    END IF;
    IF NEW.id <> OLD.id
        OR NEW.run_id <> OLD.run_id
        OR NEW.brain_id <> OLD.brain_id
        OR NEW.pricing_version_id <> OLD.pricing_version_id
        OR NEW.provider <> OLD.provider
        OR NEW.model <> OLD.model
        OR NEW.expected_input_min <> OLD.expected_input_min
        OR NEW.expected_input_max <> OLD.expected_input_max
        OR NEW.expected_output_min <> OLD.expected_output_min
        OR NEW.expected_output_max <> OLD.expected_output_max
        OR NEW.expected_cost_usd_min <> OLD.expected_cost_usd_min
        OR NEW.expected_cost_usd_max <> OLD.expected_cost_usd_max
        OR NEW.estimate_quality <> OLD.estimate_quality
        OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'LAB_USAGE_ESTIMATE_IMMUTABLE' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_lab_model_usage_guard
    BEFORE UPDATE ON lab_model_usage
    FOR EACH ROW EXECUTE FUNCTION lab_model_usage_guard();

-- The conservative maximum held against a brain's budget while a member is in
-- flight. Reserving the maximum rather than the estimate is what lets a budget
-- rejection happen before dispatch instead of after the bill.
CREATE TABLE lab_spend_reservation (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id            UUID           NOT NULL REFERENCES lab_run (id) ON DELETE RESTRICT,
    brain_id          UUID           NOT NULL REFERENCES brains (id) ON DELETE RESTRICT,
    reserved_max_usd  NUMERIC(18, 6) NOT NULL,
    status            VARCHAR(16)    NOT NULL DEFAULT 'RESERVED',
    created_at        TIMESTAMPTZ    NOT NULL DEFAULT now(),
    terminal_at       TIMESTAMPTZ,
    CONSTRAINT uq_lab_reservation_run UNIQUE (run_id),
    CONSTRAINT chk_lab_reservation_amount CHECK (reserved_max_usd >= 0),
    CONSTRAINT chk_lab_reservation_status
        CHECK (status IN ('RESERVED', 'CONSUMED', 'RELEASED')),
    CONSTRAINT chk_lab_reservation_terminal CHECK (
        (status = 'RESERVED' AND terminal_at IS NULL)
        OR (status IN ('CONSUMED', 'RELEASED') AND terminal_at IS NOT NULL))
);
-- The live exposure a budget check sums.
CREATE INDEX idx_lab_reservation_active
    ON lab_spend_reservation (brain_id) WHERE status = 'RESERVED';

CREATE FUNCTION lab_spend_reservation_guard() RETURNS TRIGGER
    LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status <> 'RESERVED' THEN
        RAISE EXCEPTION 'LAB_RESERVATION_ALREADY_FINAL' USING ERRCODE = '23514';
    END IF;
    IF NEW.status NOT IN ('CONSUMED', 'RELEASED') THEN
        RAISE EXCEPTION 'LAB_RESERVATION_TRANSITION_ILLEGAL' USING ERRCODE = '23514';
    END IF;
    IF NEW.id <> OLD.id
        OR NEW.run_id <> OLD.run_id
        OR NEW.brain_id <> OLD.brain_id
        OR NEW.reserved_max_usd <> OLD.reserved_max_usd
        OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'LAB_RESERVATION_IMMUTABLE' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_lab_spend_reservation_guard
    BEFORE UPDATE ON lab_spend_reservation
    FOR EACH ROW EXECUTE FUNCTION lab_spend_reservation_guard();

-- ============================================================ promotion gate

-- One evaluation of one immutable release against one scenario set. The report
-- itself lives outside the database; only its digest, score, and verdict are
-- stored, because a scenario report contains model responses.
CREATE TABLE lab_release_evaluation (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    brain_id            UUID          NOT NULL REFERENCES brains (id) ON DELETE RESTRICT,
    release_id          UUID          NOT NULL REFERENCES lab_instance_release (id) ON DELETE RESTRICT,
    scenario_set_id     VARCHAR(64)   NOT NULL,
    scenario_set_version INT          NOT NULL,
    score               NUMERIC(6, 4) NOT NULL,
    passed              BOOLEAN       NOT NULL,
    report_sha256       VARCHAR(64)   NOT NULL,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_lab_evaluation UNIQUE (release_id, scenario_set_id, scenario_set_version),
    CONSTRAINT chk_lab_evaluation_score   CHECK (score >= 0 AND score <= 1),
    CONSTRAINT chk_lab_evaluation_version CHECK (scenario_set_version >= 1),
    CONSTRAINT chk_lab_evaluation_report  CHECK (report_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT chk_lab_evaluation_set_id  CHECK (scenario_set_id ~ '^[a-z][a-z0-9-]{0,63}$')
);
CREATE INDEX idx_lab_evaluation_release ON lab_release_evaluation (release_id, created_at DESC);
CREATE TRIGGER trg_lab_evaluation_immutable
    BEFORE UPDATE OR DELETE ON lab_release_evaluation
    FOR EACH ROW EXECUTE FUNCTION lab_reject_mutation();

-- Compare-and-set version for the live pointer. Existing rows start at 0, and
-- no pointer target moves during this migration.
ALTER TABLE lab_instance_pointer
    ADD COLUMN pointer_version BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT chk_lab_pointer_version CHECK (pointer_version >= 0);

-- Every movement of a live pointer, appended and never rewritten.
--
-- actor_id and change_reason are the only free-text columns in this file. Both
-- are bounded, and change_reason is constrained to printable characters so a
-- control sequence or an embedded newline cannot be used to smuggle structure
-- into an audit export. The DTO boundary additionally rejects
-- borrower-data-shaped input; this constraint is the floor, not the policy.
CREATE TABLE lab_instance_pointer_event (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    brain_id         UUID         NOT NULL REFERENCES brains (id) ON DELETE RESTRICT,
    instance_slug    VARCHAR(32)  NOT NULL,
    action           VARCHAR(16)  NOT NULL,
    from_release_id  UUID         REFERENCES lab_instance_release (id) ON DELETE RESTRICT,
    to_release_id    UUID         NOT NULL REFERENCES lab_instance_release (id) ON DELETE RESTRICT,
    pointer_version  BIGINT       NOT NULL,
    actor_id         VARCHAR(120) NOT NULL,
    change_reason    VARCHAR(400) NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_lab_pointer_event UNIQUE (brain_id, instance_slug, pointer_version),
    CONSTRAINT chk_lab_pointer_event_slug   CHECK (instance_slug ~ '^[a-z][a-z0-9-]{0,31}$'),
    CONSTRAINT chk_lab_pointer_event_action CHECK (action IN ('PROMOTE', 'ROLLBACK')),
    CONSTRAINT chk_lab_pointer_event_version CHECK (pointer_version >= 1),
    -- Non-blank and free of control characters: a newline or an escape sequence in an
    -- audit export is a way to smuggle structure into it, and the length caps are the
    -- column types themselves.
    CONSTRAINT chk_lab_pointer_event_actor
        CHECK (btrim(actor_id) <> '' AND actor_id !~ '[[:cntrl:]]'),
    CONSTRAINT chk_lab_pointer_event_reason
        CHECK (btrim(change_reason) <> '' AND change_reason !~ '[[:cntrl:]]'),
    -- A rollback always names what it moved away from; only a first promotion
    -- has no predecessor, and it can never be a rollback.
    CONSTRAINT chk_lab_pointer_event_from CHECK (
        (action = 'PROMOTE')
        OR (action = 'ROLLBACK' AND from_release_id IS NOT NULL))
);
CREATE INDEX idx_lab_pointer_event_history
    ON lab_instance_pointer_event (brain_id, instance_slug, pointer_version DESC);
CREATE TRIGGER trg_lab_pointer_event_immutable
    BEFORE UPDATE OR DELETE ON lab_instance_pointer_event
    FOR EACH ROW EXECUTE FUNCTION lab_reject_mutation();

-- ============================================================ discussion cost

-- A v2 discussion turn answers from a run's saved context rather than from live
-- dependencies, which is a materially different claim from the prototype's, so
-- the bounded discriminator gains one value rather than being loosened.
ALTER TABLE lab_discussion_exchange DROP CONSTRAINT chk_lab_exchange_limitations;
ALTER TABLE lab_discussion_exchange ADD CONSTRAINT chk_lab_exchange_limitations
    CHECK (prototype_limitations IN ('PROTOTYPE_LIVE_DEPENDENCIES', 'PINNED_RUN_CONTEXT'));

CREATE TABLE lab_discussion_model_usage (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    exchange_id          UUID           NOT NULL REFERENCES lab_discussion_exchange (id) ON DELETE RESTRICT,
    brain_id             UUID           NOT NULL REFERENCES brains (id) ON DELETE RESTRICT,
    pricing_version_id   UUID           NOT NULL REFERENCES lab_model_catalog_version (id) ON DELETE RESTRICT,
    provider             VARCHAR(40)    NOT NULL,
    model                VARCHAR(160)   NOT NULL,
    actual_input_tokens  BIGINT,
    actual_cached_tokens BIGINT,
    actual_output_tokens BIGINT,
    actual_total_tokens  BIGINT,
    actual_cost_usd      NUMERIC(18, 6),
    usage_quality        VARCHAR(16)    NOT NULL DEFAULT 'PENDING',
    created_at           TIMESTAMPTZ    NOT NULL DEFAULT now(),
    reported_at          TIMESTAMPTZ,
    CONSTRAINT uq_lab_discussion_usage UNIQUE (exchange_id),
    CONSTRAINT chk_lab_discussion_usage_quality
        CHECK (usage_quality IN ('PENDING', 'REPORTED', 'INFERRED', 'UNAVAILABLE')),
    CONSTRAINT chk_lab_discussion_usage_nonneg
        CHECK ((actual_input_tokens  IS NULL OR actual_input_tokens  >= 0)
           AND (actual_cached_tokens IS NULL OR actual_cached_tokens >= 0)
           AND (actual_output_tokens IS NULL OR actual_output_tokens >= 0)
           AND (actual_total_tokens  IS NULL OR actual_total_tokens  >= 0)
           AND (actual_cost_usd      IS NULL OR actual_cost_usd      >= 0)),
    CONSTRAINT chk_lab_discussion_usage_shape CHECK (
        (usage_quality IN ('PENDING', 'UNAVAILABLE')
            AND actual_input_tokens IS NULL AND actual_cached_tokens IS NULL
            AND actual_output_tokens IS NULL AND actual_total_tokens IS NULL
            AND actual_cost_usd IS NULL AND reported_at IS NULL)
        OR
        (usage_quality IN ('REPORTED', 'INFERRED')
            AND actual_total_tokens IS NOT NULL
            AND actual_cost_usd IS NOT NULL
            AND reported_at IS NOT NULL))
);
-- Its own guard, not lab_model_usage_guard(): that function reads estimate columns this
-- table does not have, and a trigger referencing a missing field fails at write time rather
-- than at migration time — the worst place to find out.
CREATE FUNCTION lab_discussion_usage_guard() RETURNS TRIGGER
    LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.usage_quality <> 'PENDING' THEN
        RAISE EXCEPTION 'LAB_DISCUSSION_USAGE_ALREADY_FINAL' USING ERRCODE = '23514';
    END IF;
    IF NEW.usage_quality NOT IN ('REPORTED', 'INFERRED', 'UNAVAILABLE') THEN
        RAISE EXCEPTION 'LAB_DISCUSSION_USAGE_TRANSITION_ILLEGAL' USING ERRCODE = '23514';
    END IF;
    IF NEW.id <> OLD.id
        OR NEW.exchange_id <> OLD.exchange_id
        OR NEW.brain_id <> OLD.brain_id
        OR NEW.pricing_version_id <> OLD.pricing_version_id
        OR NEW.provider <> OLD.provider
        OR NEW.model <> OLD.model
        OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'LAB_DISCUSSION_USAGE_IMMUTABLE' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_lab_discussion_usage_guard
    BEFORE UPDATE ON lab_discussion_model_usage
    FOR EACH ROW EXECUTE FUNCTION lab_discussion_usage_guard();
