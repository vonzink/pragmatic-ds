package com.pragmaticds.docengine.platform.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StubPageTypeClassificationAdapterTest {

    @Test
    void returns_the_same_disabled_result_for_every_request() {
        PageTypeClassificationPort adapter = new StubPageTypeClassificationAdapter();

        PageTypeClassificationResult first = adapter.classify(syntheticRequest("FAKE PAGE ONE"));
        PageTypeClassificationResult second = adapter.classify(syntheticRequest("FAKE PAGE TWO"));

        assertThat(first).isEqualTo(second);
        assertThat(first.status()).isEqualTo(PageTypeClassificationStatus.DISABLED);
        assertThat(first.proposals()).isEmpty();
        assertThat(first.tokens()).isEqualTo(AiTokenCounts.ZERO);
    }

    @Test
    void never_throws_for_a_null_request() {
        assertThatCode(() -> new StubPageTypeClassificationAdapter().classify(null))
                .doesNotThrowAnyException();
    }

    @Test
    void the_disabled_factory_spends_nothing_and_proposes_nothing() {
        PageTypeClassificationResult disabled = PageTypeClassificationResult.disabled();

        assertThat(disabled.status()).isEqualTo(PageTypeClassificationStatus.DISABLED);
        assertThat(disabled.proposals()).isEmpty();
        assertThat(disabled.tokens()).isEqualTo(AiTokenCounts.ZERO);
    }

    private static PageTypeClassificationRequest syntheticRequest(String headText) {
        return new PageTypeClassificationRequest(
                List.of(
                        new PageTypeClassificationRequest.CandidatePage(
                                UUID.randomUUID(), 0, headText, "FAKE FOOTER")),
                List.of(
                        new PageTypeClassificationRequest.TypeDescription(
                                "PAYSTUB", "A wage statement for one pay period.")));
    }
}
