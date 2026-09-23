package com.pragmaticds.docengine.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.pragmaticds.docengine.orchestration.ParserPort.StageOutcome;
import com.pragmaticds.docengine.orchestration.ParserPort.StageRequest;
import com.pragmaticds.docengine.platform.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The stub is the only parser Phase 1 has, so its determinism IS the pipeline's testability: a
 * flaky stub would make every orchestration IT flaky. These tests pin the digest formula, the
 * failure hooks, and the error-code mapping the ITs rely on.
 */
class StubParserAdapterTest {

    private static final UUID JOB = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PACKAGE = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private StubParserAdapter stub;

    @BeforeEach
    void freshStub() {
        stub = new StubParserAdapter();
    }

    private StageRequest request(ProcessingStatus stage, int attempt, String key) {
        return new StageRequest(JOB, PACKAGE, stage, attempt, key);
    }

    @Test
    void success_digest_is_sha256_of_job_id_plus_stage_and_stable() throws Exception {
        StageOutcome first = stub.run(request(ProcessingStatus.RENDERING, 1, "key-1"));
        StageOutcome second = stub.run(request(ProcessingStatus.RENDERING, 2, "key-1"));

        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        String expected =
                HexFormat.of()
                        .formatHex(
                                sha256.digest(
                                        (JOB + ProcessingStatus.RENDERING.name())
                                                .getBytes(StandardCharsets.UTF_8)));

        assertThat(first.success()).isTrue();
        assertThat(first.outputDigest()).isEqualTo(expected);
        assertThat(second.outputDigest()).isEqualTo(expected);
    }

    @Test
    void invocations_counts_per_job_and_stage() {
        assertThat(stub.invocations(JOB, ProcessingStatus.RENDERING)).isZero();

        stub.run(request(ProcessingStatus.RENDERING, 1, "key-1"));
        stub.run(request(ProcessingStatus.RENDERING, 2, "key-1"));
        stub.run(request(ProcessingStatus.PARSING, 1, "key-1"));

        assertThat(stub.invocations(JOB, ProcessingStatus.RENDERING)).isEqualTo(2);
        assertThat(stub.invocations(JOB, ProcessingStatus.PARSING)).isEqualTo(1);
        assertThat(stub.invocations(JOB, ProcessingStatus.OCR_PROCESSING)).isZero();
    }

    @Test
    void fail_stage_fails_the_next_n_invocations_then_recovers() {
        stub.failStage(ProcessingStatus.RENDERING, 2);

        StageOutcome first = stub.run(request(ProcessingStatus.RENDERING, 1, "key-1"));
        StageOutcome second = stub.run(request(ProcessingStatus.RENDERING, 2, "key-1"));
        StageOutcome third = stub.run(request(ProcessingStatus.RENDERING, 3, "key-1"));

        assertThat(first.success()).isFalse();
        assertThat(first.errorCode()).isEqualTo(ErrorCode.RENDER_FAILED);
        assertThat(second.success()).isFalse();
        assertThat(third.success()).isTrue();
    }

    @Test
    void clear_failures_resets_programmed_failures() {
        stub.failStage(ProcessingStatus.RENDERING, 5);
        stub.clearFailures();

        assertThat(stub.run(request(ProcessingStatus.RENDERING, 1, "key-1")).success()).isTrue();
    }

    @Test
    void fail_hard_key_always_fails_that_stage_only() {
        String key = "pkg-abc/fail-hard:RENDERING";

        assertThat(stub.run(request(ProcessingStatus.RENDERING, 1, key)).success()).isFalse();
        assertThat(stub.run(request(ProcessingStatus.RENDERING, 2, key)).success()).isFalse();
        assertThat(stub.run(request(ProcessingStatus.RENDERING, 3, key)).success()).isFalse();
        assertThat(stub.run(request(ProcessingStatus.PARSING, 1, key)).success()).isTrue();
    }

    @Test
    void fail_once_key_fails_only_the_first_invocation() {
        String key = "pkg-abc/fail-once:RENDERING";

        assertThat(stub.run(request(ProcessingStatus.RENDERING, 1, key)).success()).isFalse();
        assertThat(stub.run(request(ProcessingStatus.RENDERING, 2, key)).success()).isTrue();
    }

    @Test
    void failure_codes_map_per_stage() {
        stub.failStage(ProcessingStatus.RENDERING, 1);
        stub.failStage(ProcessingStatus.TEXT_EXTRACTION, 1);
        stub.failStage(ProcessingStatus.OCR_PROCESSING, 1);
        stub.failStage(ProcessingStatus.PARSING, 1);

        assertThat(stub.run(request(ProcessingStatus.RENDERING, 1, "k")).errorCode())
                .isEqualTo(ErrorCode.RENDER_FAILED);
        assertThat(stub.run(request(ProcessingStatus.TEXT_EXTRACTION, 1, "k")).errorCode())
                .isEqualTo(ErrorCode.TEXT_EXTRACTION_FAILED);
        assertThat(stub.run(request(ProcessingStatus.OCR_PROCESSING, 1, "k")).errorCode())
                .isEqualTo(ErrorCode.OCR_FAILED);
        assertThat(stub.run(request(ProcessingStatus.PARSING, 1, "k")).errorCode())
                .isEqualTo(ErrorCode.INTERNAL);
    }

    @Test
    void failure_detail_carries_only_non_sensitive_params() {
        stub.failStage(ProcessingStatus.RENDERING, 1);

        StageOutcome outcome = stub.run(request(ProcessingStatus.RENDERING, 3, "k"));

        assertThat(outcome.detail()).containsEntry("stage", ProcessingStatus.RENDERING.name());
        assertThat(outcome.detail()).containsEntry("attempt", 3);
    }
}
