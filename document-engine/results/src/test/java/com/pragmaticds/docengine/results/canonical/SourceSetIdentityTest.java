package com.pragmaticds.docengine.results.canonical;

import static org.assertj.core.api.Assertions.assertThat;

import com.pragmaticds.docengine.results.canonical.MachineResultSnapshot.Source;
import com.pragmaticds.docengine.results.domain.ReuseEligibility;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The one source-set digest algorithm, shared by the envelope assembler (which persists it as
 * {@code engine_result.source_set_sha256} through the finalizer) and the upload-time reuse probe
 * (which recomputes it prospectively). This test protects the extraction: the helper's digest must
 * BE the digest the assembler embeds and the finalizer stores — one algorithm, not two copies.
 */
class SourceSetIdentityTest {

    private static final UUID PACKAGE_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID JOB_ID = UUID.fromString("00000000-0000-0000-0000-000000000102");
    private static final UUID SOURCE_0 = UUID.fromString("00000000-0000-0000-0000-000000000110");
    private static final UUID SOURCE_1 = UUID.fromString("00000000-0000-0000-0000-000000000111");

    private final EngineResultEnvelopeAssembler assembler = new EngineResultEnvelopeAssembler();

    @Test
    void digestMatchesFinalizerStoredValue() {
        List<Source> sources =
                List.of(
                        new Source(SOURCE_0, 0, "a".repeat(64), 1234L, "application/pdf"),
                        new Source(SOURCE_1, 1, "b".repeat(64), 987654321L, "image/png"));
        MachineResultSnapshot snapshot =
                new MachineResultSnapshot(
                        PACKAGE_ID,
                        JOB_ID,
                        1,
                        sources,
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of());
        EnvelopeAssemblyRequest request =
                new EnvelopeAssemblyRequest(
                        PACKAGE_ID,
                        JOB_ID,
                        1,
                        1,
                        "1.0.0",
                        "DOCENGINE-C14N-1",
                        ReuseEligibility.PARSE_ONCE_CURRENT_PACKAGE);

        String storedByFinalizer = assembler.assemble(request, snapshot).sourceSetSha256();

        String helperDigest =
                SourceSetIdentity.digest(
                        List.of(
                                new SourceSetIdentity.Entry(
                                        0, "a".repeat(64), 1234L, "application/pdf"),
                                new SourceSetIdentity.Entry(
                                        1, "b".repeat(64), 987654321L, "image/png")));
        assertThat(helperDigest).isEqualTo(storedByFinalizer);
    }

    @Test
    void entryOrderIsOwnedByTheOrdinalNotTheCaller() {
        String inOrder =
                SourceSetIdentity.digest(
                        List.of(
                                new SourceSetIdentity.Entry(0, "a".repeat(64), 1L, "application/pdf"),
                                new SourceSetIdentity.Entry(1, "b".repeat(64), 2L, "image/png")));
        String reversedCallerOrder =
                SourceSetIdentity.digest(
                        List.of(
                                new SourceSetIdentity.Entry(1, "b".repeat(64), 2L, "image/png"),
                                new SourceSetIdentity.Entry(0, "a".repeat(64), 1L, "application/pdf")));
        assertThat(reversedCallerOrder).isEqualTo(inOrder);
    }

    @Test
    void ordinalShaSizeAndSniffedTypeEachChangeTheDigest() {
        SourceSetIdentity.Entry base =
                new SourceSetIdentity.Entry(0, "a".repeat(64), 1L, "application/pdf");
        String baseline = SourceSetIdentity.digest(List.of(base));

        assertThat(
                        SourceSetIdentity.digest(
                                List.of(
                                        new SourceSetIdentity.Entry(
                                                1, "a".repeat(64), 1L, "application/pdf"))))
                .isNotEqualTo(baseline);
        assertThat(
                        SourceSetIdentity.digest(
                                List.of(
                                        new SourceSetIdentity.Entry(
                                                0, "c".repeat(64), 1L, "application/pdf"))))
                .isNotEqualTo(baseline);
        assertThat(
                        SourceSetIdentity.digest(
                                List.of(
                                        new SourceSetIdentity.Entry(
                                                0, "a".repeat(64), 2L, "application/pdf"))))
                .isNotEqualTo(baseline);
        assertThat(
                        SourceSetIdentity.digest(
                                List.of(new SourceSetIdentity.Entry(0, "a".repeat(64), 1L, "image/png"))))
                .isNotEqualTo(baseline);
        // A subset never matches the whole set.
        assertThat(
                        SourceSetIdentity.digest(
                                List.of(
                                        base,
                                        new SourceSetIdentity.Entry(
                                                1, "b".repeat(64), 2L, "image/png"))))
                .isNotEqualTo(baseline);
    }
}
