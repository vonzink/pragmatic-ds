package com.pragmaticds.docengine.platform.ai;

import com.pragmaticds.docengine.platform.ai.BankStatementExtraction.MoneyCell;
import com.pragmaticds.docengine.platform.ai.BankStatementExtraction.TextCell;

/**
 * Strict typed representation of a validated W-2 extraction. Components mirror the seeded W2
 * extraction schema (w2@1.3.0, V47) by name — an AI value lands on the deterministic row with the
 * same name — with ONE deliberate omission: {@code employeeSsn}. It is the schema's only sensitive
 * field; the rules extractor keeps sole ownership of it so it never appears in model output, logs
 * or the interpretation ledger.
 *
 * <p>{@code employerEin} carries the canonical {@code NN-NNNNNNN} and {@code taxYear} a bare
 * four-digit year — the exact strings the rules rows store (neither has a normalizer), so
 * agreement/conflict compares like with like.
 */
public record W2Extraction(
        TextCell employeeName,
        TextCell employerName,
        TextCell employerEin,
        TextCell taxYear,
        MoneyCell wagesTipsOtherComp,
        MoneyCell federalIncomeTaxWithheld,
        MoneyCell socialSecurityWages,
        MoneyCell medicareWages,
        MoneyCell stateWages)
        implements AiStructuredExtraction {

    @Override
    public AiDocumentType documentType() {
        return AiDocumentType.W2;
    }
}
