-- V14 — Schedule E relative value line.
--
-- Private-corpus evidence established that incomeOrLoss needs one narrowly authored
-- fallback; no corpus identifier, text, value, or coordinate is embedded here. The
-- original offset-zero extractor remains byte-identical and first, so every document
-- it already handles keeps the same winning rung and evidence behavior. The sole new
-- rung is an exact offset-2 copy for the evidenced co-linear layout.
--
-- Nearby scanning is forbidden: Schedule E is dense with adjacent amounts, and an
-- unbounded search could return a plausible value from the wrong line at high confidence.

ALTER TABLE extraction_schema NO FORCE ROW LEVEL SECURITY;

DO $$
DECLARE
    source_definition jsonb;
    income_field jsonb;
    successor_fields jsonb;
    successor_definition jsonb;
    changed_rows integer;
BEGIN
    SELECT definition INTO STRICT source_definition
      FROM extraction_schema
     WHERE org_id IS NULL
       AND document_type_code = 'SCHEDULE_E'
       AND version = '1.0.0'
       AND is_active;

    SELECT field INTO STRICT income_field
      FROM jsonb_array_elements(source_definition->'fields') AS item(field)
     WHERE field->>'name' = 'incomeOrLoss';

    IF jsonb_array_length(income_field->'extractors') <> 1
       OR income_field->'extractors'->0->>'method' <> 'ANCHOR_LABEL'
       OR income_field->'extractors'->0->'value'->>'scope' <> 'LINE'
       OR (income_field->'extractors'->0->'value'->>'occurrence')::integer <> 0 THEN
        RAISE EXCEPTION 'unexpected SCHEDULE_E@1.0.0 incomeOrLoss shape';
    END IF;

    SELECT jsonb_agg(
               CASE WHEN field->>'name' = 'incomeOrLoss'
                    THEN jsonb_set(
                             field,
                             '{extractors}',
                             (field->'extractors') || jsonb_build_array(
                                 jsonb_set(
                                     field->'extractors'->0,
                                     '{value,lineOffset}',
                                     '2'::jsonb,
                                     true)),
                             false)
                    ELSE field
               END
               ORDER BY ordinal)
      INTO successor_fields
      FROM jsonb_array_elements(source_definition->'fields')
           WITH ORDINALITY AS item(field, ordinal);

    successor_definition :=
        jsonb_set(source_definition, '{fields}', successor_fields, false);

    UPDATE extraction_schema
       SET is_active = false
     WHERE org_id IS NULL
       AND document_type_code = 'SCHEDULE_E'
       AND version = '1.0.0'
       AND is_active;
    GET DIAGNOSTICS changed_rows = ROW_COUNT;
    IF changed_rows <> 1 THEN
        RAISE EXCEPTION 'expected one active SCHEDULE_E@1.0.0 row';
    END IF;

    INSERT INTO extraction_schema
        (org_id, document_type_code, version, definition)
    VALUES
        (NULL, 'SCHEDULE_E', '1.0.1', successor_definition);
END $$;

ALTER TABLE extraction_schema FORCE ROW LEVEL SECURITY;
