-- V51 — five triage-only types: pages a lender package carries that must be RECOGNISED and
-- SORTED, and that hold no income data of their own.
--
-- A fax cover, an e-file signature authorization, a preparer's filing letter, a page of the
-- initial disclosure package and a page of the closing package match no pack today. They
-- classify UNKNOWN, PackageSplitter's continuation rule (V46) glues them onto the typed
-- document in front of them, and every one of them is OCR'd and run through extraction for
-- nothing. These types exist so a page can be named; a separate change will skip the
-- expensive OCR/extraction for them.
--
--   §1 document_type: five rows.
--        FAX_COVER_SHEET, LOAN_DISCLOSURE_PACKAGE, CLOSING_PACKAGE  category LOAN — the
--          shelf V33/V34 gave CLOSING_DISCLOSURE, URLA and FORM_4506: paper of the loan
--          transaction itself, addressed to or issued by the lender. Nothing branches on
--          category (it is a label), so a fax cover sits here rather than in a new one.
--        EFILE_AUTHORIZATION, TAX_PREPARER_LETTER  category INCOME — the shelf V46 gave
--          SCHEDULE_1/2, FORM_8962 and STATE_TAX_RETURN: pages that travel inside a tax
--          return's run.
--   §2 classification_rule_pack: one pack each, 1.0.0, min_confidence 0.60, targetScore 10,
--      ONE startsDocument anchor each, on the printed title.
--   NO §3. These types carry NO extraction schema, deliberately: there is nothing on them
--      to extract, and FieldExtractionStage already skips a document whose type has no
--      schema (FieldExtractionStageIT#documents_without_a_schema_are_skipped_not_failed).
--      ExtractionEvalIT's corpus gate covers "every type with a seeded extraction schema",
--      so no eval case is owed; ConsumerCoverageTableIT reads a schema-less row as "—".
--
-- ── WHY THE BAR IS HIGHER HERE THAN FOR ANY OTHER PACK ─────────────────────────────
--
-- A false positive on an income pack mislabels a page. A false positive HERE will cause the
-- page to be SKIPPED — its income data silently never read. So every pack below holds the
-- V10 invariant as a hard rule, and CrossConfusionIT + TriageOnlyTypesIT pin it:
--
--   every SHARED anchor of a pack, matching at once, sums to < 6 = under the 0.60 bar.
--
-- A pack can therefore only qualify on at least one phrase that no income document prints.
-- Weight is EXCLUSIVITY (V10), not salience. Every title anchor is CASE-SENSITIVE Title Case
-- or ALL CAPS (the V46 s2-title / V50 pt-title lesson): on an UNKNOWN page the splitter cuts
-- at ANY startsDocument anchor whether or not its pack won, so prose ("sign your Form 8879",
-- "your settlement statement") must not start a document. Nothing is tied to one lender,
-- preparer, county or state.
--
-- The words the income neighbours print, and why none of them is an anchor worth anything:
--   "Form 8879"        a preparer letter and a 1040 package cite it. ef-form = 2 (a reference).
--   "Note"             Schedule C, K-1s and 1040 instructions print "Note:". Never anchored bare;
--                      only "PROMISSORY NOTE" / "FIXED|ADJUSTABLE RATE NOTE" titles and the
--                      Note's own first covenant sentence.
--   "Mortgage"         Form 1098, mortgage statements, a lender's name. Never anchored bare —
--                      not even the CAPS security-instrument title "MORTGAGE", which
--                      "FIRST ... MORTGAGE LLC" and "MORTGAGE STATEMENT" would match.
--   "Balance due"      a state return line and a 1040-V voucher; the 1040 says "Amount you owe".
--                      tpl-amount = 1.
--   "Loan Estimate"    the Closing Disclosure's comparison columns print it. ldp-le-title = 2.
--   "Uniform Residential Loan Application"  NOT anchored at all, though a disclosure package
--                      carries one: URLA is its own type (V34), its fixture must not qualify
--                      this pack (CrossConfusionIT), and the 1003 carries the borrower's
--                      employment and income — it must never be sorted into a skipped type.
--   "Fax:" / "Re:"     every letterhead, VOE and SSA letter. 1 each.
--
-- ── FAX_COVER_SHEET 1.0.0 ───────────────────────────────────────────────────────────
--   fax-title        Fax|Facsimile Cover Sheet|Page|Letter / Transmittal [Sheet], Title or CAPS
--                                                                 6  EXCLUSIVE; startsDocument
--   fax-pages        "pages (including cover)" and its variants    4  EXCLUSIVE
--   fax-error        "received this fax|transmission in error"      3  EXCLUSIVE (fax disclaimer)
--   fax-checkboxes   "Please Comment|Please Recycle"               3  EXCLUSIVE (cover checkboxes)
--   fax-count        "Number of pages|Total pages"                 1  SHARED
--   fax-number       "Fax:|Fax No.:"                               1  SHARED (letterheads, VOE)
--   fax-re           "Re:|Subject:"                                1  SHARED (letters)
--   Shared sum 3 = 0.30. Fixture 19 -> 1.00.
--
-- ── EFILE_AUTHORIZATION 1.0.0 ───────────────────────────────────────────────────────
--   ef-title         "[IRS|state] e-file Signature Authorization" (8879 family, 8878, state
--                    equivalents) | "Transmittal for an IRS e-file Return" (8453)
--                                                                 6  EXCLUSIVE; startsDocument
--   ef-declaration   "Declaration of|for Electronic Filing" (state 8453s), "Taxpayer
--                    Declaration and Signature Authorization" — Title/CAPS only
--                                                                 4  EXCLUSIVE
--   ef-pin           "ERO's EFIN/PIN", "Practitioner PIN", "Self-Select PIN", "enter or
--                    generate my PIN", "as my signature on my"    3  EXCLUSIVE
--   ef-consent       "I consent to allow my ERO|transmitter|…"     3  EXCLUSIVE
--   ef-form          Form|FTB|DR|two-letter state prefix + 8879|8878|8453
--                                                                 2  SHARED (cited by letters)
--   ef-efin          "EFIN"                                        1  SHARED
--   Shared sum 3 = 0.30. A state "Declaration for Electronic Filing (DR 8453)" = 4+2 = 0.60.
--   The 8879 repeats AGI, total tax, withholding and refund from the return it authorizes;
--   skipping it loses nothing the return itself does not carry.
--
-- ── TAX_PREPARER_LETTER 1.0.0 ───────────────────────────────────────────────────────
-- Wording varies more here than anywhere, so the vocabulary is split hard: three exclusive
-- anchors, and five shared ones at weight 1 each (the voucher, the state balance-due line and
-- the "CLIENT COPY" banner preparer software stamps on EVERY page of a return copy all live
-- in the shared set).
--   tpl-title        Filing Instructions | Engagement Letter | Tax Organizer, Title/CAPS
--                                                                 5  EXCLUSIVE; startsDocument
--   tpl-prepared     "your [2025] [federal and state] return(s) has|have been prepared|filed"
--                                                                 4  EXCLUSIVE
--   tpl-engage       "thank you for choosing us", "this letter confirms our understanding",
--                    "we will prepare your"                       3  EXCLUSIVE
--   tpl-mail         "mail your payment|return|voucher to"         1  SHARED (1040-V, vouchers)
--   tpl-deadline     "postmarked by|on or before April 15", "estimated tax vouchers"
--                                                                 1  SHARED (1040-ES)
--   tpl-enclosed     "Enclosed are|is|please find"                 1  SHARED (any cover letter)
--   tpl-amount       "Balance due", "Amount of refund"             1  SHARED (state return lines)
--   tpl-copy         "Client Copy|Taxpayer Copy"                   1  SHARED (every page of a
--                                                                     preparer's return copy)
--   Shared sum 5 = 0.50. The title alone is 0.50 and does not qualify either: a state return
--   or instructions page headed "Filing Instructions" needs a second, exclusive signal.
--   Fixture 14 -> 1.00.
--
-- ── LOAN_DISCLOSURE_PACKAGE 1.0.0 ───────────────────────────────────────────────────
--   ldp-title        Intent to Proceed | Your Home Loan Toolkit | Right to Receive a Copy of
--                    Appraisal | Initial Escrow Account Disclosure | Affiliated Business
--                    Arrangement Disclosure | Servicing Disclosure Statement | Anti-Coercion |
--                    Borrower's Certification and Authorization, Title/CAPS
--                                                                 6  EXCLUSIVE; startsDocument
--   ldp-le-exclusive consumerfinance.gov/mortgage-estimate, "Save this Loan Estimate",
--                    "Estimated Cash to Close", "Estimated Closing Costs"  4  EXCLUSIVE (LE only;
--                    the CD prints "Cash to Close" and "Closing Costs" unqualified)
--   ldp-intent       "intend to proceed with this loan"            3  EXCLUSIVE
--   ldp-appraisal-copy "copy of any appraisal|written valuation"    3  EXCLUSIVE (ECOA notice)
--   ldp-coercion     "insurance agent of your choice"              3  EXCLUSIVE
--   ldp-le-title     "Loan Estimate", Title/CAPS                   2  SHARED (CD comparison)
--   ldp-servicing    "servicing of your loan may be transferred"   1  SHARED: a mortgage
--                    statement's servicing-transfer notice can print it.
--   ldp-applied      "I have applied for a mortgage loan"          1  SHARED: the VOE (Form 1005)
--                    borrower authorization prints it, and a VOE is an income document.
--   ldp-ecoa         "Equal Credit Opportunity Act"                1  SHARED
--   Shared sum 5 = 0.50. NOT anchored: "RESPA" (every mortgage statement's QWR notice) — it
--   would have made the shared set 6. Fixture 6+3+3+2+1 = 15 -> 1.00; a Loan Estimate page
--   2+4 = 0.60.
--
-- ── CLOSING_PACKAGE 1.0.0 ───────────────────────────────────────────────────────────
-- The Closing Disclosure is NOT here: CLOSING_DISCLOSURE (V33) is untouched and none of these
-- anchors is a CD phrase.
--   cp-title         Compliance Agreement | Errors and Omissions | Name Affidavit | Signature
--                    Affidavit | First Payment Letter | Occupancy Affidavit | Notice of Right
--                    to Cancel | Settlement Statement | DEED OF TRUST | Promissory Note |
--                    FIXED|ADJUSTABLE RATE NOTE, Title/CAPS ("DEED OF TRUST" CAPS only: a
--                    title commitment's requirements cite the "Deed of Trust" in Title Case)
--                                                                 6  EXCLUSIVE; startsDocument
--   cp-note          "In return for a loan that I have received", "BORROWER'S PROMISE TO PAY"
--                                                                 5  EXCLUSIVE (the Note)
--   cp-instrument    "UNIFORM INSTRUMENT", "Security Instrument" means, "TRANSFER OF RIGHTS IN
--                    THE PROPERTY", "UNIFORM COVENANTS"           5  EXCLUSIVE (the instrument)
--   cp-cancel        "legal right under federal law to cancel"     4  EXCLUSIVE (TILA notice)
--   cp-same-person   "one and the same person"                     4  EXCLUSIVE (name affidavit)
--   cp-clerical      "cooperate and adjust for clerical errors"    4  EXCLUSIVE (compliance agmt)
--   cp-occupy        "occupy the property as my principal residence"  3  EXCLUSIVE (the URLA
--                    asks "your primary residence" and does not match)
--   cp-first-payment "your first payment is due"                   3  SHARED: a servicer's
--                    welcome letter or a new loan's first mortgage statement can print it.
--   cp-notary        "Notary Public", "subscribed and sworn"       1  SHARED
--   cp-mers          "Mortgage Electronic Registration Systems"    1  SHARED (commitments)
--   Shared sum 5 = 0.50. Fixture 6+5+5 = 16 -> 1.00.
--
-- ── RLS ─────────────────────────────────────────────────────────────────────────────
-- The same NO FORCE dance as V44, V46 and V50, on two tables this time: each has been FORCEd
-- since V6 and admits only org_id = current_org(), which a global (org_id NULL) row never
-- satisfies. Each restore happens in the SAME transaction; RlsCoverageIT's pg_class sweep fails
-- the build if one is ever forgotten.
--
-- Numbered V51: V50 is the property tax statement type (#83).

-- ── §1 document types ───────────────────────────────────────────────────────────────
ALTER TABLE document_type NO FORCE ROW LEVEL SECURITY;

INSERT INTO document_type (org_id, code, display_name, category, split_description) VALUES
    (NULL, 'FAX_COVER_SHEET', 'Fax Cover Sheet', 'LOAN',
     'A fax or facsimile cover page: sender, recipient, fax numbers, a subject line, the number '
     'of pages including the cover, and often a confidentiality notice; one page, sent in front '
     'of the documents it transmits and a document of its own, never part of them.'),
    (NULL, 'EFILE_AUTHORIZATION', 'E-file Signature Authorization', 'INCOME',
     'An IRS e-file signature authorization (Form 8879 and its 8879-PE/-S/-F siblings, Form 8878, '
     'Form 8453) or a state equivalent (a declaration for electronic filing): the taxpayer''s PIN '
     'authorization and the ERO''s certification, repeating a few totals from the return; one or '
     'two pages, filed alongside the return but a document of its own.'),
    (NULL, 'TAX_PREPARER_LETTER', 'Tax Preparer Letter', 'INCOME',
     'A tax preparer''s transmittal or engagement letter, filing instructions or tax organizer: '
     'how and when to sign, mail or pay, the balance due or refund per return, and the enclosed '
     'client copies; usually in front of the returns it describes and a document of its own.'),
    (NULL, 'LOAN_DISCLOSURE_PACKAGE', 'Loan Disclosure Package', 'LOAN',
     'A page of an initial or preliminary loan disclosure package: the Loan Estimate, intent to '
     'proceed, Home Loan Toolkit, appraisal-copy notice, initial escrow account disclosure, '
     'affiliated business arrangement, servicing disclosure, anti-coercion notice, or the '
     'borrower''s certification and authorization; each notice starts a new document.'),
    (NULL, 'CLOSING_PACKAGE', 'Closing Package', 'LOAN',
     'A page of a closing package other than the Closing Disclosure: the promissory note, deed of '
     'trust or mortgage security instrument, settlement statement, compliance or errors-and-'
     'omissions agreement, name or signature affidavit, occupancy affidavit, first payment letter '
     'or notice of right to cancel; each titled instrument starts a new document.');

ALTER TABLE document_type FORCE ROW LEVEL SECURITY;

-- ── §2 rule packs ───────────────────────────────────────────────────────────────────
ALTER TABLE classification_rule_pack NO FORCE ROW LEVEL SECURITY;

INSERT INTO classification_rule_pack
    (org_id, document_type_code, version, min_confidence, definition) VALUES
(NULL, 'FAX_COVER_SHEET', '1.0.0', 0.6, '{
  "targetScore": 10,
  "anchors": [
    {"id": "fax-title", "kind": "regex", "pattern": "\\b(?:(?:Fax|Facsimile) (?:Cover (?:Sheet|Page|Letter)|Transmittal(?: Sheet)?)|(?:FAX|FACSIMILE) (?:COVER (?:SHEET|PAGE|LETTER)|TRANSMITTAL(?: SHEET)?))\\b", "weight": 6, "startsDocument": true},
    {"id": "fax-pages", "kind": "regex", "pattern": "(?i)\\bpages?:?\\s*\\(?\\s*(?:including|incl\\.?|with|w/)\\s*(?:this\\s+|the\\s+)?cover", "weight": 4},
    {"id": "fax-error", "kind": "regex", "pattern": "(?i)\\breceived this (?:fax|facsimile|telecopy|transmission|transmittal) in error\\b", "weight": 3},
    {"id": "fax-checkboxes", "kind": "regex", "pattern": "(?i)\\bPlease (?:Comment|Recycle)\\b", "weight": 3},
    {"id": "fax-count", "kind": "regex", "pattern": "(?i)\\b(?:number of pages|no\\.? of pages|# of pages|total pages)\\b", "weight": 1},
    {"id": "fax-number", "kind": "regex", "pattern": "(?i)\\b(?:Fax|Facsimile)(?: (?:number|no\\.?|#))?:", "weight": 1},
    {"id": "fax-re", "kind": "regex", "pattern": "\\b(?:Re|RE|Subject|SUBJECT):", "weight": 1}
  ]
}'::jsonb),
(NULL, 'EFILE_AUTHORIZATION', '1.0.0', 0.6, '{
  "targetScore": 10,
  "anchors": [
    {"id": "ef-title", "kind": "regex", "pattern": "\\b(?:(?:e-file|E-file|e-File|E-File|efile|E-Filing|e-Filing|Electronic Filing) Signature Authorization|(?:E-FILE|EFILE|E-FILING|ELECTRONIC FILING) SIGNATURE AUTHORIZATION|Transmittal for an IRS e-file Return|TRANSMITTAL FOR AN IRS E-FILE RETURN)\\b", "weight": 6, "startsDocument": true},
    {"id": "ef-declaration", "kind": "regex", "pattern": "\\b(?:Declaration (?:of|for) Electronic Filing|DECLARATION (?:OF|FOR) ELECTRONIC FILING|Declaration of Electronic Return Originator|Taxpayer Declaration and Signature Authorization|TAXPAYER DECLARATION AND SIGNATURE AUTHORIZATION)\\b", "weight": 4},
    {"id": "ef-pin", "kind": "regex", "pattern": "(?i)\\b(?:ERO''?s (?:EFIN/PIN|PIN|signature)|Practitioner PIN|Self-Select PIN|enter or generate my PIN|as my signature on my)\\b", "weight": 3},
    {"id": "ef-consent", "kind": "regex", "pattern": "(?i)\\b(?:I consent to allow my (?:electronic return originator|ERO|intermediate service provider|transmitter)|consent to electronic funds withdrawal)\\b", "weight": 3},
    {"id": "ef-form", "kind": "regex", "pattern": "\\b(?:Form|FORM|FTB|DR|[A-Z]{2})[ -]?(?:8879|8878|8453)(?:-[A-Z]{1,4})?\\b", "weight": 2},
    {"id": "ef-efin", "kind": "regex", "pattern": "\\bEFIN\\b", "weight": 1}
  ]
}'::jsonb),
(NULL, 'TAX_PREPARER_LETTER', '1.0.0', 0.6, '{
  "targetScore": 10,
  "anchors": [
    {"id": "tpl-title", "kind": "regex", "pattern": "\\b(?:Filing Instructions|FILING INSTRUCTIONS|Engagement Letter|ENGAGEMENT LETTER|Tax Organizer|TAX ORGANIZER)\\b", "weight": 5, "startsDocument": true},
    {"id": "tpl-prepared", "kind": "regex", "pattern": "(?i)\\byour (?:\\d{4} )?(?:(?:federal|state|individual|income|tax|and) )*returns? (?:has|have) been (?:prepared|completed|electronically filed|e-?filed)\\b", "weight": 4},
    {"id": "tpl-engage", "kind": "regex", "pattern": "(?i)\\b(?:thank you for (?:choosing|selecting|engaging) (?:us|our firm)|this letter (?:is to )?confirms? (?:our understanding|the terms)|we will prepare your)\\b", "weight": 3},
    {"id": "tpl-mail", "kind": "regex", "pattern": "(?i)\\bmail (?:your|the|this|these) (?:(?:federal|state|tax|signed|completed) )*(?:returns?|forms?|vouchers?|payments?) to\\b", "weight": 1},
    {"id": "tpl-deadline", "kind": "regex", "pattern": "(?i)\\b(?:(?:no later than|on or before|postmarked by) (?:April|Apr\\.?) 1[5-8]|estimated tax (?:payment )?vouchers?)\\b", "weight": 1},
    {"id": "tpl-enclosed", "kind": "regex", "pattern": "(?i)\\b(?:enclosed (?:are|is|please find)|we have enclosed)\\b", "weight": 1},
    {"id": "tpl-amount", "kind": "regex", "pattern": "(?i)\\b(?:balance due|amount of (?:your )?refund|refund of \\$|(?:are|will be) (?:due|entitled to) a refund)\\b", "weight": 1},
    {"id": "tpl-copy", "kind": "regex", "pattern": "(?i)\\b(?:client|taxpayer)''?s? copy\\b", "weight": 1}
  ]
}'::jsonb),
(NULL, 'LOAN_DISCLOSURE_PACKAGE', '1.0.0', 0.6, '{
  "targetScore": 10,
  "anchors": [
    {"id": "ldp-title", "kind": "regex", "pattern": "\\b(?:Intent to Proceed|INTENT TO PROCEED|Your Home Loan Toolkit|YOUR HOME LOAN TOOLKIT|Right to Receive (?:a )?Copy of (?:the )?Apprais(?:al|als)|RIGHT TO RECEIVE (?:A )?COPY OF (?:THE )?APPRAIS(?:AL|ALS)|Initial Escrow Account Disclosure|INITIAL ESCROW ACCOUNT DISCLOSURE|Affiliated Business Arrangement Disclosure|AFFILIATED BUSINESS ARRANGEMENT DISCLOSURE|Servicing Disclosure Statement|SERVICING DISCLOSURE STATEMENT|Anti-Coercion|ANTI-COERCION|Borrower''?s''? Certification (?:and|&) Authorization|BORROWER''?S''? CERTIFICATION (?:AND|&) AUTHORIZATION)\\b", "weight": 6, "startsDocument": true},
    {"id": "ldp-le-exclusive", "kind": "regex", "pattern": "(?i)(?:consumerfinance\\.gov/mortgage-estimate|\\bSave this Loan Estimate\\b|\\bEstimated Cash to Close\\b|\\bEstimated Closing Costs\\b)", "weight": 4},
    {"id": "ldp-intent", "kind": "regex", "pattern": "(?i)\\b(?:intend|intent|wish) to proceed with (?:this|the|my|our) (?:mortgage )?loan\\b", "weight": 3},
    {"id": "ldp-appraisal-copy", "kind": "regex", "pattern": "(?i)\\bcopy of (?:any|all) (?:appraisals?|(?:other )?written valuations?)\\b", "weight": 3},
    {"id": "ldp-coercion", "kind": "regex", "pattern": "(?i)\\b(?:insurance (?:agent|company|carrier) of (?:your|my|our) (?:own )?choice|not required to (?:obtain|purchase|buy) (?:\\w+ ){0,3}insurance (?:through|from))\\b", "weight": 3},
    {"id": "ldp-le-title", "kind": "regex", "pattern": "\\b(?:Loan Estimate|LOAN ESTIMATE)\\b", "weight": 2},
    {"id": "ldp-servicing", "kind": "regex", "pattern": "(?i)\\b(?:servicing of (?:your|the|this) (?:mortgage )?loan (?:may|will) be (?:assigned|sold|transferred)|we (?:may|will) (?:assign|sell|transfer) the servicing)\\b", "weight": 1},
    {"id": "ldp-applied", "kind": "regex", "pattern": "(?i)\\b(?:I|We|I/We|the undersigned) (?:have )?applied for a (?:residential )?mortgage loan\\b", "weight": 1},
    {"id": "ldp-ecoa", "kind": "regex", "pattern": "(?i)\\bEqual Credit Opportunity Act\\b", "weight": 1}
  ]
}'::jsonb),
(NULL, 'CLOSING_PACKAGE', '1.0.0', 0.6, '{
  "targetScore": 10,
  "anchors": [
    {"id": "cp-title", "kind": "regex", "pattern": "\\b(?:Compliance Agreement|COMPLIANCE AGREEMENT|Errors and Omissions|ERRORS AND OMISSIONS|Name Affidavit|NAME AFFIDAVIT|Signature Affidavit|SIGNATURE AFFIDAVIT|First Payment Letter|FIRST PAYMENT LETTER|Occupancy Affidavit|OCCUPANCY AFFIDAVIT|Notice of Right to Cancel|NOTICE OF RIGHT TO CANCEL|Settlement Statement|SETTLEMENT STATEMENT|DEED OF TRUST|PROMISSORY NOTE|Promissory Note|(?:FIXED|ADJUSTABLE) RATE NOTE)\\b", "weight": 6, "startsDocument": true},
    {"id": "cp-note", "kind": "regex", "pattern": "(?i)\\b(?:In return for a loan that I have received|BORROWER''?S PROMISE TO PAY)\\b", "weight": 5},
    {"id": "cp-instrument", "kind": "regex", "pattern": "(?i)(?:\\bUNIFORM INSTRUMENT\\b|\\bSecurity Instrument\"? means\\b|\\bTRANSFER OF RIGHTS IN THE PROPERTY\\b|\\bUNIFORM COVENANTS\\b)", "weight": 5},
    {"id": "cp-cancel", "kind": "regex", "pattern": "(?i)\\b(?:legal right under federal law to cancel|right to cancel (?:this|the) transaction)\\b", "weight": 4},
    {"id": "cp-same-person", "kind": "regex", "pattern": "(?i)\\bone and the same (?:person|individual)\\b", "weight": 4},
    {"id": "cp-clerical", "kind": "regex", "pattern": "(?i)\\b(?:fully cooperate and adjust|(?:cooperate|agree) (?:and|to) (?:adjust|correct) (?:for )?(?:any )?clerical errors?)\\b", "weight": 4},
    {"id": "cp-first-payment", "kind": "regex", "pattern": "(?i)\\byour first (?:monthly )?(?:mortgage )?payment (?:is|will be) due\\b", "weight": 3},
    {"id": "cp-occupy", "kind": "regex", "pattern": "(?i)\\b(?:occupy|occupied|will occupy) the (?:subject )?property as (?:my|our|the borrower''?s?) principal residence\\b", "weight": 3},
    {"id": "cp-notary", "kind": "regex", "pattern": "(?i)\\b(?:Notary Public|subscribed and sworn|acknowledged before me)\\b", "weight": 1},
    {"id": "cp-mers", "kind": "regex", "pattern": "(?i)\\bMortgage Electronic Registration Systems\\b", "weight": 1}
  ]
}'::jsonb);

ALTER TABLE classification_rule_pack FORCE ROW LEVEL SECURITY;
