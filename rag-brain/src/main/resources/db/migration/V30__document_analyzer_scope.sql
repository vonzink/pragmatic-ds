-- V30: per-document analyzer scope for folder-analyzer corpora. Nullable: null
-- means "shared" — the document grounds every analyzer (the pre-feature
-- behavior). A value (income|assets|title|credit|reo — app-enforced, engine
-- analyzer slugs) restricts admin/analyze retrieval to that analyzer's scope.
-- Sync derives it from the S3 subfolder; the corpus manifest can override.
ALTER TABLE brain_documents ADD COLUMN analyzer_scope VARCHAR(40);
