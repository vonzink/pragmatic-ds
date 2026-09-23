-- V48: give every unversioned document a version, so a corpus snapshot can pin it.
--
-- CorpusSnapshotService refuses a document with no document_version, and corpus sync never
-- stated one, so in 2026-09 only 13 of the mortgage brain's 98 active documents could be frozen
-- and no instance evaluation could run. Ingest now stamps the same rule
-- (DocumentIngestionService.versionOf); this applies it to the rows that predate it.
--
-- The version is the edition date when known, else a label derived from the content. A stated
-- version is never touched.

UPDATE brain_documents
SET document_version = CASE
        WHEN effective_date IS NOT NULL THEN to_char(effective_date, 'YYYY-MM-DD')
        ELSE 'sha-' || left(content_sha256, 12)
    END
WHERE (document_version IS NULL OR btrim(document_version) = '')
  AND (effective_date IS NOT NULL OR content_sha256 ~ '^[0-9a-f]{64}$');
