-- Append-only revisions of the owner-editable rule blocks. The effective
-- text is the newest row per key; NULL content means "use the pack default"
-- (an explicit, attributable revert). Pack defaults are revision zero,
-- implicit and immutable.
CREATE TABLE brain_rule_revisions (
    id          UUID         PRIMARY KEY,
    rule_key    VARCHAR(32)  NOT NULL,
    content     TEXT,
    created_at  TIMESTAMPTZ  NOT NULL,
    created_by  VARCHAR(100) NOT NULL
);
CREATE INDEX idx_rule_revisions_key_created ON brain_rule_revisions (rule_key, created_at DESC);
