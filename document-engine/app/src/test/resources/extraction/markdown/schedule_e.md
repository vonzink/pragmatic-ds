---
generator: pds-document-engine
mdContract: DOCENGINE-MD-1/1.2.0
source: "read model, identified by documentId + schemaVersion; includes human corrections; not envelope-hash-backed"
packageId: uuid-1
documentId: uuid-2
documentOrdinal: 0
documentTypeCode: SCHEDULE_E
schemaVersion: 1.0.1
occurrences: 67
pages: [1, 2]
classification:
  - { page: 1, type: SCHEDULE_E, confidence: 1.0000, rulePackVersion: 1.0.0 }
  - { page: 2, type: SCHEDULE_E, confidence: 0.9000, rulePackVersion: 1.0.0 }
---

# SCHEDULE_E — document 0

## Document fields

| Field | Value | Text | Confidence | Page |
|---|---|---|---|---|
| `estateAndTrustTotal` | 13,075 = 13075 | NATIVE | 0.8100 | 2 |
| `partnershipAndSCorpTotal` | 50,465 = 50465 | NATIVE | 0.8100 | 2 |
| `remicTotal` | 5,600 = 5600 | NATIVE | 0.8100 | 2 |
| `taxpayerName` | Jordan Q. Fixture and Casey Reese Fixture | NATIVE | 0.9000 | 1 |
| `taxpayerSsn` (sensitive, masked) | •••-••-4321 | NATIVE | 0.9000 | 1 |
| `taxYear` | 2025 | NATIVE | 0.9000 | 1 |
| `totalIncomeOrLoss` | 63,530 = 63530 | NATIVE | 0.8100 | 2 |
| `totalRentalRealEstateIncomeOrLoss` | (5,610) = -5610 | NATIVE | 0.8100 | 1 |

## Group A–C (3) — COLUMN group

| Field | A | B | C |
|---|---|---|---|
| `depreciationExpense` | 8,900 = 8900 (0.8100, p1, NATIVE) | 7,150 = 7150 (0.8100, p1, NATIVE) | — missing (review) |
| `incomeOrLoss` | 12,860 = 12860 (0.8100, p1, NATIVE) | ( 18,470 ) = -18470 (0.8100, p1, NATIVE) | — missing (review) |
| `mortgageInterest` | 13,260 = 13260 (0.8100, p1, NATIVE) | 10,415 = 10415 (0.8100, p1, NATIVE) | — missing (review) |
| `rentsReceived` | 44,400 = 44400 (0.8100, p1, NATIVE) | 29,700 = 29700 (0.8100, p1, NATIVE) | — missing (review) |
| `totalExpenses` | 31,540 = 31540 (0.8100, p1, NATIVE) | 48,170 = 48170 (0.8100, p1, NATIVE) | — missing (review) |

## Estate Or Trust A–B (2) — ROW group

| Key | `estateOrTrustDeductionOrLoss` | `estateOrTrustName` | `estateOrTrustOtherIncome` | `estateOrTrustPassiveDeductionOrLoss` | `estateOrTrustPassiveIncome` |
|---|---|---|---|---|---|
| A | 2,100 = 2100 (0.8100, p2, NATIVE) | O'BRIEN FAMILY TRUST (0.9000, p2, NATIVE) | 9,850 = 9850 (0.8100, p2, NATIVE) | 3,150 = 3150 (0.8100, p2, NATIVE) | 5,400 = 5400 (0.8100, p2, NATIVE) |
| B | 1,450 = 1450 (0.8100, p2, NATIVE) | Meridian Fixture Estate (0.9000, p2, NATIVE) | 3,375 = 3375 (0.8100, p2, NATIVE) | 1,100 = 1100 (0.8100, p2, NATIVE) | 2,250 = 2250 (0.8100, p2, NATIVE) |

## Partnership A–D (4) — ROW group

| Key | `partnershipEin` | `partnershipName` | `partnershipNonpassiveIncome` | `partnershipNonpassiveLossAllowed` | `partnershipPassiveIncome` | `partnershipPassiveLossAllowed` | `partnershipSection179Expense` |
|---|---|---|---|---|---|---|---|
| A | 27-1234567 (0.9000, p2, NATIVE) | SUMMIT RIDGE PARTNERS LP (0.9000, p2, NATIVE) | 42,150 = 42150 (0.8100, p2, NATIVE) | — missing (review) | 4,200 = 4200 (0.8100, p2, NATIVE) | 2,400 = 2400 (0.8100, p2, NATIVE) | 1,150 = 1150 (0.8100, p2, NATIVE) |
| B | 84-7654321 (0.9000, p2, NATIVE) | 1ST CHOICE PROPERTIES LLC (0.9000, p2, NATIVE) | 18,725 = 18725 (0.8100, p2, NATIVE) | ($6,310) = -6310 (0.8100, p2, NATIVE) | — missing (review) | 3,800 = 3800 (0.8100, p2, NATIVE) | 950.00 = 950 (0.9000, p2, NATIVE) |
| C | — missing (review) | — missing (review) | — missing (review) | — missing (review) | — missing (review) | — missing (review) | — missing (review) |
| D | — missing (review) | — missing (review) | — missing (review) | — missing (review) | — missing (review) | — missing (review) | — missing (review) |

## Property Address 01–03 (3) — ROW group

| Key | `propertyAddress` |
|---|---|
| 01 | 1234 SYNTHETIC AVE, DENVER, CO 80202 (0.9000, p1, NATIVE) |
| 02 | 5678 SAMPLE ST, AURORA, CO 80014 (0.9000, p1, NATIVE) |
| 03 | — missing (review) |

## Remic 01 (1) — ROW group

| Key | `remicIncome` | `remicName` |
|---|---|---|
| 01 | 5,600 = 5600 (0.8100, p2, NATIVE) | McALLISTER REMIC TRUST (0.9000, p2, NATIVE) |

## Region not read

> **`remicExcessInclusion`** — the engine located no readable ROW group region for this field and recorded one explicitly-missing occurrence with no group key. Manual review required. This is a GROUPED field, not a document-level field.

## Occurrence detail

Read-model order: field name ascending, then group key ascending with a null key first. Key `∅` is a null key. Components are span · anchor · normalizer; their product is the confidence. `Text` is where the value's characters came from — `NATIVE` from the PDF's own text layer, `OCR <engine>` recognised from pixels, `MIXED <engine>` when one value came from both, `UNKNOWN` when no text span backs it. It is NOT a confidence component. Pages are 1-based.

| Field | Key | Kind | Status | Value | Normalized | Method | Text | Confidence | Validation | Page |
|---|---|---|---|---|---|---|---|---|---|---|
| depreciationExpense | A | COLUMN | FOUND | 8,900 | 8900 | ANCHOR_LABEL | NATIVE | 0.8100 (1 · 0.9 · 0.9) | NOT_VALIDATED | 1 |
| depreciationExpense | B | COLUMN | FOUND | 7,150 | 7150 | ANCHOR_LABEL | NATIVE | 0.8100 (1 · 0.9 · 0.9) | NOT_VALIDATED | 1 |
| depreciationExpense | C | COLUMN | MISSING | — | — | NONE | UNKNOWN | 0.0000 (—) | MANUAL_REVIEW_REQUIRED | — |
| estateAndTrustTotal | ∅ | NONE | FOUND | 13,075 | 13075 | ANCHOR_LABEL | NATIVE | 0.8100 (1 · 0.9 · 0.9) | NOT_VALIDATED | 2 |
| estateOrTrustDeductionOrLoss | A | ROW | FOUND | 2,100 | 2100 | ROW_CELL | NATIVE | 0.8100 (1 · 0.9 · 0.9) | NOT_VALIDATED | 2 |
| estateOrTrustDeductionOrLoss | B | ROW | FOUND | 1,450 | 1450 | ROW_CELL | NATIVE | 0.8100 (1 · 0.9 · 0.9) | NOT_VALIDATED | 2 |
| estateOrTrustName | A | ROW | FOUND | O'BRIEN FAMILY TRUST | O'BRIEN FAMILY TRUST | ROW_CELL | NATIVE | 0.9000 (1 · 0.9 · 1) | NOT_VALIDATED | 2 |
| estateOrTrustName | B | ROW | FOUND | Meridian Fixture Estate | Meridian Fixture Estate | ROW_CELL | NATIVE | 0.9000 (1 · 0.9 · 1) | NOT_VALIDATED | 2 |
| estateOrTrustOtherIncome | A | ROW | FOUND | 9,850 | 9850 | ROW_CELL | NATIVE | 0.8100 (1 · 0.9 · 0.9) | NOT_VALIDATED | 2 |
| estateOrTrustOtherIncome | B | ROW | FOUND | 3,375 | 3375 | ROW_CELL | NATIVE | 0.8100 (1 · 0.9 · 0.9) | NOT_VALIDATED | 2 |
| estateOrTrustPassiveDeductionOrLoss | A | ROW | FOUND | 3,150 | 3150 | ROW_CELL | NATIVE | 0.8100 (1 · 0.9 · 0.9) | NOT_VALIDATED | 2 |
| estateOrTrustPassiveDeductionOrLoss | B | ROW | FOUND | 1,100 | 1100 | ROW_CELL | NATIVE | 0.8100 (1 · 0.9 · 0.9) | NOT_VALIDATED | 2 |
| estateOrTrustPassiveIncome | A | ROW | FOUND | 5,400 | 5400 | ROW_CELL | NATIVE | 0.8100 (1 · 0.9 · 0.9) | NOT_VALIDATED | 2 |
| estateOrTrustPassiveIncome | B | ROW | FOUND | 2,250 | 2250 | ROW_CELL | NATIVE | 0.8100 (1 · 0.9 · 0.9) | NOT_VALIDATED | 2 |
| incomeOrLoss | A | COLUMN | FOUND | 12,860 | 12860 | ANCHOR_LABEL | NATIVE | 0.8100 (1 · 0.9 · 0.9) | NOT_VALIDATED | 1 |
| incomeOrLoss | B | COLUMN | FOUND | ( 18,470 ) | -18470 | ANCHOR_LABEL | NATIVE | 0.8100 (1 · 0.9 · 0.9) | NOT_VALIDATED | 1 |
| incomeOrLoss | C | COLUMN | MISSING | — | — | NONE | UNKNOWN | 0.0000 (—) | MANUAL_REVIEW_REQUIRED | — |
| mortgageInterest | A | COLUMN | FOUND | 13,260 | 13260 | ANCHOR_LABEL | NATIVE | 0.8100 (1 · 0.9 · 0.9) | NOT_VALIDATED | 1 |
| mortgageInterest | B | COLUMN | FOUND | 10,415 | 10415 | ANCHOR_LABEL | NATIVE | 0.8100 (1 · 0.9 · 0.9) | NOT_VALIDATED | 1 |
| mortgageInterest | C | COLUMN | MISSING | — | — | NONE | UNKNOWN | 0.0000 (—) | MANUAL_REVIEW_REQUIRED | — |
| partnershipAndSCorpTotal | ∅ | NONE | FOUND | 50,465 | 50465 | ANCHOR_LABEL | NATIVE | 0.8100 (1 · 0.9 · 0.9) | NOT_VALIDATED | 2 |
| partnershipEin | A | ROW | FOUND | 27-1234567 | 27-1234567 | ROW_CELL | NATIVE | 0.9000 (1 · 0.9 · 1) | NOT_VALIDATED | 2 |
| partnershipEin | B | ROW | FOUND | 84-7654321 | 84-7654321 | ROW_CELL | NATIVE | 0.9000 (1 · 0.9 · 1) | NOT_VALIDATED | 2 |
| partnershipEin | C | ROW | MISSING | — | — | NONE | UNKNOWN | 0.0000 (—) | MANUAL_REVIEW_REQUIRED | — |
| partnershipEin | D | ROW | MISSING | — | — | NONE | UNKNOWN | 0.0000 (—) | MANUAL_REVIEW_REQUIRED | — |
| partnershipName | A | ROW | FOUND | SUMMIT RIDGE PARTNERS LP | SUMMIT RIDGE PARTNERS LP | ROW_CELL | NATIVE | 0.9000 (1 · 0.9 · 1) | NOT_VALIDATED | 2 |
| partnershipName | B | ROW | FOUND | 1ST CHOICE PROPERTIES LLC | 1ST CHOICE PROPERTIES LLC | ROW_CELL | NATIVE | 0.9000 (1 · 0.9 · 1) | NOT_VALIDATED | 2 |
| partnershipName | C | ROW | MISSING | — | — | NONE | UNKNOWN | 0.0000 (—) | MANUAL_REVIEW_REQUIRED | — |
| partnershipName | D | ROW | MISSING | — | — | NONE | UNKNOWN | 0.0000 (—) | MANUAL_REVIEW_REQUIRED | — |
| partnershipNonpassiveIncome | A | ROW | FOUND | 42,150 | 42150 | ROW_CELL | NATIVE | 0.8100 (1 · 0.9 · 0.9) | NOT_VALIDATED | 2 |
| partnershipNonpassiveIncome | B | ROW | FOUND | 18,725 | 18725 | ROW_CELL | NATIVE | 0.8100 (1 · 0.9 · 0.9) | NOT_VALIDATED | 2 |
| partnershipNonpassiveIncome | C | ROW | MISSING | — | — | NONE | UNKNOWN | 0.0000 (—) | MANUAL_REVIEW_REQUIRED | — |
| partnershipNonpassiveIncome | D | ROW | MISSING | — | — | NONE | UNKNOWN | 0.0000 (—) | MANUAL_REVIEW_REQUIRED | — |
| partnershipNonpassiveLossAllowed | A | ROW | MISSING | — | — | NONE | UNKNOWN | 0.0000 (—) | MANUAL_REVIEW_REQUIRED | — |
| partnershipNonpassiveLossAllowed | B | ROW | FOUND | ($6,310) | -6310 | ROW_CELL | NATIVE | 0.8100 (1 · 0.9 · 0.9) | NOT_VALIDATED | 2 |
| partnershipNonpassiveLossAllowed | C | ROW | MISSING | — | — | NONE | UNKNOWN | 0.0000 (—) | MANUAL_REVIEW_REQUIRED | — |
| partnershipNonpassiveLossAllowed | D | ROW | MISSING | — | — | NONE | UNKNOWN | 0.0000 (—) | MANUAL_REVIEW_REQUIRED | — |
| partnershipPassiveIncome | A | ROW | FOUND | 4,200 | 4200 | ROW_CELL | NATIVE | 0.8100 (1 · 0.9 · 0.9) | NOT_VALIDATED | 2 |
| partnershipPassiveIncome | B | ROW | MISSING | — | — | NONE | UNKNOWN | 0.0000 (—) | MANUAL_REVIEW_REQUIRED | — |
| partnershipPassiveIncome | C | ROW | MISSING | — | — | NONE | UNKNOWN | 0.0000 (—) | MANUAL_REVIEW_REQUIRED | — |
| partnershipPassiveIncome | D | ROW | MISSING | — | — | NONE | UNKNOWN | 0.0000 (—) | MANUAL_REVIEW_REQUIRED | — |
| partnershipPassiveLossAllowed | A | ROW | FOUND | 2,400 | 2400 | ROW_CELL | NATIVE | 0.8100 (1 · 0.9 · 0.9) | NOT_VALIDATED | 2 |
| partnershipPassiveLossAllowed | B | ROW | FOUND | 3,800 | 3800 | ROW_CELL | NATIVE | 0.8100 (1 · 0.9 · 0.9) | NOT_VALIDATED | 2 |
| partnershipPassiveLossAllowed | C | ROW | MISSING | — | — | NONE | UNKNOWN | 0.0000 (—) | MANUAL_REVIEW_REQUIRED | — |
| partnershipPassiveLossAllowed | D | ROW | MISSING | — | — | NONE | UNKNOWN | 0.0000 (—) | MANUAL_REVIEW_REQUIRED | — |
| partnershipSection179Expense | A | ROW | FOUND | 1,150 | 1150 | ROW_CELL | NATIVE | 0.8100 (1 · 0.9 · 0.9) | NOT_VALIDATED | 2 |
| partnershipSection179Expense | B | ROW | FOUND | 950.00 | 950 | ROW_CELL | NATIVE | 0.9000 (1 · 0.9 · 1) | NOT_VALIDATED | 2 |
| partnershipSection179Expense | C | ROW | MISSING | — | — | NONE | UNKNOWN | 0.0000 (—) | MANUAL_REVIEW_REQUIRED | — |
| partnershipSection179Expense | D | ROW | MISSING | — | — | NONE | UNKNOWN | 0.0000 (—) | MANUAL_REVIEW_REQUIRED | — |
| propertyAddress | 01 | ROW | FOUND | 1234 SYNTHETIC AVE, DENVER, CO 80202 | 1234 SYNTHETIC AVE, DENVER, CO 80202 | ROW_CELL | NATIVE | 0.9000 (1 · 0.9 · 1) | NOT_VALIDATED | 1 |
| propertyAddress | 02 | ROW | FOUND | 5678 SAMPLE ST, AURORA, CO 80014 | 5678 SAMPLE ST, AURORA, CO 80014 | ROW_CELL | NATIVE | 0.9000 (1 · 0.9 · 1) | NOT_VALIDATED | 1 |
| propertyAddress | 03 | ROW | MISSING | — | — | NONE | UNKNOWN | 0.0000 (—) | MANUAL_REVIEW_REQUIRED | — |
| remicExcessInclusion | ∅ | ROW | MISSING — region not read | — | — | NONE | UNKNOWN | 0.0000 (—) | MANUAL_REVIEW_REQUIRED | — |
| remicIncome | 01 | ROW | FOUND | 5,600 | 5600 | ROW_CELL | NATIVE | 0.8100 (1 · 0.9 · 0.9) | NOT_VALIDATED | 2 |
| remicName | 01 | ROW | FOUND | McALLISTER REMIC TRUST | McALLISTER REMIC TRUST | ROW_CELL | NATIVE | 0.9000 (1 · 0.9 · 1) | NOT_VALIDATED | 2 |
| remicTotal | ∅ | NONE | FOUND | 5,600 | 5600 | ANCHOR_LABEL | NATIVE | 0.8100 (1 · 0.9 · 0.9) | NOT_VALIDATED | 2 |
| rentsReceived | A | COLUMN | FOUND | 44,400 | 44400 | ANCHOR_LABEL | NATIVE | 0.8100 (1 · 0.9 · 0.9) | NOT_VALIDATED | 1 |
| rentsReceived | B | COLUMN | FOUND | 29,700 | 29700 | ANCHOR_LABEL | NATIVE | 0.8100 (1 · 0.9 · 0.9) | NOT_VALIDATED | 1 |
| rentsReceived | C | COLUMN | MISSING | — | — | NONE | UNKNOWN | 0.0000 (—) | MANUAL_REVIEW_REQUIRED | — |
| taxpayerName | ∅ | NONE | FOUND | Jordan Q. Fixture and Casey Reese Fixture | Jordan Q. Fixture and Casey Reese Fixture | LABEL_BELOW | NATIVE | 0.9000 (1 · 0.9 · 1) | NOT_VALIDATED | 1 |
| taxpayerSsn | ∅ | NONE | FOUND | •••-••-4321 | •••-••-4321 | LABEL_BELOW | NATIVE | 0.9000 (1 · 0.9 · 1) | NOT_VALIDATED | 1 |
| taxYear | ∅ | NONE | FOUND | 2025 | 2025 | ANCHOR_LABEL | NATIVE | 0.9000 (1 · 0.9 · 1) | NOT_VALIDATED | 1 |
| totalExpenses | A | COLUMN | FOUND | 31,540 | 31540 | ANCHOR_LABEL | NATIVE | 0.8100 (1 · 0.9 · 0.9) | NOT_VALIDATED | 1 |
| totalExpenses | B | COLUMN | FOUND | 48,170 | 48170 | ANCHOR_LABEL | NATIVE | 0.8100 (1 · 0.9 · 0.9) | NOT_VALIDATED | 1 |
| totalExpenses | C | COLUMN | MISSING | — | — | NONE | UNKNOWN | 0.0000 (—) | MANUAL_REVIEW_REQUIRED | — |
| totalIncomeOrLoss | ∅ | NONE | FOUND | 63,530 | 63530 | ANCHOR_LABEL | NATIVE | 0.8100 (1 · 0.9 · 0.9) | NOT_VALIDATED | 2 |
| totalRentalRealEstateIncomeOrLoss | ∅ | NONE | FOUND | (5,610) | -5610 | ANCHOR_LABEL | NATIVE | 0.8100 (1 · 0.9 · 0.9) | NOT_VALIDATED | 1 |

---

Rendered by DOCENGINE-MD-1/1.2.0 from the document read model — document `uuid-2`, schema version 1.0.1.
That read model overlays human corrections, so this is the CURRENT REVIEWED state, not a frozen machine parse.
There is no envelope hash here and no integrity claim: to cite an immutable parse, pin an engine-result revision instead.
Sensitive values are masked and cannot be unmasked through this surface. Pages are 1-based here; evidence JSON is 0-based.
