package com.pragmaticds.rag.lab.corpus;

import com.pragmaticds.rag.domain.BrainDocument;
import com.pragmaticds.rag.domain.SourceVisibility;
import com.pragmaticds.rag.repository.BrainDocumentRepository;
import com.pragmaticds.rag.service.retrieval.RetrievalResult;
import com.pragmaticds.rag.service.retrieval.RetrievalService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Retrieves only from exact document facts frozen into a corpus snapshot. */
public interface SnapshotRetrievalService {
    RetrievalResult retrieve(SnapshotRetrievalRequest request);

    record SnapshotRetrievalRequest(
            UUID brainId,
            UUID snapshotId,
            String question,
            SourceVisibility visibility,
            int topK,
            boolean rerank) {}

    final class SnapshotRetrievalException extends RuntimeException {
        public enum Code {
            SNAPSHOT_REQUEST_INVALID,
            CORPUS_SNAPSHOT_DRIFTED
        }

        private final Code code;

        public SnapshotRetrievalException(Code code) {
            super(Objects.requireNonNull(code, "code").name());
            this.code = code;
        }

        public Code code() {
            return code;
        }
    }
}

@Service
@ConditionalOnProperty(prefix = "ragbrain.instances", name = "enabled", havingValue = "true")
class DefaultSnapshotRetrievalService implements SnapshotRetrievalService {
    private final CorpusSnapshotService snapshots;
    private final BrainDocumentRepository documents;
    private final RetrievalService retrieval;

    DefaultSnapshotRetrievalService(CorpusSnapshotService snapshots,
                                    BrainDocumentRepository documents,
                                    RetrievalService retrieval) {
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.documents = Objects.requireNonNull(documents, "documents");
        this.retrieval = Objects.requireNonNull(retrieval, "retrieval");
    }

    @Override
    public RetrievalResult retrieve(SnapshotRetrievalRequest request) {
        validateRequest(request);
        CorpusSnapshotService.FrozenCorpusSnapshot snapshot =
                snapshots.require(request.brainId(), request.snapshotId());
        if (!request.brainId().equals(snapshot.brainId())
                || !request.snapshotId().equals(snapshot.id())) {
            throw drifted();
        }
        validateCurrentFacts(snapshot, LocalDate.now());
        RetrievalResult result = retrieval.retrieveSnapshot(request.question(), request.brainId(),
                request.snapshotId(), request.visibility(), request.topK(), request.rerank());
        // Repository predicates refuse changed rows. Rechecking after external AI I/O
        // converts a concurrent document mutation into an explicit drift failure rather
        // than a silent empty/partial answer.
        validateCurrentFacts(snapshot, LocalDate.now());
        return result;
    }

    private void validateCurrentFacts(
            CorpusSnapshotService.FrozenCorpusSnapshot snapshot, LocalDate today) {
        List<UUID> ids = snapshot.documents().stream()
                .map(CorpusSnapshotService.FrozenDocument::documentId)
                .distinct().sorted().toList();
        Map<UUID, BrainDocument> currentById = new HashMap<>();
        if (!ids.isEmpty()) {
            for (BrainDocument document : documents.findAllById(ids)) {
                if (currentById.put(document.getId(), document) != null) {
                    throw drifted();
                }
            }
        }
        if (currentById.size() != ids.size()) {
            throw drifted();
        }
        for (CorpusSnapshotService.FrozenDocument frozen : snapshot.documents()) {
            BrainDocument current = currentById.get(frozen.documentId());
            if (current == null
                    || !snapshot.brainId().equals(current.getBrainId())
                    || !current.isActive()
                    || !Objects.equals(frozen.documentVersion(), current.getDocumentVersion())
                    || !Objects.equals(frozen.contentSha256(), current.getContentSha256())
                    || frozen.visibility() != current.getVisibility()
                    || frozen.trustLevel() != current.getTrustLevel()
                    || !Objects.equals(frozen.effectiveDate(), current.getEffectiveDate())
                    || !Objects.equals(frozen.expirationDate(), current.getExpirationDate())
                    || (current.getEffectiveDate() != null
                            && current.getEffectiveDate().isAfter(today))
                    || (current.getExpirationDate() != null
                            && current.getExpirationDate().isBefore(today))) {
                throw drifted();
            }
        }
    }

    private static void validateRequest(SnapshotRetrievalRequest request) {
        if (request == null || request.brainId() == null || request.snapshotId() == null
                || request.question() == null || request.question().isBlank()
                || request.visibility() == null || request.topK() < 1 || request.topK() > 100) {
            throw new SnapshotRetrievalException(
                    SnapshotRetrievalException.Code.SNAPSHOT_REQUEST_INVALID);
        }
    }

    private static SnapshotRetrievalException drifted() {
        return new SnapshotRetrievalException(
                SnapshotRetrievalException.Code.CORPUS_SNAPSHOT_DRIFTED);
    }
}
