package com.pragmaticds.rag.service.ingestion;

import com.pragmaticds.rag.domain.BrainDocument;
import com.pragmaticds.rag.dto.IngestionQualityDto;
import com.pragmaticds.rag.repository.BrainDocumentRepository;
import com.pragmaticds.rag.repository.DocumentChunkQualityCounts;
import com.pragmaticds.rag.repository.DocumentChunkRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Computes ingestion-quality metrics (chunk/embedding/hierarchy/citation health)
 * for a brain. All per-chunk counting is done in SQL aggregate queries so the
 * report never hydrates chunk entities — and never their 1536-float embeddings —
 * into the heap, which previously risked OOM on large corpora.
 */
@Service
public class IngestionQualityService {

    private final BrainDocumentRepository documents;
    private final DocumentChunkRepository chunks;

    public IngestionQualityService(BrainDocumentRepository documents, DocumentChunkRepository chunks) {
        this.documents = documents;
        this.chunks = chunks;
    }

    @Transactional(readOnly = true)
    public IngestionQualityDto evaluate(UUID brainId) {
        List<BrainDocument> brainDocuments = documents.findByBrainId(brainId);
        List<DocumentChunkQualityCounts> perDocument = chunks.aggregateQualityByDocument(brainId);
        Map<UUID, DocumentChunkQualityCounts> countsByDocument = perDocument.stream()
                .filter(counts -> counts.getDocumentId() != null)
                .collect(Collectors.toMap(DocumentChunkQualityCounts::getDocumentId,
                        Function.identity(), (a, b) -> a));

        List<IngestionQualityDto.DocumentQualityDto> documentQuality = brainDocuments.stream()
                .sorted(Comparator.comparing(BrainDocument::getTitle,
                        Comparator.nullsLast(String::compareToIgnoreCase)))
                .map(document -> evaluateDocument(document, countsByDocument.get(document.getId())))
                .toList();

        int documentCount = brainDocuments.size();
        int activeDocumentCount = (int) brainDocuments.stream().filter(BrainDocument::isActive).count();

        // Brain-level totals aggregate across every chunk group for the brain,
        // matching the previous whole-brain counts.
        int chunkCount = 0;
        int embeddedChunkCount = 0;
        int childMissingEmbeddingCount = 0;
        int parentChunkCount = 0;
        int childChunkCount = 0;
        int orphanChildChunkCount = 0;
        int emptyChunkCount = 0;
        int missingCitationMetadata = 0;
        for (DocumentChunkQualityCounts counts : perDocument) {
            chunkCount += (int) counts.getChunkCount();
            embeddedChunkCount += (int) counts.getEmbeddedChunkCount();
            childMissingEmbeddingCount += (int) counts.getMissingEmbeddingCount();
            parentChunkCount += (int) counts.getParentChunkCount();
            childChunkCount += (int) counts.getChildChunkCount();
            orphanChildChunkCount += (int) counts.getOrphanChildChunkCount();
            emptyChunkCount += (int) counts.getEmptyChunkCount();
            missingCitationMetadata += (int) counts.getMissingCitationMetadataCount();
        }
        int duplicateChunkTextGroups = (int) chunks.countDuplicateTextGroups(brainId);

        List<String> warnings = brainWarnings(documentQuality, documentCount, chunkCount, childMissingEmbeddingCount,
                orphanChildChunkCount, emptyChunkCount, duplicateChunkTextGroups, missingCitationMetadata);

        return new IngestionQualityDto(
                brainId,
                documentCount,
                activeDocumentCount,
                chunkCount,
                embeddedChunkCount,
                childMissingEmbeddingCount,
                parentChunkCount,
                childChunkCount,
                orphanChildChunkCount,
                emptyChunkCount,
                duplicateChunkTextGroups,
                missingCitationMetadata,
                documentQuality,
                warnings);
    }

    private static IngestionQualityDto.DocumentQualityDto evaluateDocument(BrainDocument document,
                                                                           DocumentChunkQualityCounts counts) {
        int chunkCount = counts == null ? 0 : (int) counts.getChunkCount();
        int embeddedChunkCount = counts == null ? 0 : (int) counts.getEmbeddedChunkCount();
        int childMissingEmbeddingCount = counts == null ? 0 : (int) counts.getMissingEmbeddingCount();
        int parentChunkCount = counts == null ? 0 : (int) counts.getParentChunkCount();
        int childChunkCount = counts == null ? 0 : (int) counts.getChildChunkCount();
        int orphanChildChunkCount = counts == null ? 0 : (int) counts.getOrphanChildChunkCount();
        int emptyChunkCount = counts == null ? 0 : (int) counts.getEmptyChunkCount();
        int missingCitationMetadata = counts == null ? 0 : (int) counts.getMissingCitationMetadataCount();

        List<String> warnings = new ArrayList<>();
        if (chunkCount == 0) {
            warnings.add("Document has no chunks");
        }
        if (childMissingEmbeddingCount > 0) {
            warnings.add("Child chunks missing embeddings: " + childMissingEmbeddingCount);
        }
        if (orphanChildChunkCount > 0) {
            warnings.add("Orphan child chunks: " + orphanChildChunkCount);
        }
        if (emptyChunkCount > 0) {
            warnings.add("Empty chunks: " + emptyChunkCount);
        }
        if (missingCitationMetadata > 0) {
            warnings.add("Child chunks missing citation metadata: " + missingCitationMetadata);
        }

        return new IngestionQualityDto.DocumentQualityDto(
                document.getId(),
                document.getTitle(),
                document.getFileName(),
                document.isActive(),
                chunkCount,
                embeddedChunkCount,
                childMissingEmbeddingCount,
                parentChunkCount,
                childChunkCount,
                orphanChildChunkCount,
                emptyChunkCount,
                missingCitationMetadata,
                List.copyOf(warnings));
    }

    private static List<String> brainWarnings(List<IngestionQualityDto.DocumentQualityDto> documents,
                                              int documentCount,
                                              int chunkCount,
                                              int childMissingEmbeddingCount,
                                              int orphanChildChunkCount,
                                              int emptyChunkCount,
                                              int duplicateChunkTextGroups,
                                              int missingCitationMetadata) {
        List<String> warnings = new ArrayList<>();
        if (documentCount == 0) {
            warnings.add("No documents indexed");
        }
        long documentsWithoutChunks = documents.stream()
                .filter(document -> document.chunkCount() == 0)
                .count();
        if (documentsWithoutChunks > 0) {
            warnings.add("Documents without chunks: " + documentsWithoutChunks);
        }
        if (chunkCount == 0 && documentCount > 0) {
            warnings.add("No chunks indexed");
        }
        if (childMissingEmbeddingCount > 0) {
            warnings.add("Child chunks missing embeddings: " + childMissingEmbeddingCount);
        }
        if (orphanChildChunkCount > 0) {
            warnings.add("Orphan child chunks: " + orphanChildChunkCount);
        }
        if (emptyChunkCount > 0) {
            warnings.add("Empty chunks: " + emptyChunkCount);
        }
        if (duplicateChunkTextGroups > 0) {
            warnings.add("Duplicate chunk text groups: " + duplicateChunkTextGroups);
        }
        if (missingCitationMetadata > 0) {
            warnings.add("Child chunks missing citation metadata: " + missingCitationMetadata);
        }
        return List.copyOf(warnings);
    }
}
