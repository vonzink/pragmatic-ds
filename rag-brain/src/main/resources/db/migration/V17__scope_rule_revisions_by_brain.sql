ALTER TABLE brain_rule_revisions
    ADD COLUMN brain_id UUID;

UPDATE brain_rule_revisions
SET brain_id = '00000000-0000-0000-0000-000000000001'
WHERE brain_id IS NULL;

ALTER TABLE brain_rule_revisions
    ALTER COLUMN brain_id SET NOT NULL,
    ADD CONSTRAINT fk_brain_rule_revisions_brain
        FOREIGN KEY (brain_id) REFERENCES brains(id) ON DELETE RESTRICT;

DROP INDEX IF EXISTS idx_rule_revisions_key_created;

CREATE INDEX idx_rule_revisions_brain_key_created
    ON brain_rule_revisions (brain_id, rule_key, created_at DESC, id DESC);
