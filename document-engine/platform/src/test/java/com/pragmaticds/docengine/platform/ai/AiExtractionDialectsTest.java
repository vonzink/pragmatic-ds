package com.pragmaticds.docengine.platform.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** The registry serves each type its own schema, prompt, and parser — never a neighbor's. */
class AiExtractionDialectsTest {

    @Test
    void every_ai_document_type_has_a_dialect_and_they_do_not_bleed() {
        assertThat(AiExtractionDialects.all()).hasSize(AiDocumentType.values().length);

        AiExtractionDialect bank = AiExtractionDialects.forType(AiDocumentType.BANK_STATEMENT);
        AiExtractionDialect paystub = AiExtractionDialects.forType(AiDocumentType.PAYSTUB);

        assertThat(bank.schemaJson()).contains("\"transactions\"").doesNotContain("payFrequency");
        assertThat(paystub.schemaJson())
                .contains("\"payFrequency\"")
                .doesNotContain("transactions");
        assertThat(bank.cachedPrefix()).contains("bank statement", "STRICT OUTPUT SCHEMA");
        assertThat(paystub.cachedPrefix()).contains("paystub", "STRICT OUTPUT SCHEMA");

        AiExtractionDialect w2 = AiExtractionDialects.forType(AiDocumentType.W2);
        assertThat(w2.schemaJson())
                .contains("\"wagesTipsOtherComp\"", "\"employerEin\"")
                .doesNotContain("employeeSsn", "payFrequency", "transactions");
        assertThat(w2.cachedPrefix()).contains("W-2", "Box 1", "STRICT OUTPUT SCHEMA");
        // The SSN must not be modelled anywhere in what the provider sees: no key, no example.
        assertThat(w2.cachedPrefix()).doesNotContain("employeeSsn").doesNotContainPattern("\\d{3}-\\d{2}-\\d{4}");
        assertThat(paystub.schemaJson()).doesNotContain("wagesTipsOtherComp");
    }

    @Test
    void the_w2_dialect_refuses_a_paystub_shaped_response() {
        AiExtractionDialect w2 = AiExtractionDialects.forType(AiDocumentType.W2);
        AiExtractionResult paystubShaped =
                new AiExtractionResult(
                        "{\"borrowerName\":{},\"payFrequency\":{}}",
                        "p",
                        "m",
                        AiExtractionStatus.OK,
                        AiTokenCounts.ZERO,
                        null);

        assertThat(w2.parse(paystubShaped).status()).isEqualTo(AiExtractionStatus.ERROR);
    }

    @Test
    void each_dialect_parses_only_its_own_shape() {
        AiExtractionDialect paystub = AiExtractionDialects.forType(AiDocumentType.PAYSTUB);
        AiExtractionResult bankShaped =
                new AiExtractionResult(
                        "{\"summary\":{},\"transactions\":[],\"checks\":[]}",
                        "p",
                        "m",
                        AiExtractionStatus.OK,
                        AiTokenCounts.ZERO,
                        null);

        AiExtractionResult refused = paystub.parse(bankShaped);

        assertThat(refused.status()).isEqualTo(AiExtractionStatus.ERROR);
        assertThat(refused.reason()).isEqualTo("validation_error");
    }

    @Test
    void a_type_without_a_dialect_is_a_wiring_bug_not_a_soft_failure() {
        assertThatThrownBy(() -> AiExtractionDialects.forType(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
