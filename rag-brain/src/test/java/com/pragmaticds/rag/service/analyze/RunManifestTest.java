package com.pragmaticds.rag.service.analyze;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class RunManifestTest {

    private static final Pattern LOWER_HEX_64 = Pattern.compile("^[0-9a-f]{64}$");

    @Test
    void sha256HexOfBytesIsStableAndLowerHex64() {
        byte[] data = "paystub-bytes".getBytes();
        String h1 = RunManifest.sha256Hex(data);
        String h2 = RunManifest.sha256Hex(data);
        assertEquals(h1, h2, "hashing the same bytes twice must be stable");
        assertTrue(LOWER_HEX_64.matcher(h1).matches(), "expected 64 lowercase hex chars, got: " + h1);
    }

    @Test
    void sha256HexOfStringIsStableAndLowerHex64() {
        String h1 = RunManifest.sha256Hex("the assembled prompt");
        String h2 = RunManifest.sha256Hex("the assembled prompt");
        assertEquals(h1, h2);
        assertTrue(LOWER_HEX_64.matcher(h1).matches(), "expected 64 lowercase hex chars, got: " + h1);
    }

    @Test
    void differentBytesHashDifferently() {
        String h1 = RunManifest.sha256Hex("document A".getBytes());
        String h2 = RunManifest.sha256Hex("document B".getBytes());
        assertNotEquals(h1, h2);
    }

    @Test
    void startHashesEachDocsBytes() {
        DocInput d1 = new DocInput("d1", "paystub.pdf", "application/pdf", "bytes-one".getBytes(), 0L);
        DocInput d2 = new DocInput("d2", "w2.pdf", "application/pdf", "bytes-two".getBytes(), 1L);

        RunManifest m = RunManifest.start(UUID.randomUUID(), "income-v2", "v2", List.of(d1, d2));

        assertEquals(2, m.docs().size());
        assertEquals("d1", m.docs().get(0).get("id"));
        assertEquals("paystub.pdf", m.docs().get(0).get("fileName"));
        assertEquals(RunManifest.sha256Hex("bytes-one".getBytes()), m.docs().get(0).get("sha256"));
        assertEquals(RunManifest.sha256Hex("bytes-two".getBytes()), m.docs().get(1).get("sha256"));
        assertNotEquals(m.docs().get(0).get("sha256"), m.docs().get(1).get("sha256"));
    }

    @Test
    void startAssignsAFreshRunIdAndCopiesFixedFields() {
        UUID brainId = UUID.randomUUID();
        RunManifest m = RunManifest.start(brainId, "income-v2", "v2", List.of());

        assertNotNull(m.runId());
        assertEquals(brainId, m.brainId());
        assertEquals("income-v2", m.analyzerSlug());
        assertEquals("v2", m.envelopeVersion());
        assertTrue(m.retrievedChunkIds().isEmpty());
        assertTrue(m.calcAudit().isEmpty());
        assertNull(m.promptSha256());
    }

    @Test
    void startStillAllocatesADistinctRandomRunIdEveryTime() {
        // Characterization: the raw path's identity is generated here and nowhere else. Adding a
        // caller-allocated constructor for the Lab must not turn this into a fixed or shared id.
        assertNotEquals(
                RunManifest.start(UUID.randomUUID(), "income-v2", "v2", List.of()).runId(),
                RunManifest.start(UUID.randomUUID(), "income-v2", "v2", List.of()).runId());
    }

    @Test
    void startsDocRowKeepsItsExactKeysAndOrder() {
        // Characterization: this map is serialized into analysis_runs.docs. Its keys and their
        // order are the persisted shape, so both are pinned rather than left to drift.
        DocInput d1 = new DocInput("d1", "paystub.pdf", "application/pdf", "bytes-one".getBytes(), 0L);

        RunManifest m = RunManifest.start(UUID.randomUUID(), "income-v2", "v2", List.of(d1));

        assertEquals(List.of("id", "fileName", "sha256"),
                List.copyOf(m.docs().getFirst().keySet()));
    }

    // ---------------------------------------------------------------- parsed source (Lab)

    private static RunManifest.ParsedSource parsedSource() {
        return new RunManifest.ParsedSource(
                UUID.fromString("11111111-1111-4111-8111-111111111111"), 3, 1,
                UUID.fromString("22222222-2222-4222-8222-222222222222"),
                "b2".repeat(32), "c3".repeat(32), 4096, "1.0.0",
                UUID.fromString("99999999-9999-4999-8999-999999999990"), null);
    }

    @Test
    void forParsedSourceUsesTheCallerAllocatedRunId() {
        UUID allocated = UUID.randomUUID();

        RunManifest m = RunManifest.forParsedSource(
                allocated, UUID.randomUUID(), "income-v2", "v2", parsedSource());

        assertEquals(allocated, m.runId(),
                "the Lab request and the analyzer row must share ONE identity");
    }

    @Test
    void forParsedSourceRejectsAnAbsentRunId() {
        assertThrows(NullPointerException.class, () -> RunManifest.forParsedSource(
                null, UUID.randomUUID(), "income-v2", "v2", parsedSource()));
    }

    @Test
    void forParsedSourcePinsEngineIdentityAndCarriesNoFilenameOrValueHash() {
        RunManifest m = RunManifest.forParsedSource(
                UUID.randomUUID(), UUID.randomUUID(), "income-v2", "v2", parsedSource());

        assertEquals(1, m.docs().size());
        var row = m.docs().getFirst();
        assertEquals(List.of("packageId", "packageRevision", "parseGeneration", "processingJobId",
                        "sourceSetSha256", "envelopeSha256", "envelopeByteCount",
                        "engineEnvelopeVersion", "releaseId"),
                List.copyOf(row.keySet()));
        assertEquals("11111111-1111-4111-8111-111111111111", row.get("packageId"));
        assertEquals(3, row.get("packageRevision"));
        assertEquals("c3".repeat(32), row.get("envelopeSha256"));
        assertFalse(row.containsKey("fileName"), "a parsed source has no filename to record");
        assertFalse(row.containsKey("sha256"), "identity is the engine's digest, not a value hash");
        assertThrows(UnsupportedOperationException.class, () -> row.put("fileName", "x.pdf"));
    }

    // ---------------------------------------------------------------- review snapshot pinning

    /**
     * There is no whole-manifest digest accessor on {@link RunManifest} — {@code docs()} (not
     * {@code pinnedSources()}) is the pinned-list accessor, and nothing hashes it as a unit. The
     * substitute for "hashes differently" is the real observable: the pinned row itself gains a
     * key, and the two manifests' pinned rows are therefore unequal.
     */
    @Test
    void aParsedSourceWithAReviewSnapshotHashesDifferentlyFromOneWithout() {
        RunManifest.ParsedSource envelopeOnly = new RunManifest.ParsedSource(
                UUID.fromString("33333333-3333-4333-8333-333333333333"), 3, 1,
                UUID.fromString("44444444-4444-4444-8444-444444444444"),
                "9f".repeat(32), "1a".repeat(32), 4096, "1.0.0",
                UUID.fromString("88888888-8888-4888-8888-888888888888"), null);
        RunManifest.ParsedSource reviewed = new RunManifest.ParsedSource(
                envelopeOnly.packageId(), 3, 1, envelopeOnly.processingJobId(),
                envelopeOnly.sourceSetSha256(), envelopeOnly.envelopeSha256(), 4096, "1.0.0",
                envelopeOnly.releaseId(), "ab".repeat(32));
        UUID run = UUID.randomUUID();
        UUID brain = UUID.randomUUID();

        RunManifest a = RunManifest.forParsedSource(run, brain, "income-v2", "v2", envelopeOnly);
        RunManifest b = RunManifest.forParsedSource(run, brain, "income-v2", "v2", reviewed);

        assertFalse(a.docs().get(0).containsKey("reviewSnapshotSha256"));
        assertEquals("ab".repeat(32), b.docs().get(0).get("reviewSnapshotSha256"));
        assertNotEquals(a.docs(), b.docs());
    }
}
