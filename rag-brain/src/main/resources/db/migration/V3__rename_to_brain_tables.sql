-- Platform rename (spec §7): the brain is company-agnostic, table names follow.
-- Rename keeps all indexes, constraints, and generated columns attached.
ALTER TABLE mortgage_document_chunks RENAME TO brain_document_chunks;
ALTER TABLE mortgage_documents RENAME TO brain_documents;
