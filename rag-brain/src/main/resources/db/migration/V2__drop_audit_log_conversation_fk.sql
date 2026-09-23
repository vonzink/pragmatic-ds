-- ============================================================
-- Audit logs are written in an independent transaction
-- (REQUIRES_NEW) so they survive request rollbacks. They cannot
-- hold a foreign key to ai_conversations: the referenced row may
-- be uncommitted (same request) or rolled back entirely.
-- The conversation_id column stays for joins; it is just no
-- longer enforced. This is intentional for a write-once log table.
-- ============================================================

ALTER TABLE ai_audit_logs
    DROP CONSTRAINT IF EXISTS ai_audit_logs_conversation_id_fkey;
