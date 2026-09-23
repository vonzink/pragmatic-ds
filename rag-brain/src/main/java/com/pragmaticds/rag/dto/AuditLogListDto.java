package com.pragmaticds.rag.dto;

import com.pragmaticds.rag.domain.AuditLog;

import java.time.OffsetDateTime;
import java.util.UUID;

/** One audit row in the dashboard table — intentionally light. */
public record AuditLogListDto(
        UUID id,
        OffsetDateTime createdAt,
        String question,
        Double confidence,
        String modelProvider,
        String modelName,
        boolean fallbackUsed,
        boolean escalated
) {
    public static AuditLogListDto from(AuditLog log) {
        return new AuditLogListDto(log.getId(), log.getCreatedAt(), log.getUserQuestion(),
                log.getConfidenceScore(), log.getModelProvider(), log.getModelName(),
                log.isFallbackUsed(), log.isHumanEscalationRequired());
    }
}
