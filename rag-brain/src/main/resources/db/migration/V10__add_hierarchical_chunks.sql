ALTER TABLE brain_document_chunks
    ADD COLUMN chunk_type VARCHAR(20) NOT NULL DEFAULT 'CHILD',
    ADD COLUMN parent_chunk_id UUID REFERENCES brain_document_chunks (id) ON DELETE SET NULL,
    ADD COLUMN hierarchy_path VARCHAR(1000),
    ADD COLUMN hierarchy_level INTEGER;

CREATE INDEX idx_chunks_brain_type ON brain_document_chunks (brain_id, chunk_type);
CREATE INDEX idx_chunks_parent ON brain_document_chunks (parent_chunk_id);
