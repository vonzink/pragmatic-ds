package com.pragmaticds.rag.dto;

import com.pragmaticds.rag.domain.AuditLog;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Expanded audit row: adds the final answer and the retrieved sources. */
public record AuditLogDetailDto(
        UUID id,
        OffsetDateTime createdAt,
        String question,
        String rewrittenQuestion,
        String answer,
        Double confidence,
        String modelProvider,
        String modelName,
        boolean fallbackUsed,
        boolean escalated,
        List<Map<String, Object>> sources
) {
    public static AuditLogDetailDto from(AuditLog log) {
        return new AuditLogDetailDto(log.getId(), log.getCreatedAt(), log.getUserQuestion(),
                log.getRewrittenQuestion(), log.getFinalAnswer(), log.getConfidenceScore(),
                log.getModelProvider(), log.getModelName(), log.isFallbackUsed(),
                log.isHumanEscalationRequired(),
                log.getRetrievedContext() == null ? List.of() : log.getRetrievedContext());
    }
}
