package com.pragmaticds.rag.lab.analyze;

import com.pragmaticds.rag.lab.analyze.InstanceRunProvenance.ExecutedToolRecord;
import com.pragmaticds.rag.lab.analyze.InstanceRunProvenance.ModelResolution;
import com.pragmaticds.rag.lab.analyze.InstanceRunProvenance.ParsedInputDescriptor;
import com.pragmaticds.rag.lab.analyze.InstanceRunProvenance.RetrievedEvidence;
import com.pragmaticds.rag.lab.release.LabManifestWriter;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a pinned run records about itself.
 *
 * <p>The bytes are sealed, so nothing here can be checked by reading a database column later —
 * determinism and completeness have to be pinned at the point of construction instead.
 */
class InstanceRunProvenanceTest {

    private static final UUID RUN = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID BRAIN = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID RELEASE = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID SNAPSHOT = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID CHUNK_A = UUID.fromString("55555555-5555-4555-8555-55555555550a");
    private static final UUID CHUNK_B = UUID.fromString("55555555-5555-4555-8555-55555555550b");

    private final LabManifestWriter writer = new LabManifestWriter();

    @Test
    void theSameRunProducesTheSameBytes() {
        assertArrayEquals(provenance(evidence(CHUNK_A), evidence(CHUNK_B)).canonicalBytes(writer),
                provenance(evidence(CHUNK_A), evidence(CHUNK_B)).canonicalBytes(writer));
    }

    @Test
    void retrievalOrderIsPartOfTheRecord() {
        // The prompt is built in retrieval order, so two runs that read the same chunks in a
        // different order did not read the same prompt.
        assertFalse(java.util.Arrays.equals(
                provenance(evidence(CHUNK_A), evidence(CHUNK_B)).canonicalBytes(writer),
                provenance(evidence(CHUNK_B), evidence(CHUNK_A)).canonicalBytes(writer)));
    }

    @Test
    void answeringWithADifferentModelIsVisibleRatherThanInferred() {
        InstanceRunProvenance pinned = provenance(evidence(CHUNK_A));
        InstanceRunProvenance substituted = new InstanceRunProvenance(
                RUN, BRAIN, "income", RELEASE, "a".repeat(64), descriptor(), SNAPSHOT,
                "b".repeat(64), List.of(evidence(CHUNK_A)), List.of(tool()),
                new ModelResolution("anthropic", "claude-x", "anthropic", "claude-x",
                        "openai", "gpt-x", true),
                "c".repeat(64), 1200L);

        assertFalse(java.util.Arrays.equals(
                pinned.canonicalBytes(writer), substituted.canonicalBytes(writer)));
    }

    @Test
    void theEvidenceTheModelActuallyReadIsRecordedNotJustItsDigest() {
        // Deliberate: a digest proves the evidence was unchanged but does not let anyone read what
        // the model saw. This is also exactly why the record is only ever stored as ciphertext.
        String canonical = new String(
                provenance(evidence(CHUNK_A)).canonicalBytes(writer), StandardCharsets.UTF_8);

        assertTrue(canonical.contains("escrow accounts are analyzed annually"), canonical);
        assertTrue(canonical.contains("d".repeat(64)), "the content digest travels with the text");
    }

    private InstanceRunProvenance provenance(RetrievedEvidence... evidence) {
        return new InstanceRunProvenance(RUN, BRAIN, "income", RELEASE, "a".repeat(64),
                descriptor(), SNAPSHOT, "b".repeat(64), List.of(evidence), List.of(tool()),
                new ModelResolution("anthropic", "claude-x", "anthropic", "claude-x",
                        "anthropic", "claude-x", false),
                "c".repeat(64), 1200L);
    }

    private static ParsedInputDescriptor descriptor() {
        return new ParsedInputDescriptor(
                UUID.fromString("66666666-6666-4666-8666-666666666666"),
                UUID.fromString("77777777-7777-4777-8777-777777777777"),
                3,
                UUID.fromString("88888888-8888-4888-8888-888888888888"),
                1, "1.0.0", "e".repeat(64), 48211L, "f".repeat(64),
                List.of(UUID.fromString("99999999-9999-4999-8999-999999999999")));
    }

    private static RetrievedEvidence evidence(UUID chunkId) {
        return new RetrievedEvidence(chunkId,
                UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
                "d".repeat(64), "Fannie Mae Selling Guide", "B3-6 Liabilities",
                LocalDate.of(2026, 1, 1), "escrow accounts are analyzed annually");
    }

    private static ExecutedToolRecord tool() {
        return new ExecutedToolRecord("income.total", "1.0.0",
                "1".repeat(64), "2".repeat(64), "EXECUTED");
    }
}
