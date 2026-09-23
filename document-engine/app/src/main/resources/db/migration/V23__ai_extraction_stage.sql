-- Phase 3: the gated AI extraction stage executes after deterministic extraction and before
-- finalization. Existing migration history is immutable; widen only the job-state vocabulary.
ALTER TABLE processing_job DROP CONSTRAINT processing_job_status_check;
ALTER TABLE processing_job
    ADD CONSTRAINT processing_job_status_check CHECK (status IN (
        'UPLOADED', 'VALIDATING', 'NORMALIZING', 'RENDERING', 'TEXT_EXTRACTION',
        'OCR_PROCESSING', 'PARSING', 'CLASSIFYING', 'SPLITTING', 'EXTRACTING',
        'AI_EXTRACTION', 'FINALIZING', 'VALIDATING_DATA', 'AI_REVIEW',
        'HUMAN_REVIEW_REQUIRED', 'COMPLETED', 'FAILED'));

-- The read model names this producer explicitly rather than overloading the older generic LLM
-- slot. Keep all prior methods available for immutable historical rows and schema rungs.
ALTER TABLE extracted_field DROP CONSTRAINT extracted_field_method_check;
ALTER TABLE extracted_field ADD CONSTRAINT extracted_field_method_check
    CHECK (extraction_method IN ('ANCHOR_LABEL', 'TABLE_CLUSTER', 'REGEX', 'FORM_FIELD',
                                 'OCR_LINE', 'LLM', 'AI', 'HUMAN', 'NONE',
                                 'CHECKBOX_STATE', 'SIGNATURE_PRESENCE',
                                 'LABEL_BELOW', 'ROW_CELL'));
