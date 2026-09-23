-- The reusable platform should boot with a neutral, empty default brain.
-- Legacy example content is not bundled.
UPDATE brains
SET slug = 'generic',
    display_name = 'Generic Brain',
    pack_ref = 'packs/generic',
    updated_at = now()
WHERE id = '00000000-0000-0000-0000-000000000001'
  AND is_default = TRUE
  AND slug = 'mortgage'
  AND pack_ref = 'packs/example-mortgage';
