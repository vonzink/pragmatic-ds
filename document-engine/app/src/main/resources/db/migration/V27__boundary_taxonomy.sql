-- V27 — Phase E1 of the Reducto-parity roadmap: the boundary-extraction taxonomy is DATA.
--
-- The type list sent to the model (design §7) is assembled from document_type rows, never from
-- literals in Java: adding a lender-specific type to the AI splitter must be a data change, and
-- the mortgage knowledge must stay in classification-owned rows, not in platform code or prompts.
-- split_description is the one sentence of that knowledge each type carries: what the type LOOKS
-- like at a boundary — the header a first page prints, the shape that distinguishes it — written
-- for a model deciding "does a new document start here", not for a human browsing a list (that is
-- display_name's job).
--
-- NULLABLE: an org-inserted type with no description simply contributes its display name, exactly
-- what the taxonomy assembler already sends today. The seeded built-ins are all authored below.
ALTER TABLE document_type ADD COLUMN split_description text;

COMMENT ON COLUMN document_type.split_description IS
    'One sentence describing what this type looks like at a document boundary, sent to the '
    'boundary-extraction model as taxonomy data (Phase E). NULL = fall back to display_name.';

-- The V10/V12/V18/V21/V25 late-seed dance: document_type has FORCE ROW LEVEL SECURITY (V6) and
-- its write policies admit only org_id = current_org(), which the global (org_id NULL) rows can
-- never satisfy. Drop FORCE for the duration, restore before commit; a forgotten restore fails
-- RlsCoverageIT.
ALTER TABLE document_type NO FORCE ROW LEVEL SECURITY;

UPDATE document_type SET split_description =
    'A wage statement for one pay period: employer and employee blocks, pay-period dates, '
    'current and year-to-date earnings, taxes and deductions; usually one or two pages per stub.',
    updated_at = now()
 WHERE org_id IS NULL AND code = 'PAYSTUB';

UPDATE document_type SET split_description =
    'IRS Form W-2 Wage and Tax Statement: a boxed one-page federal form with numbered wage and '
    'withholding boxes and employer EIN / employee SSN blocks; each copy is its own document.',
    updated_at = now()
 WHERE org_id IS NULL AND code = 'W2';

UPDATE document_type SET split_description =
    'A bank account statement for ONE period: bank letterhead, account number, statement-period '
    'dates, beginning and ending balances, then a dated transaction table; a new period starts a '
    'new statement.',
    updated_at = now()
 WHERE org_id IS NULL AND code = 'BANK_STATEMENT';

UPDATE document_type SET split_description =
    'A photocopied or photographed driver''s license card: portrait photo, issuing-state header, '
    'license number, birth / issue / expiration dates; a single small page.',
    updated_at = now()
 WHERE org_id IS NULL AND code = 'DRIVERS_LICENSE';

UPDATE document_type SET split_description =
    'A monthly mortgage servicing statement: servicer letterhead, loan number, payment breakdown '
    '(principal, interest, escrow) and an amount-due box.',
    updated_at = now()
 WHERE org_id IS NULL AND code = 'MORTGAGE_STATEMENT';

UPDATE document_type SET split_description =
    'A homeowners insurance declarations page: carrier letterhead, policy number and period, '
    'insured property address, coverage and premium table.',
    updated_at = now()
 WHERE org_id IS NULL AND code = 'HOI_DECLARATION';

UPDATE document_type SET split_description =
    'A residential purchase agreement: numbered contract clauses, property address, buyer and '
    'seller names, purchase price and signature blocks; one agreement commonly spans many pages '
    'that belong together.',
    updated_at = now()
 WHERE org_id IS NULL AND code = 'PURCHASE_CONTRACT';

UPDATE document_type SET split_description =
    'IRS Form 1040 with its schedules and attachments: form-numbered federal tax pages with '
    'line-numbered entries; one return spans many pages that all belong to the same document.',
    updated_at = now()
 WHERE org_id IS NULL AND code = 'TAX_RETURN';

UPDATE document_type SET split_description =
    'IRS Schedule E (Form 1040) Supplemental Income and Loss: property columns A-C with '
    'line-numbered rental income and expense rows; each Schedule E form is its own document.',
    updated_at = now()
 WHERE org_id IS NULL AND code = 'SCHEDULE_E';

UPDATE document_type SET split_description =
    'No recognized type: use when a new document clearly starts here but its kind matches none '
    'of the other types.',
    updated_at = now()
 WHERE org_id IS NULL AND code = 'UNKNOWN';

ALTER TABLE document_type FORCE ROW LEVEL SECURITY;
