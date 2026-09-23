package com.pragmaticds.rag.service.analyze;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Mutable per-run accumulator for the analysis_runs manifest. Created right after
 * analyzer resolution; fields fill in as the pipeline progresses; persisted by
 * AnalysisRunRecorder at every terminal outcome. Holds hashes and ids only —
 * never document bytes or extracted text.
 */
public class RunManifest {

    private final UUID runId;
    private final UUID brainId;
    private final String analyzerSlug;
    private final String envelopeVersion;
    private final List<Map<String, Object>> docs;

    private String promptSha256;
    private List<String> retrievedChunkIds = List.of();
    private List<Map<String, Object>> calcAudit = List.of();

    private RunManifest(UUID runId, UUID brainId, String analyzerSlug, String envelopeVersion,
                        List<Map<String, Object>> docs) {
        this.runId = runId;
        this.brainId = brainId;
        this.analyzerSlug = analyzerSlug;
        this.envelopeVersion = envelopeVersion;
        this.docs = docs;
    }

    public static RunManifest start(UUID brainId, String analyzerSlug, String envelopeVersion,
                                    List<DocInput> inputs) {
        List<Map<String, Object>> docs = inputs.stream().map(d -> {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("id", d.id());
            m.put("fileName", d.fileName());
            m.put("sha256", sha256Hex(d.bytes()));
            return m;
        }).toList();
        return new RunManifest(UUID.randomUUID(), brainId, analyzerSlug, envelopeVersion, docs);
    }

    /**
     * The immutable engine revision one parsed Lab run analyzed.
     *
     * <p>Every member is an identifier, a digest, a count, or a version — the parsed source is
     * pinned by the engine's own identity, so nothing here is derived from a borrower value and
     * there is no filename to carry. {@code envelopeSha256} is the digest of the exact bytes the
     * adapter received and verified, never a rehash of the parsed view.
     */
    public record ParsedSource(
            UUID packageId,
            int packageRevision,
            int parseGeneration,
            UUID processingJobId,
            String sourceSetSha256,
            String envelopeSha256,
            int envelopeByteCount,
            String engineEnvelopeVersion,
            UUID releaseId,
            String reviewSnapshotSha256) {}

    /**
     * A manifest for a parsed Lab run, sharing ONE analyzer identity with the Lab request.
     *
     * <p>The run id is supplied rather than generated because the Lab allocates it before the
     * analyzer executes and pins that exact {@code analysis_runs.id} on success. {@link #start}
     * keeps its random id, so the raw path is byte-for-byte unchanged.
     */
    public static RunManifest forParsedSource(UUID runId, UUID brainId, String analyzerSlug,
                                              String envelopeVersion, ParsedSource source) {
        Map<String, Object> pinned = new LinkedHashMap<>();
        pinned.put("packageId", source.packageId().toString());
        pinned.put("packageRevision", source.packageRevision());
        pinned.put("parseGeneration", source.parseGeneration());
        pinned.put("processingJobId", source.processingJobId().toString());
        pinned.put("sourceSetSha256", source.sourceSetSha256());
        pinned.put("envelopeSha256", source.envelopeSha256());
        pinned.put("envelopeByteCount", source.envelopeByteCount());
        pinned.put("engineEnvelopeVersion", source.engineEnvelopeVersion());
        pinned.put("releaseId", source.releaseId().toString());
        if (source.reviewSnapshotSha256() != null) {
            pinned.put("reviewSnapshotSha256", source.reviewSnapshotSha256());
        }
        return new RunManifest(Objects.requireNonNull(runId, "runId"), brainId, analyzerSlug,
                envelopeVersion, List.of(Collections.unmodifiableMap(pinned)));
    }

    public static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static String sha256Hex(String s) {
        return sha256Hex(s.getBytes(StandardCharsets.UTF_8));
    }

    public UUID runId() { return runId; }
    public UUID brainId() { return brainId; }
    public String analyzerSlug() { return analyzerSlug; }
    public String envelopeVersion() { return envelopeVersion; }
    public List<Map<String, Object>> docs() { return docs; }
    public String promptSha256() { return promptSha256; }
    public void setPromptSha256(String v) { this.promptSha256 = v; }
    public List<String> retrievedChunkIds() { return retrievedChunkIds; }
    public void setRetrievedChunkIds(List<String> v) { this.retrievedChunkIds = v; }
    public List<Map<String, Object>> calcAudit() { return calcAudit; }
    public void setCalcAudit(List<Map<String, Object>> v) { this.calcAudit = v; }

    /** How many calculation requests ended in ERROR on this run — a count derived from the audit rows. */
    public int calculationsFailed() {
        if (calcAudit == null) {
            return 0;
        }
        return (int) calcAudit.stream().filter(row -> "ERROR".equals(row.get("status"))).count();
    }
}
