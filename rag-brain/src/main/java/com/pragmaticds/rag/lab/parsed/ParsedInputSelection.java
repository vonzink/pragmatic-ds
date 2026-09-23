package com.pragmaticds.rag.lab.parsed;

import com.pragmaticds.rag.lab.engine.EngineResultEnvelope;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Narrows a verified envelope to exactly the sources a caller selected.
 *
 * <p>An instance may be pointed at part of a package — two of five uploaded files, say — without
 * the engine reparsing anything. This performs that narrowing as a pure function over an already
 * verified envelope, and it fails closed rather than guessing.
 *
 * <p>Package identity is preserved exactly. The artifact descriptor, envelope and canonicalization
 * versions, package id, generation, and provenance are carried through untouched, so a run pinned
 * to a selection is still pinned to the same immutable parse. Only sources, pages, logical
 * documents, and unassigned pages are narrowed.
 *
 * <p>Three refusals matter, and all three exist because the alternative is silently analyzing
 * something the caller did not select:
 *
 * <ul>
 *   <li>A selected id the envelope does not contain. The caller and the parse disagree about what
 *       exists, so nothing downstream can be trusted.
 *   <li>A logical document whose pages are only partly selected. Keeping it would analyze foreign
 *       pages; dropping it would silently discard a document the selection did reach.
 *   <li>A retained field citing evidence on a page the selection removed. The citation would point
 *       at nothing, which is worse than refusing.
 * </ul>
 */
public final class ParsedInputSelection {

    private ParsedInputSelection() {}

    /** Why a selection cannot be narrowed. Stable, value-free codes only. */
    public enum FailureCode {
        SELECTION_EMPTY,
        SELECTION_DUPLICATE_SOURCE,
        SELECTION_SOURCE_NOT_IN_PARSE,
        SELECTION_SPLITS_DOCUMENT,
        SELECTION_BREAKS_EVIDENCE,
        SELECTION_SELECTS_NO_DOCUMENT
    }

    /** Thrown for every refusal above; carries a code and never a value. */
    public static final class SelectionException extends RuntimeException {
        private final FailureCode code;

        public SelectionException(FailureCode code) {
            super(Objects.requireNonNull(code, "code").name());
            this.code = code;
        }

        public FailureCode code() {
            return code;
        }
    }

    /** One selected source, reconciled against the parse: identity and digest only. */
    public record SelectedSource(UUID sourceId, String contentSha256, int position) {}

    /**
     * The narrowed envelope plus the reconciled selection.
     *
     * <p>{@code sourceSetSha256} is the engine's digest for the whole parse, not a digest of the
     * subset. The parse is what a run is pinned to, and inventing a subset digest would create a
     * second identity nothing else in the system verifies.
     */
    public record SelectionResult(
            EngineResultEnvelope selectedEnvelope,
            List<SelectedSource> selectedSources,
            String sourceSetSha256) {

        public SelectionResult {
            selectedSources = List.copyOf(Objects.requireNonNull(selectedSources, "selectedSources"));
        }
    }

    /**
     * Narrows {@code envelope} to {@code selectedSourceIds}.
     *
     * <p>Selection order is the parse's own source ordinal rather than the order the caller listed
     * ids in, so the same selection expressed two ways produces byte-identical child rows.
     */
    public static SelectionResult select(
            EngineResultEnvelope envelope, List<UUID> selectedSourceIds) {
        Objects.requireNonNull(envelope, "envelope");
        Objects.requireNonNull(selectedSourceIds, "selectedSourceIds");
        if (selectedSourceIds.isEmpty() || selectedSourceIds.stream().anyMatch(Objects::isNull)) {
            throw new SelectionException(FailureCode.SELECTION_EMPTY);
        }
        Set<UUID> requested = new LinkedHashSet<>(selectedSourceIds);
        if (requested.size() != selectedSourceIds.size()) {
            throw new SelectionException(FailureCode.SELECTION_DUPLICATE_SOURCE);
        }

        List<EngineResultEnvelope.SourceFile> keptSources = envelope.sources().stream()
                .filter(source -> requested.contains(source.id()))
                .sorted(java.util.Comparator.comparingInt(EngineResultEnvelope.SourceFile::ordinal))
                .toList();
        if (keptSources.size() != requested.size()) {
            throw new SelectionException(FailureCode.SELECTION_SOURCE_NOT_IN_PARSE);
        }

        List<EngineResultEnvelope.EnginePage> keptPages = envelope.pages().stream()
                .filter(page -> requested.contains(page.sourceFileId()))
                .toList();
        Set<UUID> keptPageIds = keptPages.stream()
                .map(EngineResultEnvelope.EnginePage::id)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        List<EngineResultEnvelope.LogicalDocument> keptDocuments = new ArrayList<>();
        for (EngineResultEnvelope.LogicalDocument document : envelope.documents()) {
            long retained = document.pageIds().stream().filter(keptPageIds::contains).count();
            if (retained == 0) {
                continue;
            }
            if (retained != document.pageIds().size()) {
                throw new SelectionException(FailureCode.SELECTION_SPLITS_DOCUMENT);
            }
            for (EngineResultEnvelope.FieldOccurrence field : document.fields()) {
                for (EngineResultEnvelope.EvidenceSpan span : field.evidence()) {
                    if (!keptPageIds.contains(span.pageId())) {
                        throw new SelectionException(FailureCode.SELECTION_BREAKS_EVIDENCE);
                    }
                }
            }
            keptDocuments.add(document);
        }
        if (keptDocuments.isEmpty()) {
            throw new SelectionException(FailureCode.SELECTION_SELECTS_NO_DOCUMENT);
        }

        List<UUID> keptUnassigned = envelope.unassignedPageIds().stream()
                .filter(keptPageIds::contains)
                .toList();

        EngineResultEnvelope narrowed = new EngineResultEnvelope(
                envelope.artifact(),
                envelope.envelopeVersion(),
                envelope.canonicalizationVersion(),
                envelope.packageId(),
                envelope.generation(),
                keptSources,
                keptPages,
                keptDocuments,
                keptUnassigned,
                envelope.provenance());

        List<SelectedSource> selected = new ArrayList<>(keptSources.size());
        for (int index = 0; index < keptSources.size(); index++) {
            EngineResultEnvelope.SourceFile source = keptSources.get(index);
            selected.add(new SelectedSource(source.id(), source.contentSha256(), index));
        }
        return new SelectionResult(
                narrowed, selected, envelope.generation().sourceSetSha256());
    }
}
