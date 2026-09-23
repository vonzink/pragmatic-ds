package com.pragmaticds.docengine.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.pragmaticds.docengine.orchestration.ParserPort.StageRequest;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StageRequestTest {

    private static final Instant T0 = Instant.parse("2026-09-16T22:00:00Z");

    @Test
    void no_deadline_is_never_past() {
        StageRequest request =
                new StageRequest(UUID.randomUUID(), UUID.randomUUID(), ProcessingStatus.OCR_PROCESSING, 1, "k");
        assertThat(request.deadline()).isNull();
        assertThat(request.pastDeadline(T0.plusSeconds(999_999))).isFalse();
    }

    @Test
    void past_deadline_means_strictly_after_it() {
        StageRequest request =
                new StageRequest(
                        UUID.randomUUID(), UUID.randomUUID(), ProcessingStatus.OCR_PROCESSING, 1, "k", T0);
        assertThat(request.pastDeadline(T0.minusSeconds(1))).isFalse();
        assertThat(request.pastDeadline(T0)).isFalse();
        assertThat(request.pastDeadline(T0.plusMillis(1))).isTrue();
    }
}
