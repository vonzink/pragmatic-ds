package com.pragmaticds.rag.service.audit;

import com.pragmaticds.rag.domain.AuditLog;
import com.pragmaticds.rag.repository.AuditLogRepository;
import com.pragmaticds.rag.service.retrieval.RetrievedChunk;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Persists the full compliance audit trail for every AI interaction.
 * Runs in its own transaction (REQUIRES_NEW) so an audit record survives
 * even if the surrounding request transaction rolls back.
 */
@Service
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;
    private final PiiRedactionService piiRedactionService;

    public AuditLogService(AuditLogRepository auditLogRepository,
                           PiiRedactionService piiRedactionService) {
        this.auditLogRepository = auditLogRepository;
        this.piiRedactionService = piiRedactionService;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AuditLog record(UUID conversationId,
                           UUID brainId,
                           String userQuestion,
                           List<RetrievedChunk> retrievedChunks,
                           String finalPrompt,
                           String finalAnswer,
                           String modelProvider,
                           String modelName,
                           Double confidenceScore,
                           boolean fallbackUsed,
                           boolean humanEscalationRequired) {

        AuditLog log = new AuditLog();
        log.setConversationId(conversationId);
        log.setBrainId(brainId);
        log.setUserQuestion(piiRedactionService.redact(userQuestion));
        log.setRetrievedContext(toContextJson(retrievedChunks));
        log.setFinalPrompt(piiRedactionService.redact(finalPrompt));
        log.setFinalAnswer(finalAnswer);
        log.setModelProvider(modelProvider);
        log.setModelName(modelName);
        log.setConfidenceScore(confidenceScore);
        log.setFallbackUsed(fallbackUsed);
        log.setHumanEscalationRequired(humanEscalationRequired);
        return auditLogRepository.save(log);
    }

    private List<Map<String, Object>> toContextJson(List<RetrievedChunk> chunks) {
        if (chunks == null) {
            return List.of();
        }
        return chunks.stream()
                .map(c -> Map.<String, Object>of(
                        "chunk_id", String.valueOf(c.chunkId()),
                        "document_name", String.valueOf(c.documentName()),
                        "source_name", String.valueOf(c.sourceName()),
                        "section", c.section() == null ? "" : c.section(),
                        "vector_score", c.vectorScore(),
                        "keyword_score", c.keywordScore(),
                        "combined_score", c.combinedScore()))
                .toList();
    }
}
