package com.pragmaticds.docengine.platform.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;

class StubAiExtractionAdapterTest {

    @Test
    void returns_the_same_disabled_result_for_every_request() {
        AiExtractionPort adapter = new StubAiExtractionAdapter();

        AiExtractionResult first = adapter.extract(syntheticRequest("FAKE ACCOUNT 0001"));
        AiExtractionResult second = adapter.extract(syntheticRequest("FAKE ACCOUNT 0002"));

        assertThat(first).isEqualTo(second);
        assertThat(first.structuredJson()).isNull();
        assertThat(first.provider()).isEqualTo("stub");
        assertThat(first.model()).isEqualTo("stub");
        assertThat(first.status()).isEqualTo(AiExtractionStatus.DISABLED);
        assertThat(first.tokenCounts()).isEqualTo(AiTokenCounts.ZERO);
        assertThat(first.reason()).isEqualTo("disabled");
    }

    @Test
    void never_throws_for_a_null_request() {
        assertThatCode(() -> new StubAiExtractionAdapter().extract(null))
                .doesNotThrowAnyException();
    }

    private static AiExtractionRequest syntheticRequest(String documentText) {
        return new AiExtractionRequest(
                AiDocumentType.BANK_STATEMENT,
                documentText,
                "FAKE TABLE STRUCTURE",
                """
                {
                  "type": "object",
                  "properties": {},
                  "additionalProperties": false
                }
                """);
    }
}
