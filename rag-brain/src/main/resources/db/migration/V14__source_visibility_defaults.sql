UPDATE brain_documents
SET visibility = 'INTERNAL'
WHERE source_type IN ('INTERNAL_POLICY', 'INVESTOR_OVERLAY');

ALTER TABLE brain_documents
    ALTER COLUMN visibility SET DEFAULT 'INTERNAL';
