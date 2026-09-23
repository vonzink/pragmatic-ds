-- V46: precompute the normalized-content hash the duplicate-chunk metric groups on.
--
-- countDuplicateTextGroups lowercased, whitespace-collapsed and trimmed every
-- chunk on every call: 2.2 s on the mortgage brain (~3,000 child chunks), which is
-- why the Overview sat on "Loading…" for the ingestion-quality tile. The same
-- normalization now lives in a STORED generated column, so Postgres computes it
-- once per write (ingest, reindex) and the metric becomes an indexed GROUP BY.
-- regexp_replace is IMMUTABLE, which is what a generated column requires.
--
-- md5('') = d41d8cd98f00b204e9800998ecf8427e marks a chunk whose text is empty
-- after normalization; the metric skips that value instead of re-running the regex.
ALTER TABLE brain_document_chunks
    ADD COLUMN content_norm_md5 CHAR(32)
        GENERATED ALWAYS AS (md5(btrim(regexp_replace(lower(content), '\s+', ' ', 'g')))) STORED;

CREATE INDEX idx_chunks_brain_norm_md5 ON brain_document_chunks (brain_id, content_norm_md5);
