package com.pragmaticds.rag.service.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * The corpus manifest (<prefix>_manifest.json): per-file metadata over
 * defaults. Mirror of scripts/s3-ingest/plan.mjs resolveEntry — including the
 * hard fallbacks, so a missing or broken manifest never blocks a sync.
 * The Java side has since grown past the script: visibility/trustLevel,
 * basename titles, and analyzerScope exist only here.
 */
public final class SyncManifest {

    private static final Logger log = LoggerFactory.getLogger(SyncManifest.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Resolved metadata for one file; dates stay strings until execution. */
    public record Entry(String fileName, boolean ingest, String reason, String title,
                        String sourceName, String sourceType, String visibility, String trustLevel, String documentVersion,
                        String effectiveDate, String expirationDate, String analyzerScope) {}

    private final JsonNode root;

    private SyncManifest(JsonNode root) {
        this.root = root;
    }

    public static SyncManifest parse(Optional<byte[]> manifestBytes) {
        if (manifestBytes.isEmpty()) {
            return new SyncManifest(JSON.createObjectNode());
        }
        try {
            return new SyncManifest(JSON.readTree(manifestBytes.get()));
        } catch (Exception e) {
            log.warn("Corpus manifest is unreadable ({}); syncing with defaults only", e.getMessage());
            return new SyncManifest(JSON.createObjectNode());
        }
    }

    /**
     * analyzerScope precedence: per-file entry → subfolder-derived → defaults → null.
     * A resolved scope of literal {@code "shared"} (from any of the three
     * sources — a shared/ subfolder, a per-file entry, or defaults) is
     * normalized to null: retrieval treats only {@code analyzer_scope IS NULL}
     * as shared, so a literal "shared" row would ground NO analyzer.
     */
    public Entry resolve(String fileName) {
        JsonNode defaults = root.path("defaults");
        JsonNode entry = root.path("files").path(fileName);
        String derivedScope = deriveScope(fileName);
        String resolvedScope = text(entry, "analyzerScope",
                derivedScope != null
                        ? derivedScope
                        : text(defaults, "analyzerScope", null));
        if (SyncPlanner.SHARED_SCOPE.equals(resolvedScope)) {
            resolvedScope = null;
        }
        return new Entry(
                fileName,
                !entry.path("ingest").isBoolean() || entry.path("ingest").asBoolean(),
                text(entry, "reason", null),
                text(entry, "title", deriveTitle(baseName(fileName))),
                text(entry, "sourceName", text(defaults, "sourceName", "Generic Knowledge Base")),
                text(entry, "sourceType", text(defaults, "sourceType", "AGENCY_GUIDELINE")),
                // No hard fallback: an undeclared visibility stays null so the
                // ingestion pipeline can honor the document's own frontmatter
                // declaration (and only then default to INTERNAL).
                text(entry, "visibility", text(defaults, "visibility", null)),
                text(entry, "trustLevel", text(defaults, "trustLevel", "APPROVED")),
                text(entry, "documentVersion", null),
                text(entry, "effectiveDate", null),
                text(entry, "expirationDate", null),
                resolvedScope);
    }

    /** "va_loan-guide.pdf" -> "va loan guide" (plan.mjs deriveTitle). */
    static String deriveTitle(String fileName) {
        return fileName.replaceFirst("\\.[^.]+$", "").replaceAll("[_-]+", " ").strip();
    }

    /** "income/b3-notes.md" -> "income"; a top-level file has no scope (null). */
    static String deriveScope(String fileName) {
        int slash = fileName.indexOf('/');
        return slash > 0 ? fileName.substring(0, slash) : null;
    }

    /**
     * "income/b3-notes.md" -> "b3-notes.md"; "b3-notes.md" -> "b3-notes.md"
     * (no directory).
     */
    static String baseName(String fileName) {
        int slash = fileName.lastIndexOf('/');
        return slash >= 0 ? fileName.substring(slash + 1) : fileName;
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : fallback;
    }
}
