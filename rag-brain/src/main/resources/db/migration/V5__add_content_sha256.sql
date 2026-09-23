-- Content hash for sync change-detection (spec §6). Null on legacy rows:
-- those keep the script-era skip-if-active behavior until re-uploaded.
ALTER TABLE brain_documents ADD COLUMN content_sha256 VARCHAR(64);
