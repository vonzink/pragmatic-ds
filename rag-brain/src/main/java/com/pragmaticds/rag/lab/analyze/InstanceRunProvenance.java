package com.pragmaticds.rag.lab.analyze;

import com.pragmaticds.rag.lab.release.LabManifestWriter;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Everything a pinned run was produced by, as one canonical record.
 *
 * <p>The question this exists to answer is "what exactly produced this answer, and could it be
 * produced again?". So it records identity at every layer a run depends on — the release, the parse
 * and the exact sources selected from it, the corpus snapshot, each retrieved chunk with the digest
 * of the bytes it came from, each pinned tool, and which model actually answered versus which one
 * was asked for.
 *
 * <p>It deliberately includes the retrieved chunk text that went into the prompt. Digests alone
 * would prove the evidence was unchanged but not let anyone read what the model actually saw, and
 * an answer nobody can reconstruct the input to is not auditable. That text is borrower-adjacent,
 * which is why this record exists only as AES-256-GCM ciphertext in {@code lab_run_payload} and
 * never in a relational column, a log line, or an audit row — those hold ids, digests, and counts.
 */
public record InstanceRunProvenance(
        UUID runId,
        UUID brainId,
        String instanceSlug,
        UUID releaseId,
        String releaseManifestSha256,
        ParsedInputDescriptor parsedInput,
        UUID corpusSnapshotId,
        String corpusSnapshotSha256,
        List<RetrievedEvidence> retrieved,
        List<ExecutedToolRecord> tools,
        ModelResolution model,
        String outputSchemaSha256,
        long latencyMillis) {

    public InstanceRunProvenance {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(brainId, "brainId");
        Objects.requireNonNull(releaseId, "releaseId");
        retrieved = List.copyOf(Objects.requireNonNull(retrieved, "retrieved"));
        tools = List.copyOf(Objects.requireNonNull(tools, "tools"));
    }

    /** The exact immutable parse, and exactly which of its sources this run analyzed. */
    public record ParsedInputDescriptor(
            UUID registrationId,
            UUID packageId,
            int revision,
            UUID processingJobId,
            int parseGeneration,
            String envelopeVersion,
            String envelopeSha256,
            long envelopeSizeBytes,
            String sourceSetSha256,
            List<UUID> selectedSourceIds) {

        public ParsedInputDescriptor {
            selectedSourceIds = List.copyOf(
                    Objects.requireNonNull(selectedSourceIds, "selectedSourceIds"));
        }
    }

    /** One retrieved chunk: where it came from, what it hashed to, and what the model read. */
    public record RetrievedEvidence(
            UUID chunkId,
            UUID documentId,
            String contentSha256,
            String sourceName,
            String documentTitle,
            LocalDate effectiveDate,
            String content) {}

    /** One pinned tool and whether it ran. Identity is all four fields, as the registry requires. */
    public record ExecutedToolRecord(
            String name,
            String version,
            String inputSchemaSha256,
            String outputSchemaSha256,
            String status) {}

    /**
     * What was asked for versus what answered. Recorded separately because they can differ, and a
     * run that silently answered from something other than its pinned model must be visible here
     * rather than inferred from its absence.
     */
    public record ModelResolution(
            String requestedProvider,
            String requestedModel,
            String resolvedProvider,
            String resolvedModel,
            String answeringProvider,
            String answeringModel,
            boolean fallbackUsed) {}

    /**
     * Canonical bytes for sealing, through the same writer a release manifest uses.
     *
     * <p>Every collection is written in the order it was built — retrieval order, tool declaration
     * order, caller selection order — so the same run produces the same bytes. Nothing here is
     * sourced from an unordered collection.
     */
    public byte[] canonicalBytes(LabManifestWriter writer) {
        Objects.requireNonNull(writer, "writer");
        return writer.canonicalize(asMap());
    }

    private Map<String, Object> asMap() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("provenanceVersion", 1);
        root.put("runId", runId.toString());
        root.put("brainId", brainId.toString());
        root.put("instanceSlug", instanceSlug);
        root.put("releaseId", releaseId.toString());
        root.put("releaseManifestSha256", releaseManifestSha256);
        root.put("parsedInput", parsedInputMap());
        root.put("corpusSnapshotId", corpusSnapshotId == null ? null : corpusSnapshotId.toString());
        root.put("corpusSnapshotSha256", corpusSnapshotSha256);
        root.put("retrieved", retrievedList());
        root.put("tools", toolList());
        root.put("model", modelMap());
        root.put("outputSchemaSha256", outputSchemaSha256);
        root.put("latencyMillis", latencyMillis);
        return root;
    }

    private Map<String, Object> parsedInputMap() {
        if (parsedInput == null) {
            return null;
        }
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("registrationId", text(parsedInput.registrationId()));
        value.put("packageId", text(parsedInput.packageId()));
        value.put("revision", parsedInput.revision());
        value.put("processingJobId", text(parsedInput.processingJobId()));
        value.put("parseGeneration", parsedInput.parseGeneration());
        value.put("envelopeVersion", parsedInput.envelopeVersion());
        value.put("envelopeSha256", parsedInput.envelopeSha256());
        value.put("envelopeSizeBytes", parsedInput.envelopeSizeBytes());
        value.put("sourceSetSha256", parsedInput.sourceSetSha256());
        value.put("selectedSourceIds",
                parsedInput.selectedSourceIds().stream().map(UUID::toString).toList());
        return value;
    }

    private List<Map<String, Object>> retrievedList() {
        List<Map<String, Object>> values = new ArrayList<>(retrieved.size());
        for (RetrievedEvidence evidence : retrieved) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("chunkId", text(evidence.chunkId()));
            value.put("documentId", text(evidence.documentId()));
            value.put("contentSha256", evidence.contentSha256());
            value.put("sourceName", evidence.sourceName());
            value.put("documentTitle", evidence.documentTitle());
            value.put("effectiveDate", evidence.effectiveDate() == null
                    ? null : evidence.effectiveDate().toString());
            value.put("content", evidence.content());
            values.add(value);
        }
        return values;
    }

    private List<Map<String, Object>> toolList() {
        List<Map<String, Object>> values = new ArrayList<>(tools.size());
        for (ExecutedToolRecord tool : tools) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("name", tool.name());
            value.put("version", tool.version());
            value.put("inputSchemaSha256", tool.inputSchemaSha256());
            value.put("outputSchemaSha256", tool.outputSchemaSha256());
            value.put("status", tool.status());
            values.add(value);
        }
        return values;
    }

    private Map<String, Object> modelMap() {
        if (model == null) {
            return null;
        }
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("requestedProvider", model.requestedProvider());
        value.put("requestedModel", model.requestedModel());
        value.put("resolvedProvider", model.resolvedProvider());
        value.put("resolvedModel", model.resolvedModel());
        value.put("answeringProvider", model.answeringProvider());
        value.put("answeringModel", model.answeringModel());
        value.put("fallbackUsed", model.fallbackUsed());
        return value;
    }

    private static String text(UUID value) {
        return value == null ? null : value.toString();
    }
}
